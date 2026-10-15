package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.config.ApiKeyAuthenticationFilter;
import zw.co.payport.gateway.entity.CheckoutSession;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.service.CheckoutSessionService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The actual developer API a merchant's SERVER calls -- authenticated by
 * secret API key (see ApiKeyAuthenticationFilter), never by the JWT
 * scheme customers/staff/merchant-dashboard-users use.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/merchant")
@RequiredArgsConstructor
@PreAuthorize("hasRole('MERCHANT')")
public class CheckoutSessionController {

    private final CheckoutSessionService checkoutSessionService;
    private final zw.co.payport.gateway.repository.CheckoutSessionRepository checkoutSessionRepository;

    public record CreateSessionBody(
            String reference, Long amountCents, String currency, String description,
            String customerPhone, String customerEmail,
            String successUrl, String cancelUrl, String metadata) {}

    @PostMapping("/checkout-sessions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody CreateSessionBody body, HttpServletRequest req) {
        try {
            Merchant merchant = currentMerchant();
            var createReq = new CheckoutSessionService.CreateSessionRequest(
                    body.reference(), body.amountCents(), body.currency(), body.description(),
                    body.customerPhone(), body.customerEmail(),
                    body.successUrl(), body.cancelUrl(), body.metadata()
            );
            CheckoutSession session = checkoutSessionService.createSession(merchant, currentApiKeyId(), createReq);
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toMap(session), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("CHECKOUT_SESSION_CREATE_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @GetMapping("/checkout-sessions/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable Long id, HttpServletRequest req) {
        try {
            CheckoutSession session = checkoutSessionService.getForMerchant(currentMerchant(), id);
            return ResponseEntity.ok(ApiResponse.success(toMap(session), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("NOT_FOUND", e.getMessage(), requestId(req)));
        }
    }

    /** Lookup by the merchant-facing reference string, not the internal id -- for the "type in a reference to raise a query about it" flow. Scoped to the merchant's own sessions, same as get-by-id. */
    @GetMapping("/checkout-sessions/by-reference/{reference}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getByReference(@PathVariable String reference, HttpServletRequest req) {
        Merchant merchant = currentMerchant();
        return checkoutSessionRepository.findByMerchantIdAndReference(merchant.getId(), reference)
                .map(s -> ResponseEntity.ok(ApiResponse.success(toMap(s), requestId(req))))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("NOT_FOUND", "No transaction found with reference: " + reference, requestId(req))));
    }

    @PostMapping("/checkout-sessions/{id}/cancel")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cancel(@PathVariable Long id, HttpServletRequest req) {
        try {
            CheckoutSession session = checkoutSessionService.cancel(currentMerchant(), id);
            return ResponseEntity.ok(ApiResponse.success(toMap(session), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("CANCEL_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @GetMapping("/checkout-sessions")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(
            @RequestParam(required = false) String status, HttpServletRequest req) {
        Merchant merchant = currentMerchant();
        List<CheckoutSession> sessions = checkoutSessionService.listForMerchant(merchant.getId());
        if (status != null && !status.isBlank()) {
            sessions = sessions.stream().filter(s -> s.getStatus().equalsIgnoreCase(status)).toList();
        }
        List<Map<String, Object>> body = sessions.stream().map(this::toMap).toList();
        return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
    }

    @GetMapping("/balance")
    public ResponseEntity<ApiResponse<Map<String, Object>>> balance(HttpServletRequest req) {
        Merchant merchant = currentMerchant();
        Map<String, CheckoutSessionService.CurrencyTotals> byCurrency = checkoutSessionService.balanceByCurrency(merchant.getId());

        Map<String, Object> currencies = new LinkedHashMap<>();
        byCurrency.forEach((currency, t) -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("grossCents", t.grossCents());
            c.put("commissionCents", t.commissionCents());
            c.put("netCents", t.netCents());
            currencies.put(currency, c);
        });

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currencies", currencies);
        body.put("note", "netCents is gross minus commission, calculated at the moment each payment was confirmed. " +
                "Settlements move sessions from this running balance into a fixed payout record on a schedule -- " +
                "this figure only reflects what hasn't been settled yet, not your all-time earnings.");
        return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
    }

    @GetMapping("/collections-by-method")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> collectionsByMethod(HttpServletRequest req) {
        Merchant merchant = currentMerchant();
        List<CheckoutSessionService.MethodBreakdown> breakdown = checkoutSessionService.collectionsByPaymentMethod(merchant.getId());
        List<Map<String, Object>> body = breakdown.stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("currency", b.currency());
            m.put("paymentMethodCode", b.paymentMethodCode());
            m.put("count", b.count());
            m.put("grossCents", b.grossCents());
            return m;
        }).toList();
        return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
    }

    @GetMapping("/reports/trends")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> trends(
            @RequestParam(required = false, defaultValue = "USD") String currency,
            @RequestParam(required = false, defaultValue = "30") int days,
            HttpServletRequest req) {
        Merchant merchant = currentMerchant();
        int safeDays = Math.min(Math.max(days, 1), 90); // cap at 90 -- a single query scanning a merchant's whole history for a huge range isn't worth allowing yet without pagination
        List<CheckoutSessionService.DailyTrend> trends = checkoutSessionService.dailyTrends(merchant.getId(), currency, safeDays);
        List<Map<String, Object>> body = trends.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", t.date());
            m.put("count", t.count());
            m.put("grossCents", t.grossCents());
            return m;
        }).toList();
        return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
    }

    private Map<String, Object> toMap(CheckoutSession s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", s.getId());
        map.put("reference", s.getReference());
        map.put("amountCents", s.getAmountCents());
        map.put("currency", s.getCurrency());
        map.put("description", s.getDescription());
        map.put("status", s.getStatus());
        map.put("livemode", Boolean.TRUE.equals(s.getLivemode()));
        map.put("paymentMethodCode", s.getPaymentMethodCode());
        map.put("commissionAmountCents", s.getCommissionAmountCents());
        map.put("netAmountCents", s.getNetAmountCents());
        map.put("customerPhone", s.getCustomerPhone());
        map.put("customerEmail", s.getCustomerEmail());
        map.put("checkoutUrl", checkoutUrl(s.getId()));
        map.put("successUrl", s.getSuccessUrl());
        map.put("cancelUrl", s.getCancelUrl());
        map.put("metadata", s.getMetadata());
        map.put("expiresAt", s.getExpiresAt());
        map.put("createdAt", s.getCreatedAt());
        return map;
    }

    private String checkoutUrl(Long sessionId) {
        // Consumer app base URL -- see Section 9 of the design doc: the
        // hosted checkout page lives as new routes in payport-consumer,
        // not a separate app.
        return "https://app.payport.africa/checkout/" + sessionId;
    }

    private Merchant currentMerchant() {
        return zw.co.payport.gateway.config.MerchantAuthResolver.currentMerchant();
    }

    private Long currentApiKeyId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ApiKeyAuthenticationFilter.MerchantPrincipal p) {
            return p.apiKey().getId();
        }
        return null; // dashboard-user-created sessions have no associated API key
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
