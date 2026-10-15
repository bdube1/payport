package zw.co.payport.gateway.psp.merchant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.Api;
import zw.co.payport.gateway.psp.cutover.LedgerCutoverService;
import zw.co.payport.gateway.psp.fees.FeeAgreement;
import zw.co.payport.gateway.psp.fees.FeeService;
import zw.co.payport.gateway.psp.fees.TaxRate;
import zw.co.payport.gateway.psp.fees.TaxRemittanceHandler;
import zw.co.payport.gateway.psp.ledger.domain.FeeQuote;
import zw.co.payport.gateway.psp.platform.approval.ApprovalRequest;
import zw.co.payport.gateway.psp.platform.approval.ApprovalService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Merchant accounts, fees and tax administration for ops, compliance and finance. */
@RestController
@RequestMapping("/api/v1/psp/admin")
@PreAuthorize("hasAnyRole('OPS','ADMIN','FINANCE','COMPLIANCE')")
public class MerchantAdminController {

    public record SuspendBody(String scope, String reason) {}
    public record ReasonBody(String reason) {}
    public record VerificationBody(String status, String reason) {}
    public record RiskBody(String riskClass, String reason) {}
    public record FundsPolicyBody(String riskClass, int holdDays, int reserveBps, int reserveDays, String reason) {}
    public record ProductBody(String paymentMethodCode, String currency, boolean enable, String reason) {}
    public record LimitBody(String currency, String limitType, long value, String reason) {}
    public record TaxRemittanceBody(String taxCode, String currency, long amountMinor, String cashAccount, String zimraRef) {}

    private final MerchantControlService merchants;
    private final FeeService fees;
    private final LedgerCutoverService cutover;
    private final ApprovalService approvals;
    private final com.fasterxml.jackson.databind.ObjectMapper om;

    public MerchantAdminController(MerchantControlService merchants, FeeService fees, LedgerCutoverService cutover,
                                   ApprovalService approvals, com.fasterxml.jackson.databind.ObjectMapper om) {
        this.merchants = merchants;
        this.fees = fees;
        this.cutover = cutover;
        this.approvals = approvals;
        this.om = om;
    }

    @GetMapping("/merchants/{id}/controls")
    public ResponseEntity<ApiResponse<Map<String, Object>>> controls(@PathVariable long id, HttpServletRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("profile", merchants.profile(id));
        m.put("products", merchants.products(id));
        m.put("limits", merchants.limits(id));
        m.put("payoutAccounts", merchants.payoutAccounts(id).stream().map(MerchantControlService::view).collect(Collectors.toList()));
        m.put("feeAgreements", fees.agreementsFor(id));
        return Api.ok(m, req);
    }

    @PostMapping("/merchants/{id}/suspend")
    public ResponseEntity<ApiResponse<MerchantProfile>> suspend(@PathVariable long id, @RequestBody SuspendBody b, HttpServletRequest req) {
        return Api.ok(merchants.suspend(id, b.scope(), b.reason(), Actor.current()), req);
    }

    @PostMapping("/merchants/{id}/reinstatement-requests")
    public ResponseEntity<ApiResponse<ApprovalRequest>> reinstate(@PathVariable long id, @RequestBody ReasonBody b, HttpServletRequest req) {
        return Api.created(merchants.requestReinstatement(id, b.reason(), Actor.current()), req);
    }

    @PostMapping("/merchants/{id}/verification-requests")
    @PreAuthorize("hasAnyRole('COMPLIANCE','ADMIN','OPS')")
    public ResponseEntity<ApiResponse<ApprovalRequest>> verify(@PathVariable long id, @RequestBody VerificationBody b, HttpServletRequest req) {
        return Api.created(merchants.requestVerification(id, b.status(), b.reason(), Actor.current()), req);
    }

    @PostMapping("/merchants/{id}/risk-class")
    @PreAuthorize("hasAnyRole('COMPLIANCE','ADMIN')")
    public ResponseEntity<ApiResponse<MerchantProfile>> raiseRisk(@PathVariable long id, @RequestBody RiskBody b, HttpServletRequest req) {
        return Api.ok(merchants.setRiskClass(id, b.riskClass(), b.reason(), Actor.current()), req);
    }

    @PostMapping("/merchants/{id}/funds-policy-requests")
    @PreAuthorize("hasAnyRole('COMPLIANCE','ADMIN','FINANCE')")
    public ResponseEntity<ApiResponse<ApprovalRequest>> fundsPolicy(@PathVariable long id, @RequestBody FundsPolicyBody b, HttpServletRequest req) {
        return Api.created(merchants.requestFundsPolicy(id, b.riskClass(), b.holdDays(), b.reserveBps(), b.reserveDays(), b.reason(), Actor.current()), req);
    }

    @PostMapping("/merchants/{id}/product-requests")
    public ResponseEntity<ApiResponse<ApprovalRequest>> product(@PathVariable long id, @RequestBody ProductBody b, HttpServletRequest req) {
        return Api.created(merchants.requestProduct(id, b.paymentMethodCode(), b.currency(), b.enable(), b.reason(), Actor.current()), req);
    }

    @PostMapping("/merchants/{id}/products/disable")
    public ResponseEntity<ApiResponse<String>> disableProduct(@PathVariable long id, @RequestBody ProductBody b, HttpServletRequest req) {
        merchants.disableProduct(id, b.paymentMethodCode(), b.currency(), b.reason(), Actor.current());
        return Api.ok("disabled", req);
    }

    @PostMapping("/merchants/{id}/limit-requests")
    @PreAuthorize("hasAnyRole('COMPLIANCE','ADMIN','OPS')")
    public ResponseEntity<ApiResponse<ApprovalRequest>> limit(@PathVariable long id, @RequestBody LimitBody b, HttpServletRequest req) {
        return Api.created(merchants.requestLimit(id, b.currency(), b.limitType(), b.value(), b.reason(), Actor.current()), req);
    }

    @PostMapping("/merchants/{id}/payout-accounts")
    @PreAuthorize("hasAnyRole('OPS','ADMIN','FINANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addPayoutAccount(@PathVariable long id,
                                                                            @RequestBody MerchantControlService.PayoutAccountRequest b,
                                                                            HttpServletRequest req) {
        return Api.created(MerchantControlService.view(merchants.addPayoutAccount(id, b, Actor.current())), req);
    }

    // ── Fees & tax ───────────────────────────────────────────────────────

    @GetMapping("/fees/agreements")
    public ResponseEntity<ApiResponse<List<FeeAgreement>>> agreements(@RequestParam(required = false) Long merchantId, HttpServletRequest req) {
        return Api.ok(fees.agreementsFor(merchantId), req);
    }

    @PostMapping("/fees/agreements")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN','OPS')")
    public ResponseEntity<ApiResponse<ApprovalRequest>> proposeAgreement(@RequestBody FeeService.AgreementRequest b, HttpServletRequest req) {
        return Api.created(fees.proposeAgreement(b, Actor.current()), req);
    }

    @GetMapping("/fees/quote")
    public ResponseEntity<ApiResponse<FeeQuote>> quote(@RequestParam long merchantId, @RequestParam String method,
                                                      @RequestParam String currency, @RequestParam long amountMinor, HttpServletRequest req) {
        return Api.ok(fees.quote(merchantId, method, currency, amountMinor), req);
    }

    @GetMapping("/tax/rates")
    public ResponseEntity<ApiResponse<List<TaxRate>>> taxRates(HttpServletRequest req) {
        return Api.ok(fees.taxRates(), req);
    }

    @PostMapping("/tax/rates")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN')")
    public ResponseEntity<ApiResponse<ApprovalRequest>> proposeTax(@RequestBody FeeService.TaxRateRequest b, HttpServletRequest req) {
        return Api.created(fees.proposeTaxRate(b, Actor.current()), req);
    }

    @PostMapping("/tax/remittance-requests")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN')")
    public ResponseEntity<ApiResponse<ApprovalRequest>> remit(@RequestBody TaxRemittanceBody b, HttpServletRequest req) throws Exception {
        return Api.created(approvals.request(TaxRemittanceHandler.ACTION, b.taxCode() + ":" + b.zimraRef(), om.writeValueAsString(b),
                "Record " + b.taxCode() + " " + b.currency() + " " + b.amountMinor() + " paid to ZIMRA (" + b.zimraRef() + ") from " + b.cashAccount(),
                null, Actor.current()), req);
    }

    // ── Cutover ──────────────────────────────────────────────────────────

    @GetMapping("/merchants/{id}/cutover")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN')")
    public ResponseEntity<ApiResponse<List<LedgerCutoverService.CurrencyPreview>>> cutoverPreview(@PathVariable long id, HttpServletRequest req) {
        return Api.ok(cutover.preview(id), req);
    }

    @PostMapping("/merchants/{id}/cutover-requests")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN')")
    public ResponseEntity<ApiResponse<ApprovalRequest>> cutover(@PathVariable long id, HttpServletRequest req) {
        return Api.created(cutover.request(id, Actor.current()), req);
    }
}
