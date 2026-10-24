package zw.co.payport.gateway.agents;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.Web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agents, separate from merchants: the agent portal (its own login and dashboard), the public
 * application and its tracking, and the ops console.
 */
@RestController
public class AgentController {

    private final AgentService agents;
    private final AgentAuthService auth;

    public AgentController(AgentService agents, AgentAuthService auth) {
        this.agents = agents;
        this.auth = auth;
    }

    private static AgentJwtAuthenticationFilter.AgentPrincipal me() {
        Object p = SecurityContextHolder.getContext().getAuthentication() == null ? null : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (p instanceof AgentJwtAuthenticationFilter.AgentPrincipal a) return a;
        throw new SecurityException("Agent login required");
    }

    private static String actor() {
        return me().toString();
    }

    // ─── agent portal: public ───────────────────────────────────────────────

    public record LoginBody(String email, String password) {}

    @PostMapping("/api/v1/agent-portal/login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(@RequestBody LoginBody b, HttpServletRequest req) {
        return Web.run(() -> auth.login(b.email(), b.password()), req);
    }

    public record EmailBody(String email) {}

    @PostMapping("/api/v1/agent-portal/forgot-password")
    public ResponseEntity<ApiResponse<Map<String, Object>>> forgot(@RequestBody EmailBody b, HttpServletRequest req) {
        return Web.run(() -> {
            auth.forgotPassword(b.email());
            return Map.<String, Object>of("sent", true);
        }, req);
    }

    public record ResetBody(String token, String newPassword) {}

    @PostMapping("/api/v1/agent-portal/reset-password")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reset(@RequestBody ResetBody b, HttpServletRequest req) {
        return Web.run(() -> {
            auth.resetPassword(b.token(), b.newPassword());
            return Map.<String, Object>of("reset", true);
        }, req);
    }

    public record ApplyBody(String name, String contactPerson, String phone, String email, String location, String idNumber) {}

    @PostMapping("/api/v1/agent-portal/apply")
    public ResponseEntity<ApiResponse<Map<String, Object>>> apply(@RequestBody ApplyBody b, HttpServletRequest req) {
        return Web.run(() -> {
            if (b.email() == null || b.email().isBlank()) throw new IllegalArgumentException("An email address is required");
            return agents.apply(b.name(), b.contactPerson(), b.phone(), b.email(), b.location(), b.idNumber(), "applicant:" + b.email().trim().toLowerCase());
        }, req);
    }

    @GetMapping("/api/v1/agent-portal/track/{reference}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> track(@PathVariable String reference, HttpServletRequest req) {
        return Web.run(() -> agents.track(reference), req);
    }

    // ─── agent portal: signed in ────────────────────────────────────────────

    @GetMapping("/api/v1/agent/me")
    @PreAuthorize("hasRole('AGENT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> profile(HttpServletRequest req) {
        return Web.run(() -> {
            Map<String, Object> out = new LinkedHashMap<>();
            Map<String, Object> u = auth.user(me().userId());
            out.put("agent", agents.view(agents.get(me().agentId())));
            out.put("user", Map.of("id", u.get("id"), "fullName", u.get("full_name"), "email", u.get("email"),
                    "mustChangePassword", AgentAuthService.truthy(u.get("must_change_password"))));
            return out;
        }, req);
    }

    public record ChangePasswordBody(String currentPassword, String newPassword) {}

    @PostMapping("/api/v1/agent/change-password")
    @PreAuthorize("hasRole('AGENT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> changePassword(@RequestBody ChangePasswordBody b, HttpServletRequest req) {
        return Web.run(() -> {
            auth.changePassword(me().userId(), b.currentPassword(), b.newPassword());
            return Map.<String, Object>of("changed", true);
        }, req);
    }

    @GetMapping("/api/v1/agent/dashboard")
    @PreAuthorize("hasRole('AGENT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> agentDashboard(HttpServletRequest req) {
        return Web.run(() -> agents.agentDashboard(me().agentId()), req);
    }

    @GetMapping("/api/v1/agent/customer")
    @PreAuthorize("hasRole('AGENT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> customer(@RequestParam String customer, @RequestParam String currency, HttpServletRequest req) {
        return Web.run(() -> agents.customer(me().agentId(), customer, currency), req);
    }

    public record DepositBody(Long agentId, long amountMinor, String currency, String bankReference) {}

    @PostMapping("/api/v1/agent/float-deposits")
    @PreAuthorize("hasRole('AGENT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> notifyDeposit(@RequestBody DepositBody b, HttpServletRequest req) {
        return Web.run(() -> agents.recordDeposit(me().agentId(), b.amountMinor(), b.currency(), b.bankReference(), actor()), req);
    }

    @GetMapping("/api/v1/agent/float-deposits")
    @PreAuthorize("hasRole('AGENT')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> myDeposits(HttpServletRequest req) {
        return Web.run(() -> agents.deposits(me().agentId(), null), req);
    }

    public record CashInBody(String customer, String currency, long amountMinor, String clientRef) {}

    @PostMapping("/api/v1/agent/cash-in")
    @PreAuthorize("hasRole('AGENT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cashIn(@RequestBody CashInBody b, HttpServletRequest req) {
        return Web.run(() -> agents.cashIn(me().agentId(), b.customer(), b.currency(), b.amountMinor(), b.clientRef(), actor()), req);
    }

    @GetMapping("/api/v1/agent/transactions")
    @PreAuthorize("hasRole('AGENT')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> transactions(HttpServletRequest req) {
        return Web.run(() -> agents.transactions(me().agentId()), req);
    }

    @GetMapping("/api/v1/agent/commissions")
    @PreAuthorize("hasRole('AGENT')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> commissions(HttpServletRequest req) {
        return Web.run(() -> agents.commissions(me().agentId()), req);
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

    public record OnboardBody(String name, String contactPerson, String phone, String email, String location, String idNumber, Integer commissionBps) {}

    @PostMapping("/api/v1/admin/agents")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> onboard(@RequestBody OnboardBody b, HttpServletRequest req) {
        return Web.run(() -> {
            if (b.email() == null || b.email().isBlank()) throw new IllegalArgumentException("An email address is needed for the agent's portal login");
            auth.requireEmailFree(b.email());
            Map<String, Object> a = agents.onboard(b.name(), b.contactPerson(), b.phone(), b.email(), b.location(), b.idNumber(), b.commissionBps(), Web.staff());
            Map<String, Object> out = new LinkedHashMap<>(a);
            out.put("login", auth.createLogin(((Number) a.get("id")).longValue(), b.contactPerson() == null || b.contactPerson().isBlank() ? b.name() : b.contactPerson(),
                    b.email(), (String) a.get("agent_code"), Web.staff()));
            return out;
        }, req);
    }

    @GetMapping("/api/v1/admin/agents/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> detail(@PathVariable long id, HttpServletRequest req) {
        return Web.run(() -> {
            Map<String, Object> out = new LinkedHashMap<>(agents.detail(id));
            out.put("users", auth.users(id));
            return out;
        }, req);
    }

    @PutMapping("/api/v1/admin/agents/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(@PathVariable long id, @RequestBody OnboardBody b, HttpServletRequest req) {
        return Web.run(() -> agents.update(id, b.name(), b.contactPerson(), b.phone(), b.email(), b.location(), b.idNumber(), b.commissionBps(), Web.staff()), req);
    }

    public record DecideBody(boolean approve, String reason, Integer commissionBps) {}

    @PostMapping("/api/v1/admin/agents/{id}/decide")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> decide(@PathVariable long id, @RequestBody DecideBody b, HttpServletRequest req) {
        return Web.run(() -> {
            Map<String, Object> a = new LinkedHashMap<>(agents.decide(id, b.approve(), b.reason(), b.commissionBps(), Web.staff()));
            if (b.approve() && a.get("email") != null && auth.users(id).isEmpty()) {
                a.put("login", auth.createLogin(id, a.get("contact_person") == null ? (String) a.get("name") : (String) a.get("contact_person"),
                        (String) a.get("email"), (String) a.get("agent_code"), Web.staff()));
            }
            return a;
        }, req);
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

    public record LoginCreateBody(String fullName, String email) {}

    @PostMapping("/api/v1/admin/agents/{id}/users")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createLogin(@PathVariable long id, @RequestBody LoginCreateBody b, HttpServletRequest req) {
        return Web.run(() -> {
            Map<String, Object> a = agents.get(id);
            return auth.createLogin(id, b.fullName(), b.email(), (String) a.get("agent_code"), Web.staff());
        }, req);
    }

    @PostMapping("/api/v1/admin/agents/{id}/users/{userId}/reset-password")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resetLogin(@PathVariable long id, @PathVariable long userId, HttpServletRequest req) {
        return Web.run(() -> auth.adminReset(id, userId, Web.staff()), req);
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
