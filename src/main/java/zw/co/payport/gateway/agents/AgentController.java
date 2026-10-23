package zw.co.payport.gateway.agents;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.config.MerchantAuthResolver;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.entity.MerchantUser;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.Web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Agents: the agent's own screens (merchant dashboard, dashboard-only) and the ops console. */
@RestController
public class AgentController {

    private final AgentService agents;

    public AgentController(AgentService agents) {
        this.agents = agents;
    }

    private static Merchant merchant() {
        return MerchantAuthResolver.currentMerchant();
    }

    private static String merchantActor() {
        MerchantUser u = MerchantAuthResolver.currentMerchantUserOrNull();
        return "merchant:" + merchant().getId() + (u == null ? "" : ":user:" + u.getId());
    }

    // ─── agent (merchant dashboard) ─────────────────────────────────────────

    @GetMapping("/api/v1/merchant/agent")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(HttpServletRequest req) {
        return Web.run(() -> {
            Map<String, Object> out = new LinkedHashMap<>();
            agents.forMerchant(merchant().getId()).ifPresent(a -> out.put("agent", agents.view(a)));
            out.put("isAgent", out.containsKey("agent"));
            return out;
        }, req);
    }

    public record ApplyBody(String name, String phone, String location) {}

    @PostMapping("/api/v1/merchant/agent/apply")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> apply(@RequestBody ApplyBody b, HttpServletRequest req) {
        return Web.run(() -> agents.apply(merchant().getId(), b.name(), b.phone(), b.location(), merchantActor()), req);
    }

    public record DepositBody(Long agentId, long amountMinor, String currency, String bankReference) {}

    @PostMapping("/api/v1/merchant/agent/float-deposits")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> notifyDeposit(@RequestBody DepositBody b, HttpServletRequest req) {
        return Web.run(() -> agents.recordDeposit(agentId(), b.amountMinor(), b.currency(), b.bankReference(), merchantActor()), req);
    }

    @GetMapping("/api/v1/merchant/agent/float-deposits")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> myDeposits(HttpServletRequest req) {
        return Web.run(() -> agents.deposits(agentId(), null), req);
    }

    public record CashInBody(String customer, String currency, long amountMinor, String clientRef) {}

    @PostMapping("/api/v1/merchant/agent/cash-in")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cashIn(@RequestBody CashInBody b, HttpServletRequest req) {
        return Web.run(() -> agents.cashIn(merchant().getId(), b.customer(), b.currency(), b.amountMinor(), b.clientRef(), merchantActor()), req);
    }

    public record CashOutBody(String customerPhone, String code) {}

    @PostMapping("/api/v1/merchant/agent/cash-out")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cashOut(@RequestBody CashOutBody b, HttpServletRequest req) {
        return Web.run(() -> agents.cashOut(merchant().getId(), b.customerPhone(), b.code(), merchantActor()), req);
    }

    @GetMapping("/api/v1/merchant/agent/transactions")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> transactions(HttpServletRequest req) {
        return Web.run(() -> agents.transactions(agentId()), req);
    }

    @GetMapping("/api/v1/merchant/agent/commissions")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> commissions(HttpServletRequest req) {
        return Web.run(() -> agents.commissions(agentId()), req);
    }

    private long agentId() {
        return ((Number) agents.forMerchant(merchant().getId()).orElseThrow(() -> new IllegalArgumentException("This merchant is not a PayPort agent")).get("id")).longValue();
    }

    // ─── ops console ─────────────────────────────────────────────────────────

    @GetMapping("/api/v1/admin/agents")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest req) {
        return Web.run(agents::list, req);
    }

    @GetMapping("/api/v1/admin/agents/dashboard")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> dashboard(HttpServletRequest req) {
        return Web.run(agents::dashboard, req);
    }

    @GetMapping("/api/v1/admin/agents/onboardable-merchants")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','COMPLIANCE')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> onboardable(HttpServletRequest req) {
        return Web.run(agents::onboardableMerchants, req);
    }

    public record OnboardBody(Long merchantId, String name, String phone, String location, Integer commissionBps) {}

    @PostMapping("/api/v1/admin/agents")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> onboard(@RequestBody OnboardBody b, HttpServletRequest req) {
        return Web.run(() -> {
            if (b.merchantId() == null) throw new IllegalArgumentException("Choose the merchant");
            return agents.onboard(b.merchantId(), b.name(), b.phone(), b.location(), b.commissionBps(), Web.staff());
        }, req);
    }

    @GetMapping("/api/v1/admin/agents/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> detail(@PathVariable long id, HttpServletRequest req) {
        return Web.run(() -> agents.detail(id), req);
    }

    @PutMapping("/api/v1/admin/agents/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(@PathVariable long id, @RequestBody OnboardBody b, HttpServletRequest req) {
        return Web.run(() -> agents.update(id, b.name(), b.phone(), b.location(), b.commissionBps(), Web.staff()), req);
    }

    public record DecideBody(boolean approve, String reason, Integer commissionBps) {}

    @PostMapping("/api/v1/admin/agents/{id}/decide")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> decide(@PathVariable long id, @RequestBody DecideBody b, HttpServletRequest req) {
        return Web.run(() -> agents.decide(id, b.approve(), b.reason(), b.commissionBps(), Web.staff()), req);
    }

    public record StatusBody(String status, String reason) {}

    @PostMapping("/api/v1/admin/agents/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(@PathVariable long id, @RequestBody StatusBody b, HttpServletRequest req) {
        return Web.run(() -> {
            if ("ACTIVE".equalsIgnoreCase(b.status()) && !Web.roles().contains("ADMIN") && !Web.roles().contains("COMPLIANCE")) {
                throw new SecurityException("Only compliance or an administrator can reinstate an agent");
            }
            return agents.setStatus(id, b.status(), b.reason(), Web.staff());
        }, req);
    }

    @GetMapping("/api/v1/admin/agents/float-deposits")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> deposits(@RequestParam(required = false) String status, HttpServletRequest req) {
        return Web.run(() -> agents.deposits(null, status), req);
    }

    @PostMapping("/api/v1/admin/agents/float-deposits")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> recordDeposit(@RequestBody DepositBody b, HttpServletRequest req) {
        return Web.run(() -> {
            if (b.agentId() == null) throw new IllegalArgumentException("agentId is required");
            return agents.recordDeposit(b.agentId(), b.amountMinor(), b.currency(), b.bankReference(), Web.staff());
        }, req);
    }

    public record DepositDecision(boolean approve, String note) {}

    @PostMapping("/api/v1/admin/agents/float-deposits/{id}/decide")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> decideDeposit(@PathVariable long id, @RequestBody DepositDecision b, HttpServletRequest req) {
        return Web.run(() -> agents.decideDeposit(id, b.approve(), b.note(), Web.staff()), req);
    }
}
