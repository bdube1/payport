package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.config.MerchantAuthResolver;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.entity.Settlement;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.service.SettlementService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchant/settlements")
@RequiredArgsConstructor
@PreAuthorize("hasRole('MERCHANT')")
public class SettlementController {

    private final SettlementService settlementService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest req) {
        Merchant merchant = MerchantAuthResolver.currentMerchant();
        List<Settlement> settlements = settlementService.listForMerchant(merchant.getId());
        return ResponseEntity.ok(ApiResponse.success(settlements.stream().map(this::toMap).toList(), requestId(req)));
    }

    private Map<String, Object> toMap(Settlement s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
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
