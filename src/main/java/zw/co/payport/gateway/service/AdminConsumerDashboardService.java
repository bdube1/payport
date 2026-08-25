package zw.co.payport.gateway.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.entity.Transaction;
import zw.co.payport.gateway.entity.User;
import zw.co.payport.gateway.enums.TransactionChannel;
import zw.co.payport.gateway.repository.TransactionRepository;
import zw.co.payport.gateway.repository.UserRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminConsumerDashboardService {

    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;

    public Map<String, Object> getOverview(int trendDays) {
        List<User> consumers = userRepository.findAll().stream()
                .filter(u -> "CUSTOMER".equals(u.getRole()))
                .toList();

        List<Transaction> consumerTx = transactionRepository.findAll().stream()
                .filter(t -> t.getChannel() != TransactionChannel.API)
                .toList();

        long activeCount = consumers.stream().filter(u -> Boolean.TRUE.equals(u.getIsActive())).count();
        long verifiedCount = consumers.stream().filter(u -> Boolean.TRUE.equals(u.getIsVerified())).count();

        Map<String, Long> volumeByCurrency = new LinkedHashMap<>();
        Map<String, Long> countByCurrency = new LinkedHashMap<>();
        for (Transaction t : consumerTx) {
            if (t.getStatus() == null || !"COMPLETED".equals(t.getStatus().toString())) continue;
            volumeByCurrency.merge(t.getCurrency(), t.getAmountCents(), Long::sum);
            countByCurrency.merge(t.getCurrency(), 1L, Long::sum);
        }

        Map<String, Long> productBreakdown = consumerTx.stream()
                .filter(t -> t.getStatus() != null && "COMPLETED".equals(t.getStatus().toString()))
                .filter(t -> t.getProductType() != null)
                .collect(Collectors.groupingBy(t -> t.getProductType().getCategory(), Collectors.counting()));

        LocalDateTime cutoff = LocalDateTime.now().minusDays(trendDays);
        Map<String, Long> dailySignups = new TreeMap<>();
        Map<String, Long> dailyTxCount = new TreeMap<>();
        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;
        for (int i = 0; i < trendDays; i++) {
            String day = LocalDate.now().minusDays(i).format(fmt);
            dailySignups.put(day, 0L);
            dailyTxCount.put(day, 0L);
        }
        for (User u : consumers) {
            if (u.getRegisteredAt() == null || u.getRegisteredAt().isBefore(cutoff)) continue;
            dailySignups.merge(u.getRegisteredAt().toLocalDate().format(fmt), 1L, Long::sum);
        }
        for (Transaction t : consumerTx) {
            if (t.getCreatedAt() == null || t.getCreatedAt().isBefore(cutoff)) continue;
            if (t.getStatus() == null || !"COMPLETED".equals(t.getStatus().toString())) continue;
            dailyTxCount.merge(t.getCreatedAt().toLocalDate().format(fmt), 1L, Long::sum);
        }

        List<Map<String, Object>> signupTrend = dailySignups.entrySet().stream()
                .map(e -> Map.<String, Object>of("date", e.getKey(), "signups", e.getValue()))
                .sorted(Comparator.comparing(m -> (String) m.get("date")))
                .toList();
        List<Map<String, Object>> txTrend = dailyTxCount.entrySet().stream()
                .map(e -> Map.<String, Object>of("date", e.getKey(), "count", e.getValue()))
                .sorted(Comparator.comparing(m -> (String) m.get("date")))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalConsumers", consumers.size());
        result.put("activeConsumers", activeCount);
        result.put("verifiedConsumers", verifiedCount);
        result.put("volumeByCurrency", volumeByCurrency);
        result.put("transactionCountByCurrency", countByCurrency);
        result.put("productBreakdown", productBreakdown);
        result.put("signupTrend", signupTrend);
        result.put("transactionTrend", txTrend);
        return result;
    }
}
