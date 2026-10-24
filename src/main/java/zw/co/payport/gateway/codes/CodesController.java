package zw.co.payport.gateway.codes;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.agents.AgentJwtAuthenticationFilter;
import zw.co.payport.gateway.config.MerchantAuthResolver;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.PartyCodes;
import zw.co.payport.gateway.platform.Web;
import zw.co.payport.gateway.risk.ComplianceAudit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Agent and merchant codes: several per party (branches), each with a static QR; applications; reports per code. */
@RestController
public class CodesController {

    private final PartyCodes codes;
    private final CodeReports reports;
    private final ComplianceAudit audit;

    public CodesController(PartyCodes codes, CodeReports reports, ComplianceAudit audit) {
        this.codes = codes;
        this.reports = reports;
        this.audit = audit;
    }

    public record RequestBody(String label, String address) {}
    public record DecideBody(boolean approve, String note) {}
    public record StatusBody(String status) {}

    /** The party's codes, each with its current QR (issued the first time it is looked at), and its applications. */
    Map<String, Object> mine(String type, long id) {
        for (Map<String, Object> c : codes.codesOf(type, id)) {
            if (c.get("qrPayload") == null) codes.qr(type, id, (String) c.get("code"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("codes", codes.codesOf(type, id));
        out.put("requests", codes.requestsOf(type, id));
        return out;
    }

    // ─── merchant portal ─────────────────────────────────────────────────────

    private static long merchantId() {
        return MerchantAuthResolver.currentMerchant().getId();
    }

    private static String merchantActor() {
        var u = MerchantAuthResolver.currentMerchantUserOrNull();
        return u != null ? "merchant-user:" + u.getEmail() : "merchant:" + merchantId();
    }

    @GetMapping("/api/v1/merchant/codes")
    @PreAuthorize("hasAnyRole('MERCHANT','MERCHANT_PENDING')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> merchantCodes(HttpServletRequest req) {
        return Web.run(() -> mine(PartyCodes.MERCHANT, merchantId()), req);
    }

    @PostMapping("/api/v1/merchant/codes/requests")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> merchantRequest(@org.springframework.web.bind.annotation.RequestBody RequestBody b, HttpServletRequest req) {
        return Web.run(() -> codes.request(PartyCodes.MERCHANT, merchantId(), b.label(), b.address(), merchantActor()), req);
    }

    @PostMapping("/api/v1/merchant/codes/{code}/qr")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> merchantNewQr(@PathVariable String code, HttpServletRequest req) {
        return Web.run(() -> {
            codes.newQr(PartyCodes.MERCHANT, merchantId(), code);
            audit.record(merchantActor(), "QR_REISSUED", "code:" + code, "");
            return mine(PartyCodes.MERCHANT, merchantId());
        }, req);
    }

    @GetMapping("/api/v1/merchant/codes/report")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> merchantReport(@RequestParam(required = false) String code, @RequestParam(required = false) String from,
                                                                           @RequestParam(required = false) String to, HttpServletRequest req) {
        return Web.run(() -> reports.report(PartyCodes.MERCHANT, merchantId(), code, from, to), req);
    }

    // ─── agent portal ────────────────────────────────────────────────────────

    private static AgentJwtAuthenticationFilter.AgentPrincipal agent() {
        return (AgentJwtAuthenticationFilter.AgentPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    @GetMapping("/api/v1/agent/codes")
    @PreAuthorize("hasRole('AGENT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> agentCodes(HttpServletRequest req) {
        return Web.run(() -> mine(PartyCodes.AGENT, agent().agentId()), req);
    }

    @PostMapping("/api/v1/agent/codes/requests")
    @PreAuthorize("hasRole('AGENT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> agentRequest(@org.springframework.web.bind.annotation.RequestBody RequestBody b, HttpServletRequest req) {
        return Web.run(() -> codes.request(PartyCodes.AGENT, agent().agentId(), b.label(), b.address(), agent().toString()), req);
    }

    @PostMapping("/api/v1/agent/codes/{code}/qr")
    @PreAuthorize("hasRole('AGENT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> agentNewQr(@PathVariable String code, HttpServletRequest req) {
        return Web.run(() -> {
            codes.newQr(PartyCodes.AGENT, agent().agentId(), code);
            audit.record(agent().toString(), "QR_REISSUED", "code:" + code, "");
            return mine(PartyCodes.AGENT, agent().agentId());
        }, req);
    }

    @GetMapping("/api/v1/agent/codes/report")
    @PreAuthorize("hasRole('AGENT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> agentReport(@RequestParam(required = false) String code, @RequestParam(required = false) String from,
                                                                        @RequestParam(required = false) String to, HttpServletRequest req) {
        return Web.run(() -> reports.report(PartyCodes.AGENT, agent().agentId(), code, from, to), req);
    }

    // ─── admin portal ────────────────────────────────────────────────────────

    @GetMapping("/api/v1/admin/codes")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> search(@RequestParam(required = false) String type, @RequestParam(required = false) String q,
                                                                         HttpServletRequest req) {
        return Web.run(() -> codes.search(type, q), req);
    }

    @GetMapping("/api/v1/admin/codes/{type}/{partyId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> ofParty(@PathVariable String type, @PathVariable long partyId, HttpServletRequest req) {
        return Web.run(() -> mine(type.toUpperCase(), partyId), req);
    }

    @PostMapping("/api/v1/admin/codes/{type}/{partyId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> add(@PathVariable String type, @PathVariable long partyId,
                                                                @org.springframework.web.bind.annotation.RequestBody RequestBody b, HttpServletRequest req) {
        return Web.run(() -> {
            String code = codes.addCode(type, partyId, b.label(), b.address());
            audit.record(Web.staff(), "CODE_ADDED", type.toUpperCase() + ":" + partyId, code + " " + b.label());
            return mine(type.toUpperCase(), partyId);
        }, req);
    }

    @GetMapping("/api/v1/admin/codes/requests")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','COMPLIANCE')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> requests(@RequestParam(required = false) String status, HttpServletRequest req) {
        return Web.run(() -> codes.requests(status), req);
    }

    @PostMapping("/api/v1/admin/codes/requests/{id}/decide")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> decide(@PathVariable long id, @org.springframework.web.bind.annotation.RequestBody DecideBody b, HttpServletRequest req) {
        return Web.run(() -> {
            Map<String, Object> r = codes.decide(id, b.approve(), b.note(), Web.staff());
            audit.record(Web.staff(), b.approve() ? "CODE_REQUEST_APPROVED" : "CODE_REQUEST_REJECTED", "code-request:" + id, String.valueOf(r.get("code")));
            return r;
        }, req);
    }

    @PostMapping("/api/v1/admin/codes/{code}/status")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(@PathVariable String code, @org.springframework.web.bind.annotation.RequestBody StatusBody b, HttpServletRequest req) {
        return Web.run(() -> {
            Map<String, Object> r = codes.setStatus(code, b.status());
            audit.record(Web.staff(), "CODE_" + r.get("status"), "code:" + code, "");
            return r;
        }, req);
    }

    @GetMapping("/api/v1/admin/codes/report")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> report(@RequestParam String type, @RequestParam(required = false) Long partyId,
                                                                   @RequestParam(required = false) String code, @RequestParam(required = false) String from,
                                                                   @RequestParam(required = false) String to, HttpServletRequest req) {
        return Web.run(() -> {
            long party = partyId != null ? partyId : reports.partyOf(type, code == null ? "" : code.trim());
            return reports.report(type, party, code, from, to);
        }, req);
    }
}
