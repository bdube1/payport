package zw.co.payport.gateway.psp.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.psp.common.Api;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.ledger.LedgerService;
import zw.co.payport.gateway.psp.ledger.MerchantFundsService;
import zw.co.payport.gateway.psp.ledger.domain.AccountCategory;
import zw.co.payport.gateway.psp.ledger.domain.AccountKey;
import zw.co.payport.gateway.psp.legacy.LegacyGateway;
import zw.co.payport.gateway.psp.legacy.LegacyPayments;
import zw.co.payport.gateway.psp.platform.idempotency.IdempotentHttp;
import zw.co.payport.gateway.psp.routing.RoutingService;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Partner API: HMAC-signed (see RequestSigning), optional mTLS, scoped keys,
 * Idempotency-Key on every POST. A partner is bound to one merchant account.
 *
 *   POST /api/v1/partner/payments            start a wallet/card collection (method AUTO = smart routing by number)
 *   GET  /api/v1/partner/payments/{reference}  status
 *   GET  /api/v1/partner/balance             ledger balances
 *   GET  /api/v1/partner/statement           available-balance statement
 */
@RestController
@RequestMapping("/api/v1/partner")
@PreAuthorize("hasRole('PARTNER')")
public class PartnerApiController {

    public record PaymentBody(String reference, long amountMinor, String currency, String customerMsisdn, String method, String description) {}

    private final LegacyPayments payments;
    private final LegacyGateway gateway;
    private final RoutingService routing;
    private final MerchantFundsService funds;
    private final LedgerService ledger;
    private final IdempotentHttp idem;
    private final ObjectMapper om;

    public PartnerApiController(LegacyPayments payments, LegacyGateway gateway, RoutingService routing, MerchantFundsService funds,
                                LedgerService ledger, IdempotentHttp idem, ObjectMapper om) {
        this.payments = payments;
        this.gateway = gateway;
        this.routing = routing;
        this.funds = funds;
        this.ledger = ledger;
        this.idem = idem;
        this.om = om;
    }

    @PostMapping("/payments")
    @PreAuthorize("hasAuthority('SCOPE_payments:write')")
    public ResponseEntity<String> create(@RequestBody PaymentBody b, @RequestHeader(value = IdempotentHttp.HEADER, required = false) String key,
                                         HttpServletRequest req) throws Exception {
        SignedRequestFilter.PartnerPrincipal p = principal();
        long merchantId = merchantOf(p);
        if (key == null || key.isBlank()) throw PspException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "Send an Idempotency-Key header");
        if (b.reference() == null || b.reference().isBlank()) throw PspException.badRequest("REFERENCE_REQUIRED", "reference is required");
        return idem.run("partner:" + p.keyId(), key, req, om.writeValueAsString(b), 201, () -> {
            try {
                String method = b.method() == null || "AUTO".equalsIgnoreCase(b.method())
                        ? routing.walletProviderFor(b.customerMsisdn(), b.currency(), b.amountMinor()) : b.method().toUpperCase();
                long sessionId = payments.createCheckout(merchantId, b.reference(), b.amountMinor(), b.currency(), b.description(), b.customerMsisdn());
                var r = payments.payCheckout(sessionId, b.customerMsisdn(), method, req.getRemoteAddr());
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("reference", b.reference());
                out.put("sessionId", sessionId);
                out.put("method", method);
                out.put("status", r.session().getStatus());
                out.put("transactionReference", r.session().getTransactionReference());
                if (r.widgetJsUrl() != null) out.put("cardWidgetUrl", r.widgetJsUrl());
                return out;
            } catch (PspException e) {
                throw e;
            } catch (Exception e) {
                throw PspException.unprocessable("PAYMENT_NOT_STARTED", e.getMessage());
            }
        }, out -> String.valueOf(out.get("sessionId")));
    }

    @GetMapping("/payments/{reference}")
    @PreAuthorize("hasAuthority('SCOPE_payments:read') or hasAuthority('SCOPE_payments:write')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(@PathVariable String reference, HttpServletRequest req) {
        long merchantId = merchantOf(principal());
        LegacyGateway.CheckoutLink c = gateway.checkoutByMerchantReference(merchantId, reference)
                .orElseThrow(() -> PspException.notFound("Payment", reference));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reference", reference);
        out.put("status", c.status());
        out.put("amountMinor", c.amountCents());
        out.put("currency", c.currency());
        gateway.transactionReferenceOfSession(c.sessionId()).flatMap(gateway::transaction).ifPresent(t -> {
            out.put("transactionReference", t.reference());
            out.put("paymentStatus", t.status().name());
            out.put("providerReference", t.providerReference());
        });
        return Api.ok(out, req);
    }

    @GetMapping("/balance")
    @PreAuthorize("hasAuthority('SCOPE_balance:read')")
    public ResponseEntity<ApiResponse<List<MerchantFundsService.MerchantBalances>>> balance(HttpServletRequest req) {
        long merchantId = merchantOf(principal());
        return Api.ok(List.of(funds.balances(merchantId, "USD"), funds.balances(merchantId, "ZWG")), req);
    }

    @GetMapping("/statement")
    @PreAuthorize("hasAuthority('SCOPE_statement:read')")
    public ResponseEntity<ApiResponse<List<LedgerService.StatementLine>>> statement(@RequestParam(defaultValue = "USD") String currency,
                                                                                   @RequestParam(defaultValue = "7") int days, HttpServletRequest req) {
        long merchantId = merchantOf(principal());
        return Api.ok(ledger.statement(AccountKey.merchant(AccountCategory.MERCHANT_AVAILABLE, merchantId, currency),
                LocalDateTime.now().minusDays(Math.min(days, 90)), LocalDateTime.now().plusMinutes(1), 1000), req);
    }

    static SignedRequestFilter.PartnerPrincipal principal() {
        Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (p instanceof SignedRequestFilter.PartnerPrincipal pp) return pp;
        throw PspException.forbidden("PARTNER_AUTH_REQUIRED", "Signed partner request required");
    }

    static long merchantOf(SignedRequestFilter.PartnerPrincipal p) {
        if (p.merchantId() == null) throw PspException.forbidden("NO_MERCHANT", "This key is not bound to a merchant account");
        return p.merchantId();
    }
}
