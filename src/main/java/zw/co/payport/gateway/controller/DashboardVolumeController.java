package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.Web;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hourly transaction volume for the dashboard chart and sparklines, and the same-time-yesterday comparison,
 * counted in the database (one grouped query per day) instead of shipping every transaction to the browser.
 */
@RestController
public class DashboardVolumeController {

    static final List<String> PENDING = List.of("INITIATED", "PAYMENT_PENDING", "PAYMENT_CONFIRMED", "FULFILMENT_PENDING", "FULFILMENT_RETRY");
    static final List<String> FAILED = List.of("PAYMENT_FAILED", "FULFILMENT_FAILED", "PAYMENT_TIMEOUT");
    static final List<String> ATTENTION = List.of("FULFILMENT_FAILED", "MANUAL_FULFILMENT", "REFUND_PENDING", "FULFILMENT_RETRY");

    private final JdbcTemplate jdbc;

    public DashboardVolumeController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/admin/dashboard/volume")
    @PreAuthorize("hasAnyRole('OPS','ADMIN','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> volume(HttpServletRequest req) {
        return Web.run(() -> {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime start = LocalDate.now().atStartOfDay();
            int hours = now.getHour() + 1;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("hours", hours);
            out.put("today", day(start, now, hours));
            Map<String, Object> y = day(start.minusDays(1), now.minusDays(1), hours);
            Map<String, Object> ySum = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : y.entrySet()) {
                long s = 0;
                for (long v : (long[]) e.getValue()) s += v;
                ySum.put(e.getKey(), s);
            }
            out.put("yesterdaySoFar", ySum);
            return out;
        }, req);
    }

    private Map<String, Object> day(LocalDateTime from, LocalDateTime to, int hours) {
        long[] all = new long[hours], completed = new long[hours], pending = new long[hours], failed = new long[hours],
                attention = new long[hours], revenueUsd = new long[hours];
        for (Map<String, Object> r : jdbc.queryForList("SELECT HOUR(created_at) AS h, status, COUNT(*) AS n, "
                + "COALESCE(SUM(CASE WHEN COALESCE(currency, 'USD') = 'USD' THEN amount_cents ELSE 0 END), 0) AS usd "
                + "FROM transactions WHERE created_at >= ? AND created_at < ? GROUP BY HOUR(created_at), status",
                Timestamp.valueOf(from), Timestamp.valueOf(to))) {
            int h = ((Number) r.get("h")).intValue();
            if (h < 0 || h >= hours) continue;
            String s = (String) r.get("status");
            long n = ((Number) r.get("n")).longValue();
            all[h] += n;
            if ("COMPLETED".equals(s)) { completed[h] += n; revenueUsd[h] += ((Number) r.get("usd")).longValue(); }
            if (PENDING.contains(s)) pending[h] += n;
            if (FAILED.contains(s)) failed[h] += n;
            if (ATTENTION.contains(s)) attention[h] += n;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("all", all); m.put("completed", completed); m.put("pending", pending); m.put("failed", failed);
        m.put("attention", attention); m.put("revenueUsd", revenueUsd);
        return m;
    }
}
