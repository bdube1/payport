package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.entity.Dispute;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.service.DisputeService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/disputes")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OPS','ADMIN')")
public class DisputeAdminController {

    private final DisputeService disputeService;

    public record OpenBody(Long checkoutSessionId, Long amountCents, String reason) {}
    public record ResolveBody(String status, String evidenceNotes) {}

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> open(@RequestBody OpenBody body, HttpServletRequest req) {
        try {
            Dispute dispute = disputeService.open(new DisputeService.OpenDisputeRequest(body.checkoutSessionId(), body.amountCents(), body.reason()));
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toMap(dispute), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("DISPUTE_OPEN_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resolve(@PathVariable Long id, @RequestBody ResolveBody body, HttpServletRequest req) {
        try {
            Dispute dispute = disputeService.resolve(id, body.status(), body.evidenceNotes());
            return ResponseEntity.ok(ApiResponse.success(toMap(dispute), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("DISPUTE_RESOLVE_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest req) {
        List<Dispute> disputes = disputeService.listAll();
        return ResponseEntity.ok(ApiResponse.success(disputes.stream().map(this::toMap).toList(), requestId(req)));
    }

    private Map<String, Object> toMap(Dispute d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("checkoutSessionId", d.getCheckoutSessionId());
        m.put("amountCents", d.getAmountCents());
        m.put("reason", d.getReason());
        m.put("status", d.getStatus());
        m.put("evidenceNotes", d.getEvidenceNotes());
        m.put("createdAt", d.getCreatedAt());
        m.put("resolvedAt", d.getResolvedAt());
        return m;
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
