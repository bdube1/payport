package zw.co.payport.gateway.intelligence;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.Web;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PayPort Intelligence for staff: the switches and how each model is doing (administrators), complaint type
 * suggestions and the investigation helper for whoever works complaints. Everything is read-only advice.
 */
@RestController
public class IntelligenceController {

    private final Intelligence intelligence;
    private final ComplaintTriage triage;
    private final InvestigationHelper helper;
    private final FraudShadow fraud;

    public IntelligenceController(Intelligence intelligence, ComplaintTriage triage, InvestigationHelper helper, FraudShadow fraud) {
        this.intelligence = intelligence;
        this.triage = triage;
        this.helper = helper;
        this.fraud = fraud;
    }

    @GetMapping("/api/v1/admin/intelligence")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> overview(HttpServletRequest req) {
        return Web.run(() -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("features", intelligence.features());
            out.put("triage", triage.status());
            out.put("fraud", fraud.summary());
            return out;
        }, req);
    }

    public record Switch(Boolean enabled) {}

    @PostMapping("/api/v1/admin/intelligence/features/{feature}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> set(@PathVariable String feature, @RequestBody Switch b, HttpServletRequest req) {
        return Web.run(() -> intelligence.set(feature, b != null && Boolean.TRUE.equals(b.enabled()), Web.staff()), req);
    }

    @PostMapping("/api/v1/admin/intelligence/triage/retrain")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> retrain(HttpServletRequest req) {
        return Web.run(triage::retrain, req);
    }

    /** Which helpers the complaint desk should show. */
    @GetMapping("/api/v1/admin/complaints/assist")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> assist(HttpServletRequest req) {
        return Web.run(() -> Map.of("triage", intelligence.on(Intelligence.TRIAGE), "helper", intelligence.on(Intelligence.HELPER)), req);
    }

    public record Text(String text) {}

    @PostMapping("/api/v1/admin/complaints/triage")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> suggest(@RequestBody Text b, HttpServletRequest req) {
        return Web.run(() -> triage.suggest(b == null ? null : b.text()), req);
    }

    @GetMapping("/api/v1/admin/complaints/explain")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> explain(@RequestParam String ref, HttpServletRequest req) {
        return Web.run(() -> helper.explain(ref), req);
    }
}
