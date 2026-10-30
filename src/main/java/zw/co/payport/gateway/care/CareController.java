package zw.co.payport.gateway.care;

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

import java.util.List;
import java.util.Map;

/**
 * Customer care: complaints (/admin/complaints), reversal requests (/admin/reversals) and reversal approval
 * (/admin/reversal-approvals) - three permissions, so the people who ask for a reversal and the people who approve
 * it can be different roles.
 */
@RestController
public class CareController {

    private final CareService care;
    private final ReversalService reversals;

    public CareController(CareService care, ReversalService reversals) {
        this.care = care;
        this.reversals = reversals;
    }

    // ─── complaints ──────────────────────────────────────────────────────────

    @GetMapping("/api/v1/admin/complaints/catalog")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> catalog(HttpServletRequest req) {
        return Web.run(() -> Map.of("categories", ComplaintCatalog.ALL, "channels", List.copyOf(CareService.CHANNELS), "statuses", List.copyOf(CareService.STATUSES)), req);
    }

    @GetMapping("/api/v1/admin/complaints")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(@RequestParam(required = false) String status, @RequestParam(required = false) String q, HttpServletRequest req) {
        return Web.run(() -> care.list(status, q), req);
    }

    @GetMapping("/api/v1/admin/complaints/summary")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> summary(HttpServletRequest req) {
        return Web.run(care::summary, req);
    }

    @GetMapping("/api/v1/admin/complaints/trace")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> trace(@RequestParam String ref, HttpServletRequest req) {
        return Web.run(() -> care.trace(ref), req);
    }

    @GetMapping("/api/v1/admin/complaints/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable long id, HttpServletRequest req) {
        return Web.run(() -> care.get(id), req);
    }

    @PostMapping("/api/v1/admin/complaints")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody CareService.NewComplaint b, HttpServletRequest req) {
        return Web.run(() -> care.create(b, Web.staff()), req);
    }

    public record NoteBody(String note) {}
    public record AssignBody(String to) {}
    public record StatusBody(String status, String resolution) {}

    @PostMapping("/api/v1/admin/complaints/{id}/notes")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> note(@PathVariable long id, @RequestBody NoteBody b, HttpServletRequest req) {
        return Web.run(() -> care.note(id, b == null ? null : b.note(), Web.staff()), req);
    }

    @PostMapping("/api/v1/admin/complaints/{id}/assign")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> assign(@PathVariable long id, @RequestBody(required = false) AssignBody b, HttpServletRequest req) {
        return Web.run(() -> care.assign(id, b == null ? null : b.to(), Web.staff()), req);
    }

    @PostMapping("/api/v1/admin/complaints/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(@PathVariable long id, @RequestBody StatusBody b, HttpServletRequest req) {
        return Web.run(() -> care.status(id, b == null ? null : b.status(), b == null ? null : b.resolution(), Web.staff()), req);
    }

    // ─── reversal requests ───────────────────────────────────────────────────

    @GetMapping("/api/v1/admin/reversals")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> reversals(@RequestParam(required = false) String status, HttpServletRequest req) {
        return Web.run(() -> reversals.list(status), req);
    }

    @GetMapping("/api/v1/admin/reversals/trace")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reversalTrace(@RequestParam String ref, HttpServletRequest req) {
        return Web.run(() -> care.trace(ref), req);
    }

    @PostMapping("/api/v1/admin/reversals")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> request(@RequestBody ReversalService.NewReversal b, HttpServletRequest req) {
        return Web.run(() -> reversals.request(b, Web.staff()), req);
    }

    // ─── approval (a different permission) ───────────────────────────────────

    public record DecisionBody(String note) {}
    public record PayoutBody(String payoutReference, String method) {}

    @GetMapping("/api/v1/admin/reversal-approvals")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> approvals(HttpServletRequest req) {
        return Web.run(() -> reversals.list("OPEN"), req);
    }

    @PostMapping("/api/v1/admin/reversal-approvals/{ref}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> approve(@PathVariable String ref, @RequestBody(required = false) DecisionBody b, HttpServletRequest req) {
        return Web.run(() -> reversals.approve(ref, b == null ? null : b.note(), Web.staff()), req);
    }

    @PostMapping("/api/v1/admin/reversal-approvals/{ref}/reject")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reject(@PathVariable String ref, @RequestBody DecisionBody b, HttpServletRequest req) {
        return Web.run(() -> reversals.reject(ref, b == null ? null : b.note(), Web.staff()), req);
    }

    @PostMapping("/api/v1/admin/reversal-approvals/{ref}/payout")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> payout(@PathVariable String ref, @RequestBody PayoutBody b, HttpServletRequest req) {
        return Web.run(() -> reversals.recordPayout(ref, b == null ? null : b.payoutReference(), b == null ? null : b.method(), Web.staff()), req);
    }
}
