package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.entity.CheckoutSession;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.repository.CheckoutSessionRepository;
import zw.co.payport.gateway.repository.MerchantRepository;

import java.util.*;

/** Commissions collected, both totalled and broken down per merchant -- separate from the general merchant dashboard since this is specifically a finance-facing view. */
@RestController
@RequestMapping("/api/v1/admin/reports/commission")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OPS','ADMIN','FINANCE')")
public class CommissionReportController {

    private final CheckoutSessionRepository checkoutSessionRepository;
    private final MerchantRepository merchantRepository;

    private record MerchantCommissionRow(Long merchantId, String merchantName, Integer effectiveCommissionBps,
                                          Map<String, Object> byCurrency, long totalCommissionCentsForSort) {}

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> report(HttpServletRequest req) {
        List<CheckoutSession> sessions = checkoutSessionRepository.findAll().stream()
                .filter(s -> isPaidFamily(s.getStatus()))
                .toList();

        Map<String, Long> totalByCurrency = new LinkedHashMap<>();
        for (CheckoutSession s : sessions) {
            long commission = s.getCommissionAmountCents() != null ? s.getCommissionAmountCents() : 0;
            totalByCurrency.merge(s.getCurrency(), commission, Long::sum);
        }

        Map<Long, Map<String, long[]>> byMerchant = new LinkedHashMap<>(); // merchantId -> currency -> [commissionCents, txCount]
        for (CheckoutSession s : sessions) {
            long commission = s.getCommissionAmountCents() != null ? s.getCommissionAmountCents() : 0;
            byMerchant.computeIfAbsent(s.getMerchantId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(s.getCurrency(), k -> new long[2]);
            long[] agg = byMerchant.get(s.getMerchantId()).get(s.getCurrency());
            agg[0] += commission;
            agg[1] += 1;
        }

        List<MerchantCommissionRow> rows = new ArrayList<>();
        for (Map.Entry<Long, Map<String, long[]>> e : byMerchant.entrySet()) {
            Merchant m = merchantRepository.findById(e.getKey()).orElse(null);
            Map<String, Object> byCurrency = new LinkedHashMap<>();
            long totalForSort = 0;
            for (Map.Entry<String, long[]> ce : e.getValue().entrySet()) {
                byCurrency.put(ce.getKey(), Map.of("commissionCents", ce.getValue()[0], "transactionCount", ce.getValue()[1]));
                totalForSort += ce.getValue()[0];
            }
            rows.add(new MerchantCommissionRow(
                    e.getKey(),
                    m != null ? m.getBusinessName() : "Unknown",
                    m != null ? m.getCommissionRateBps() : null,
                    byCurrency,
                    totalForSort
            ));
        }
        rows.sort((a, b) -> Long.compare(b.totalCommissionCentsForSort(), a.totalCommissionCentsForSort()));

        List<Map<String, Object>> perMerchant = rows.stream().map(r -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("merchantId", r.merchantId());
            row.put("merchantName", r.merchantName());
            row.put("effectiveCommissionBps", r.effectiveCommissionBps());
            row.put("byCurrency", r.byCurrency());
            return row;
        }).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totalCommissionByCurrency", totalByCurrency);
        body.put("perMerchant", perMerchant);
        return ResponseEntity.ok(ApiResponse.success(body, req.getHeader("X-Request-ID")));
    }

    private boolean isPaidFamily(String status) {
        return "PAID".equals(status) || "PARTIALLY_REFUNDED".equals(status) || "REFUNDED".equals(status);
    }
}
