package zw.co.payport.gateway.psp.wallet;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.psp.agent.Agent;
import zw.co.payport.gateway.psp.agent.AgentRepository;
import zw.co.payport.gateway.psp.agent.AgentService;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.Api;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.fx.FxRate;
import zw.co.payport.gateway.psp.fx.FxRateService;
import zw.co.payport.gateway.psp.kyc.CustomerProfile;
import zw.co.payport.gateway.psp.kyc.KycService;
import zw.co.payport.gateway.psp.platform.approval.ApprovalRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Back-office for wallet customers (KYC), agents and FX rates. */
@RestController
@RequestMapping("/api/v1/psp/admin")
@PreAuthorize("hasAnyRole('OPS','ADMIN','FINANCE','COMPLIANCE')")
public class WalletAdminController {

    public record IdBody(String nationalId) {}
    public record NoteBody(String note) {}
    public record ProofBody(String proofOfResidenceRef) {}
    public record PinBody(String pin) {}
    public record AgentLimitsBody(long perTxnMinor, long dailyCashInMinor, long dailyCashOutMinor, long maxFloatMinor, String reason) {}
    public record FloatBody(String currency, long amountMinor, String bankDepositRef, String trustCashAccount) {}
    public record RateBody(BigDecimal bid, BigDecimal ask, LocalDateTime asOf, String source) {}

    private final KycService kyc;
    private final WalletService wallets;
    private final AgentService agentService;
    private final AgentRepository agents;
    private final FxRateService fx;
    private final WalletTariffRepository tariffs;

    public WalletAdminController(KycService kyc, WalletService wallets, AgentService agentService, AgentRepository agents,
                                 FxRateService fx, WalletTariffRepository tariffs) {
        this.kyc = kyc;
        this.wallets = wallets;
        this.agentService = agentService;
        this.agents = agents;
        this.fx = fx;
        this.tariffs = tariffs;
    }

    // ── Customers ────────────────────────────────────────────────────────

    @GetMapping("/customers/by-msisdn/{msisdn}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> customer(@PathVariable String msisdn, HttpServletRequest req) {
        CustomerProfile c = kyc.byMsisdn(msisdn).orElseThrow(() -> PspException.notFound("Customer", msisdn));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("customerRef", c.getCustomerRef());
        m.put("name", c.getFirstName() + " " + c.getLastName());
        m.put("msisdn", c.getMsisdn());
        m.put("tier", c.getKycTier());
        m.put("status", c.getStatus());
        m.put("statusReason", c.getStatusReason());
        m.put("sanctions", c.getSanctionsStatus());
        m.put("risk", c.getRiskRating());
        m.put("idVerification", c.getIdVerificationResult());
        m.put("nationalId", c.getNationalId() == null ? null : zw.co.payport.gateway.psp.platform.crypto.FieldCrypto.mask(c.getNationalId(), 3));
        m.put("balanceUSD", wallets.balance(c.getCustomerRef(), "USD"));
        m.put("balanceZWG", wallets.balance(c.getCustomerRef(), "ZWG"));
        m.put("recent", wallets.history(c.getCustomerRef(), 20));
        return Api.ok(m, req);
    }

    @PostMapping("/customers/{ref}/national-id")
    public ResponseEntity<ApiResponse<CustomerProfile>> verifyId(@PathVariable String ref, @RequestBody IdBody b, HttpServletRequest req) {
        return Api.ok(kyc.verifyNationalId(ref, b.nationalId(), Actor.current()), req);
    }

    @PostMapping("/customers/{ref}/id-confirmed")
    @PreAuthorize("hasAnyRole('COMPLIANCE','ADMIN')")
    public ResponseEntity<ApiResponse<CustomerProfile>> confirmId(@PathVariable String ref, @RequestBody NoteBody b, HttpServletRequest req) {
        return Api.ok(kyc.confirmIdManually(ref, b.note(), Actor.current()), req);
    }

    @PostMapping("/customers/{ref}/tier3-requests")
    public ResponseEntity<ApiResponse<ApprovalRequest>> tier3(@PathVariable String ref, @RequestBody ProofBody b, HttpServletRequest req) {
        return Api.created(kyc.requestTier3(ref, b.proofOfResidenceRef(), Actor.current()), req);
    }

    @PostMapping("/customers/{ref}/freeze")
    public ResponseEntity<ApiResponse<CustomerProfile>> freeze(@PathVariable String ref, @RequestBody NoteBody b, HttpServletRequest req) {
        return Api.ok(kyc.freeze(ref, b.note(), Actor.current()), req);
    }

    @PostMapping("/customers/{ref}/unfreeze-requests")
    public ResponseEntity<ApiResponse<ApprovalRequest>> unfreeze(@PathVariable String ref, @RequestBody NoteBody b, HttpServletRequest req) {
        return Api.created(kyc.requestUnfreeze(ref, b.note(), Actor.current()), req);
    }

    @GetMapping("/wallet-tariffs")
    public ResponseEntity<ApiResponse<List<WalletTariff>>> tariffs(HttpServletRequest req) {
        return Api.ok(tariffs.findAllByOrderByOperationAscCurrencyAscBandMinMinorAsc(), req);
    }

    // ── Agents ───────────────────────────────────────────────────────────

    @PostMapping("/agents")
    @PreAuthorize("hasAnyRole('OPS','ADMIN','COMPLIANCE')")
    public ResponseEntity<ApiResponse<ApprovalRequest>> onboard(@RequestBody AgentService.OnboardRequest b, HttpServletRequest req) {
        return Api.created(agentService.onboard(b, Actor.current()), req);
    }

    @GetMapping("/agents")
    public ResponseEntity<ApiResponse<List<Agent>>> agentList(@RequestParam(defaultValue = "ACTIVE") String status, HttpServletRequest req) {
        return Api.ok(agents.findByStatus(status.toUpperCase()), req);
    }

    @GetMapping("/agents/{ref}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> agent(@PathVariable String ref, HttpServletRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        Agent a = agentService.byRef(ref);
        m.put("agent", a);
        m.put("balances", agentService.balances(ref));
        m.put("subAgents", agents.findByParentAgentId(a.getId()));
        return Api.ok(m, req);
    }

    @PostMapping("/agents/{ref}/pin")
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    public ResponseEntity<ApiResponse<String>> setPin(@PathVariable String ref, @RequestBody PinBody b, HttpServletRequest req) {
        agentService.setPin(ref, b.pin(), Actor.current());
        return Api.ok("PIN set", req);
    }

    @PostMapping("/agents/{ref}/suspend")
    public ResponseEntity<ApiResponse<Agent>> suspend(@PathVariable String ref, @RequestBody NoteBody b, HttpServletRequest req) {
        return Api.ok(agentService.suspend(ref, b.note(), Actor.current()), req);
    }

    @PostMapping("/agents/{ref}/limit-requests")
    public ResponseEntity<ApiResponse<ApprovalRequest>> limits(@PathVariable String ref, @RequestBody AgentLimitsBody b, HttpServletRequest req) {
        return Api.created(agentService.requestLimits(ref, b.perTxnMinor(), b.dailyCashInMinor(), b.dailyCashOutMinor(), b.maxFloatMinor(),
                b.reason(), Actor.current()), req);
    }

    @PostMapping("/agents/{ref}/float-purchase-requests")
    @PreAuthorize("hasAnyRole('OPS','FINANCE','ADMIN')")
    public ResponseEntity<ApiResponse<ApprovalRequest>> floatIn(@PathVariable String ref, @RequestBody FloatBody b, HttpServletRequest req) {
        return Api.created(agentService.requestFloatPurchase(ref, b.currency(), b.amountMinor(), b.bankDepositRef(), b.trustCashAccount(), Actor.current()), req);
    }

    @PostMapping("/agents/{ref}/float-redemption-requests")
    @PreAuthorize("hasAnyRole('OPS','FINANCE','ADMIN')")
    public ResponseEntity<ApiResponse<ApprovalRequest>> floatOut(@PathVariable String ref, @RequestBody FloatBody b, HttpServletRequest req) {
        return Api.created(agentService.requestFloatRedemption(ref, b.currency(), b.amountMinor(), b.trustCashAccount(), Actor.current()), req);
    }

    @PostMapping("/agents/commissions/pay")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN')")
    public ResponseEntity<ApiResponse<Integer>> payCommissions(@RequestParam(required = false) String period, HttpServletRequest req) {
        String p = period != null ? period : YearMonth.now().minusMonths(1).toString();
        return Api.ok(agentService.payCommissions(p, Actor.current()), req);
    }

    // ── FX ───────────────────────────────────────────────────────────────

    @GetMapping("/fx/rates")
    public ResponseEntity<ApiResponse<List<FxRate>>> rates(HttpServletRequest req) {
        return Api.ok(fx.history(), req);
    }

    @PostMapping("/fx/rates")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN')")
    public ResponseEntity<ApiResponse<ApprovalRequest>> proposeRate(@RequestBody RateBody b, HttpServletRequest req) {
        return Api.created(fx.proposeRate(b.bid(), b.ask(), b.asOf(), b.source(), Actor.current()), req);
    }
}
