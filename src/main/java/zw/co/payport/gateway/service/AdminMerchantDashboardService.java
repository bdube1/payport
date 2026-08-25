package zw.co.payport.gateway.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.entity.CheckoutSession;
import zw.co.payport.gateway.entity.Dispute;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.repository.CheckoutSessionRepository;
import zw.co.payport.gateway.repository.DisputeRepository;
import zw.co.payport.gateway.repository.MerchantRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminMerchantDashboardService {

    private final MerchantRepository merchantRepository;
    private final CheckoutSessionRepository checkoutSessionRepository;
    private final DisputeRepository disputeRepository;

    public Map<String, Object> getOverview(int trendDays) {
        List<Merchant> merchants = merchantRepository.findAll();
        List<CheckoutSession> allSessions = checkoutSessionRepository.findAll();

        Map<String, Long> merchantsByStatus = merchants.stream()
                .collect(Collectors.groupingBy(Merchant::getStatus, Collectors.counting()));

        Map<String, long[]> volumeByCurrency = new LinkedHashMap<>();
        for (CheckoutSession s : allSessions) {
            if (!isPaidFamily(s.getStatus())) continue;
            long[] totals = volumeByCurrency.computeIfAbsent(s.getCurrency(), k -> new long[2]);
            totals[0] += s.getAmountCents();
            totals[1] += s.getCommissionAmountCents() != null ? s.getCommissionAmountCents() : 0;
        }

        long paidCount = allSessions.stream().filter(s -> isPaidFamily(s.getStatus())).count();
        long failedCount = allSessions.stream().filter(s -> "FAILED".equals(s.getStatus())).count();

        List<Dispute> disputes = disputeRepository.findAll();
        Map<String, Long> disputesByStatus = disputes.stream()
                .collect(Collectors.groupingBy(Dispute::getStatus, Collectors.counting()));

        LocalDateTime cutoff = LocalDateTime.now().minusDays(trendDays);
        Map<String, Long> dailyGross = new TreeMap<>();
        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;
        for (int i = 0; i < trendDays; i++) {
            dailyGross.put(LocalDate.now().minusDays(i).format(fmt), 0L);
        }
        for (CheckoutSession s : allSessions) {
            if (!isPaidFamily(s.getStatus())) continue;
            if (s.getCreatedAt().isBefore(cutoff)) continue;
            String day = s.getCreatedAt().toLocalDate().format(fmt);
            dailyGross.merge(day, s.getAmountCents(), Long::sum);
        }
        List<Map<String, Object>> trend = dailyGross.entrySet().stream()
                .map(e -> Map.<String, Object>of("date", e.getKey(), "grossCents", e.getValue()))
                .sorted(Comparator.comparing(m -> (String) m.get("date")))
                .toList();

        Map<Long, Long> grossByMerchant = new HashMap<>();
        for (CheckoutSession s : allSessions) {
            if (!isPaidFamily(s.getStatus())) continue;
            grossByMerchant.merge(s.getMerchantId(), s.getAmountCents(), Long::sum);
        }
        List<Map<String, Object>> leaderboard = grossByMerchant.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(10)
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("merchantId", e.getKey());
                    row.put("merchantName", merchantRepository.findById(e.getKey()).map(Merchant::getBusinessName).orElse("Unknown"));
                    row.put("grossCents", e.getValue());
                    return row;
                }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("merchantsByStatus", merchantsByStatus);
        result.put("totalMerchants", merchants.size());
        result.put("volumeByCurrency", volumeByCurrency.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> Map.of("grossCents", e.getValue()[0], "commissionCents", e.getValue()[1]),
                        (a, b) -> a, LinkedHashMap::new)));
        result.put("paidTransactionCount", paidCount);
        result.put("failedTransactionCount", failedCount);
        result.put("disputesByStatus", disputesByStatus);
        result.put("totalDisputes", disputes.size());
        result.put("trend", trend);
        result.put("leaderboard", leaderboard);
        return result;
    }

    private boolean isPaidFamily(String status) {
        return "PAID".equals(status) || "PARTIALLY_REFUNDED".equals(status) || "REFUNDED".equals(status);
    }
}
