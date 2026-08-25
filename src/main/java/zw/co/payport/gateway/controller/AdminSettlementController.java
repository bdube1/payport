package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.entity.Settlement;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.repository.MerchantRepository;
import zw.co.payport.gateway.service.SettlementService;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/settlements")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OPS','ADMIN')")
public class AdminSettlementController {

    private final SettlementService settlementService;
    private final MerchantRepository merchantRepository;

    public record RunBody(Integer days) {}
    public record MarkPaidBody(String payoutReference) {}

    @PostMapping("/run")
    public ResponseEntity<ApiResponse<Map<String, Object>>> run(@RequestBody(required = false) RunBody body, HttpServletRequest req) {
        int days = (body != null && body.days() != null) ? body.days() : 1;
        LocalDateTime periodEnd = LocalDateTime.now();
        LocalDateTime periodStart = periodEnd.minusDays(days);
        List<Settlement> created = settlementService.runForAllMerchants(periodStart, periodEnd);
        Map<String, Object> body2 = new LinkedHashMap<>();
        body2.put("settlementsCreated", created.size());
        body2.put("periodStart", periodStart);
        body2.put("periodEnd", periodEnd);
        return ResponseEntity.ok(ApiResponse.success(body2, requestId(req)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest req) {
        List<Settlement> settlements = settlementService.listAll();
        return ResponseEntity.ok(ApiResponse.success(settlements.stream().map(this::toMap).toList(), requestId(req)));
    }

    @PostMapping("/{id}/processing")
    public ResponseEntity<ApiResponse<Map<String, Object>>> markProcessing(@PathVariable Long id, HttpServletRequest req) {
        try {
            Settlement s = settlementService.markProcessing(id);
            return ResponseEntity.ok(ApiResponse.success(toMap(s), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("STATUS_CHANGE_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @PostMapping("/{id}/paid")
    public ResponseEntity<ApiResponse<Map<String, Object>>> markPaid(@PathVariable Long id, @RequestBody MarkPaidBody body, HttpServletRequest req) {
        try {
            Settlement s = settlementService.markPaid(id, body.payoutReference());
            return ResponseEntity.ok(ApiResponse.success(toMap(s), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("MARK_PAID_FAILED", e.getMessage(), requestId(req)));
        }
    }

    private Map<String, Object> toMap(Settlement s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("merchantId", s.getMerchantId());
        m.put("merchantName", merchantRepository.findById(s.getMerchantId()).map(Merchant::getBusinessName).orElse("Unknown"));
        m.put("periodStart", s.getPeriodStart());
        m.put("periodEnd", s.getPeriodEnd());
        m.put("currency", s.getCurrency());
        m.put("sessionCount", s.getSessionCount());
        m.put("grossAmountCents", s.getGrossAmountCents());
        m.put("commissionAmountCents", s.getCommissionAmountCents());
        m.put("refundAmountCents", s.getRefundAmountCents());
        m.put("netAmountCents", s.getNetAmountCents());
        m.put("status", s.getStatus());
        m.put("payoutReference", s.getPayoutReference());
        m.put("paidAt", s.getPaidAt());
        m.put("createdAt", s.getCreatedAt());
        return m;
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
