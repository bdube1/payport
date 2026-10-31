package zw.co.payport.commerce;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Store figures for Prometheus (go-live pack, owner 31 Oct 2026), on this machine only: whether PayPort Payments is
 * reachable, how far behind the payment events are, payments stuck "being confirmed", orders in the last hour by
 * payment state, and texts waiting. No customer data.
 */
@RestController
public class Monitoring {

    private final JdbcTemplate jdbc;
    private final EventReader reader;

    public Monitoring(JdbcTemplate jdbc, EventReader reader) {
        this.jdbc = jdbc;
        this.reader = reader;
    }

    @GetMapping(value = "/internal/metrics", produces = "text/plain")
    public ResponseEntity<String> metrics(HttpServletRequest req) {
        String ip = req.getRemoteAddr();
        if (!("127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip))) return ResponseEntity.status(404).body("");
        Timestamp hour = Timestamp.valueOf(LocalDateTime.now().minusHours(1));
        StringBuilder b = new StringBuilder();
        b.append("# HELP commerce_psp_up 1 when PayPort Payments answered the last event read\n# TYPE commerce_psp_up gauge\n")
                .append("commerce_psp_up ").append(reader.pspUp() ? 1 : 0).append('\n');
        b.append("# HELP commerce_events_lag_seconds Seconds since the payment events were last read\n# TYPE commerce_events_lag_seconds gauge\n")
                .append("commerce_events_lag_seconds ").append((System.currentTimeMillis() - reader.lastRead()) / 1000).append('\n');
        Long stuck = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE payment_status IN ('PENDING','CONFIRMING') AND updated_at < ?", Long.class,
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(30)));
        b.append("# HELP commerce_orders_confirming_stale Orders whose payment has been 'being confirmed' for over 30 minutes\n# TYPE commerce_orders_confirming_stale gauge\n")
                .append("commerce_orders_confirming_stale ").append(stuck == null ? 0 : stuck).append('\n');
        b.append("# HELP commerce_orders_last_hour Orders placed in the last hour by payment state\n# TYPE commerce_orders_last_hour gauge\n");
        for (var r : jdbc.queryForList("SELECT payment_status AS s, COUNT(*) AS n FROM orders WHERE created_at >= ? GROUP BY payment_status", hour))
            b.append("commerce_orders_last_hour{state=\"").append(r.get("s")).append("\"} ").append(r.get("n")).append('\n');
        Long paid = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE paid_at >= ?", Long.class, hour);
        b.append("# HELP commerce_orders_paid_last_hour Orders paid in the last hour\n# TYPE commerce_orders_paid_last_hour gauge\n")
                .append("commerce_orders_paid_last_hour ").append(paid == null ? 0 : paid).append('\n');
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(b.toString());
    }
}
