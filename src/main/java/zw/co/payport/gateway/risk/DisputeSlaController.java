package zw.co.payport.gateway.risk;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Dispute timelines for the people who work disputes. */
@RestController
@RequestMapping("/api/v1/admin/dispute-timelines")
@PreAuthorize("hasAnyRole('OPS','ADMIN','COMPLIANCE')")
public class DisputeSlaController {

    private final DisputeSlaService sla;

    public DisputeSlaController(DisputeSlaService sla) {
        this.sla = sla;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest req) {
        return ResponseEntity.ok(ApiResponse.success(sla.timelines(LocalDateTime.now()), rid(req)));
    }

    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<ApiResponse<Map<String, Object>>> acknowledge(@PathVariable long id, HttpServletRequest req) {
        try {
            return ResponseEntity.ok(ApiResponse.success(sla.acknowledge(id, ComplianceController.officer()), rid(req)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(ApiResponse.error("ACKNOWLEDGE_FAILED", e.getMessage(), rid(req)));
        }
    }

    private static String rid(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
