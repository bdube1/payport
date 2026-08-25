package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.service.ReconciliationService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/reconciliation")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OPS','ADMIN')")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    @GetMapping("/run")
    public ResponseEntity<ApiResponse<Map<String, Object>>> run(
            @RequestParam(defaultValue = "7") int days, HttpServletRequest req) {
        ReconciliationService.ReconciliationReport report = reconciliationService.run(days);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionsChecked", report.sessionsChecked());
        body.put("discrepanciesFound", report.discrepanciesFound());
        body.put("discrepancies", report.discrepancies().stream().map(this::toMap).toList());
        return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
    }

    private Map<String, Object> toMap(ReconciliationService.Discrepancy d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checkoutSessionId", d.checkoutSessionId());
        m.put("sessionReference", d.sessionReference());
        m.put("type", d.type().toString());
        m.put("sessionStatus", d.sessionStatus());
        m.put("transactionStatus", d.transactionStatus());
        m.put("sessionAmountCents", d.sessionAmountCents());
        m.put("transactionAmountCents", d.transactionAmountCents());
        m.put("detail", d.detail());
        return m;
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
