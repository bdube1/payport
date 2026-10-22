package zw.co.payport.gateway.integration;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.service.AdaptorRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Operations view of provider integrations: register (capabilities, configured environment,
 * verification and evidence), live health (circuit breaker, latency), and the kill switch.
 */
@RestController
@RequestMapping("/api/v1/admin/integrations")
@PreAuthorize("hasAnyRole('OPS','ADMIN')")
public class IntegrationAdminController {

    private final IntegrationRegister register;
    private final ProviderHealthService health;
    private final AdaptorRegistry adaptors;

    public IntegrationAdminController(IntegrationRegister register, ProviderHealthService health, AdaptorRegistry adaptors) {
        this.register = register;
        this.health = health;
        this.adaptors = adaptors;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest req) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (IntegrationRegister.Provider p : register.all().values()) out.add(view(p, false));
        return ok(out, req);
    }

    @GetMapping("/{code}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable String code, HttpServletRequest req) {
        IntegrationRegister.Provider p = register.get(code);
        if (p == null) return ResponseEntity.status(404).body(ApiResponse.error("NOT_FOUND", "Unknown provider " + code, rid(req)));
        return ok(view(p, true), req);
    }

    public record SuspendBody(String reason) {}

    /** Kill switch: stops new payments/deliveries to the provider on every instance, before anything is sent. */
    @PostMapping("/{code}/suspend")
    public ResponseEntity<ApiResponse<Map<String, Object>>> suspend(@PathVariable String code, @RequestBody SuspendBody b, HttpServletRequest req) {
        if (register.get(code) == null) return ResponseEntity.status(404).body(ApiResponse.error("NOT_FOUND", "Unknown provider " + code, rid(req)));
        try {
            health.suspend(code, b == null ? null : b.reason(), staff());
            return ok(health.health(code), req);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("SUSPEND_FAILED", e.getMessage(), rid(req)));
        }
    }

    public record ResumeBody(String note) {}

    @PostMapping("/{code}/resume")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resume(@PathVariable String code, @RequestBody(required = false) ResumeBody b, HttpServletRequest req) {
        try {
            health.resume(code, b == null ? null : b.note(), staff());
            return ok(health.health(code), req);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(ApiResponse.error("RESUME_FAILED", e.getMessage(), rid(req)));
        }
    }

    public record VerificationBody(String environment, String flow, boolean pass, String evidence) {}

    /** Records a verification run (sandbox/UAT/production smoke test) as evidence for the register. */
    @PostMapping("/{code}/verifications")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> verify(@PathVariable String code, @RequestBody VerificationBody b, HttpServletRequest req) {
        try {
            if (b.evidence() == null || b.evidence().isBlank()) throw new IllegalArgumentException("evidence is required (ticket, log excerpt or reference)");
            if (!List.of("SANDBOX", "UAT", "PRODUCTION").contains(String.valueOf(b.environment()).toUpperCase())) {
                throw new IllegalArgumentException("environment must be SANDBOX, UAT or PRODUCTION");
            }
            register.recordVerification(code, b.environment(), b.flow(), b.pass(), b.evidence(), staff());
            return ok(register.verifications(code), req);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("VERIFICATION_REJECTED", e.getMessage(), rid(req)));
        }
    }

    private Map<String, Object> view(IntegrationRegister.Provider p, boolean detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", p.code());
        m.put("name", p.name());
        m.put("kind", p.kind());
        m.put("currencies", p.currencies());
        m.put("operations", p.operations());
        m.put("callbackAuth", p.callbackAuth());
        m.put("verification", p.verification());
        m.put("configuredEnvironment", register.configuredEnvironment(p));
        m.put("endpointHost", register.endpointHost(p));
        m.put("adaptorLoaded", adaptors.hasPaymentAdaptor(p.code()) || adaptors.hasFulfilmentAdaptor(p.code()));
        m.put("notes", p.notes());
        m.put("health", health.health(p.code()));
        if (detail) {
            m.put("verifications", register.verifications(p.code()));
            m.put("suspensions", health.suspensionHistory(p.code()));
        }
        return m;
    }

    private static String staff() {
        return "staff:" + SecurityContextHolder.getContext().getAuthentication().getName();
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T body, HttpServletRequest req) {
        return ResponseEntity.ok(ApiResponse.success(body, rid(req)));
    }

    private static String rid(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
