package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.entity.Settlement;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.service.AdminConsumerDashboardService;
import zw.co.payport.gateway.service.AdminMerchantDashboardService;
import zw.co.payport.gateway.service.SettlementService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/reports/consolidated")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OPS','ADMIN')")
public class ConsolidatedReportController {

    private final AdminMerchantDashboardService merchantDashboardService;
    private final AdminConsumerDashboardService consumerDashboardService;
    private final SettlementService settlementService;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(
            @RequestParam(defaultValue = "30") int days, HttpServletRequest req) {

        Map<String, Object> merchants = merchantDashboardService.getOverview(days);
        Map<String, Object> consumers = consumerDashboardService.getOverview(days);

        List<Settlement> allSettlements = settlementService.listAll();
        long settledCount = allSettlements.stream().filter(s -> "PAID".equals(s.getStatus())).count();
        long pendingPayoutCount = allSettlements.stream().filter(s -> !"PAID".equals(s.getStatus())).count();
        Map<String, Long> netPaidByCurrency = new LinkedHashMap<>();
        for (Settlement s : allSettlements) {
            if (!"PAID".equals(s.getStatus())) continue;
            netPaidByCurrency.merge(s.getCurrency(), s.getNetAmountCents(), Long::sum);
        }

        Map<String, Object> settlementSummary = new LinkedHashMap<>();
        settlementSummary.put("totalSettlements", allSettlements.size());
        settlementSummary.put("paidOut", settledCount);
        settlementSummary.put("awaitingPayout", pendingPayoutCount);
        settlementSummary.put("netPaidByCurrency", netPaidByCurrency);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("periodDays", days);
        body.put("merchants", merchants);
        body.put("consumers", consumers);
        body.put("settlements", settlementSummary);
        return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
