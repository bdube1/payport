package zw.co.payport.gateway.risk;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ADMIN side of compliance designations: request a REPORT_APPROVER, revoke one. ADMIN sees no compliance data. */
@RestController
@RequestMapping("/api/v1/admin/compliance-authorities")
@PreAuthorize("hasRole('ADMIN')")
public class ComplianceAdminController {

    private final ComplianceAuthorities authorities;

    public ComplianceAdminController(ComplianceAuthorities authorities) {
        this.authorities = authorities;
    }

    public record GrantBody(String holder, String authority, String reason) {}

    @PostMapping("/requests")
    public ResponseEntity<ApiResponse<Map<String, Object>>> request(@RequestBody GrantBody b, HttpServletRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(authorities.request(b.holder(), b.authority(), b.reason(), ComplianceController.officer()), rid(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("AUTHORITY_REQUEST_INVALID", e.getMessage(), rid(req)));
        }
    }

    @PostMapping("/revoke")
    public ResponseEntity<ApiResponse<Map<String, Object>>> revoke(@RequestBody GrantBody b, HttpServletRequest req) {
        try {
            authorities.revoke(b.holder(), b.authority(), ComplianceController.officer());
            return ResponseEntity.ok(ApiResponse.success(Map.of("revoked", true), rid(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.status(409).body(ApiResponse.error("REVOKE_FAILED", e.getMessage(), rid(req)));
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest req) {
        return ResponseEntity.ok(ApiResponse.success(authorities.list(), rid(req)));
    }

    private static String rid(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
