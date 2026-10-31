package zw.co.payport.gateway.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Business figures for the Operations Control Centre (Prometheus, then Grafana on the TV). Read from Payport's own
 * tables every 30 seconds with a handful of indexed, read-only queries, so Grafana never needs database access and the
 * TV never adds load to the payment path. Everything is a count or an average: no references, phone numbers or names.
 *
 * Transaction groups follow the customer outcome: successful (COMPLETED), failed (PAYMENT_FAILED, PAYMENT_TIMEOUT,
 * FULFILMENT_FAILED), pending (INITIATED, PAYMENT_PENDING, PAYMENT_CONFIRMED, FULFILMENT_*), refunded.
 */
@Slf4j
@Component
public class ControlCentreMetrics {

    static final String SUCCESS = "'COMPLETED'";
    static final String FAILED = "'PAYMENT_FAILED','PAYMENT_TIMEOUT','FULFILMENT_FAILED'";
    static final String PENDING = "'INITIATED','PAYMENT_PENDING','PAYMENT_CONFIRMED','FULFILMENT_PENDING','FULFILMENT_RETRY','MANUAL_FULFILMENT'";

    private final JdbcTemplate jdbc;
    private final Map<String, Double> values = new ConcurrentHashMap<>();
    private final MultiGauge byStatus, providerUp, providerTx, providerSuccess, providerLatency, settlement, webhooks, riskAlerts, channelTx;

    public ControlCentreMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        for (String k : List.of("transactions_today", "transactions_last_5m", "success_rate_1h", "pending_payments", "uncertain_payments",
                "duplicate_callbacks_today", "failed_callbacks_today", "refunds_today", "reversals_pending", "recon_exceptions_open",
                "complaints_open", "complaints_overdue", "privileged_actions_24h", "value_today_usd_minor", "refresh_errors")) {
            Gauge.builder("payport.cc." + k, values, m -> m.getOrDefault(k, Double.NaN)).register(registry);
        }
        byStatus = MultiGauge.builder("payport.cc.transactions.today.by.outcome").register(registry);
        providerUp = MultiGauge.builder("payport.cc.provider.up").description("1 answered, 0 no answer, -1 not checked or not configured").register(registry);
        providerTx = MultiGauge.builder("payport.cc.provider.transactions.1h").register(registry);
        providerSuccess = MultiGauge.builder("payport.cc.provider.success.rate.1h").register(registry);
        providerLatency = MultiGauge.builder("payport.cc.provider.payment.latency.seconds.1h").register(registry);
        settlement = MultiGauge.builder("payport.cc.settlement.batches").register(registry);
        webhooks = MultiGauge.builder("payport.cc.webhook.deliveries").register(registry);
        riskAlerts = MultiGauge.builder("payport.cc.risk.alerts.open").register(registry);
        channelTx = MultiGauge.builder("payport.cc.transactions.today.by.channel").register(registry);
    }

    private double one(String sql, Object... args) {
        Number n = jdbc.queryForObject(sql, Number.class, args);
        return n == null ? 0 : n.doubleValue();
    }

    private static MultiGauge.Row<Number> row(Tags tags, Object value) {
        return MultiGauge.Row.of(tags, value == null ? Double.NaN : ((Number) value).doubleValue());
    }

    @Scheduled(fixedDelay = 30_000L, initialDelay = 20_000L)
    public void refresh() {
        try {
            Timestamp today = Timestamp.valueOf(LocalDate.now().atStartOfDay());
            Timestamp hour = Timestamp.valueOf(LocalDateTime.now().minusHours(1));
            Timestamp day = Timestamp.valueOf(LocalDateTime.now().minusHours(24));
            values.put("transactions_today", one("SELECT COUNT(*) FROM transactions WHERE created_at >= ?", today));
            values.put("transactions_last_5m", one("SELECT COUNT(*) FROM transactions WHERE created_at >= ?", Timestamp.valueOf(LocalDateTime.now().minusMinutes(5))));
            double ok = one("SELECT COUNT(*) FROM transactions WHERE created_at >= ? AND status IN (" + SUCCESS + ")", hour);
            double bad = one("SELECT COUNT(*) FROM transactions WHERE created_at >= ? AND status IN (" + FAILED + ")", hour);
            values.put("success_rate_1h", ok + bad == 0 ? Double.NaN : ok / (ok + bad));
            values.put("pending_payments", one("SELECT COUNT(*) FROM transactions WHERE status = 'PAYMENT_PENDING'"));
            values.put("uncertain_payments", one("SELECT COUNT(*) FROM payment_checks WHERE status = 'OPEN'"));
            values.put("duplicate_callbacks_today", one("SELECT COALESCE(SUM(received_count - 1), 0) FROM payment_callbacks WHERE last_received_at >= ?", today));
            values.put("failed_callbacks_today", one("SELECT COUNT(*) FROM payment_callbacks WHERE last_received_at >= ? AND status IN ('INVALID','ERROR','CONFLICT','UNKNOWN_REF')", today));
            values.put("refunds_today", one("SELECT COUNT(*) FROM transactions WHERE refunded_at >= ?", today));
            values.put("reversals_pending", one("SELECT COUNT(*) FROM reversal_requests WHERE status IN ('PENDING_APPROVAL','AWAITING_PAYOUT')"));
            values.put("recon_exceptions_open", one("SELECT COUNT(*) FROM recon_exceptions WHERE status = 'OPEN'"));
            values.put("complaints_open", one("SELECT COUNT(*) FROM complaints WHERE status IN ('OPEN','INVESTIGATING','AWAITING_REVERSAL')"));
            values.put("complaints_overdue", one("SELECT COUNT(*) FROM complaints WHERE status IN ('OPEN','INVESTIGATING','AWAITING_REVERSAL') AND due_at < ?", Timestamp.valueOf(LocalDateTime.now())));
            values.put("privileged_actions_24h", one("SELECT COUNT(*) FROM compliance_audit_log WHERE at >= ?", day));
            values.put("value_today_usd_minor", one("SELECT COALESCE(SUM(amount_cents), 0) FROM transactions WHERE created_at >= ? AND currency = 'USD' AND status IN (" + SUCCESS + ")", today));

            Map<String, Double> outcome = new java.util.LinkedHashMap<>(Map.of("successful", 0.0, "failed", 0.0, "pending", 0.0, "refunded", 0.0));
            for (Map<String, Object> r : jdbc.queryForList("SELECT CASE WHEN status IN (" + SUCCESS + ") THEN 'successful' WHEN status IN (" + FAILED + ") THEN 'failed' "
                    + "WHEN status IN ('REFUND_PENDING','REFUNDED') THEN 'refunded' ELSE 'pending' END AS o, COUNT(*) AS n FROM transactions WHERE created_at >= ? GROUP BY o", today)) {
                outcome.put((String) r.get("o"), ((Number) r.get("n")).doubleValue());
            }
            List<MultiGauge.Row<?>> rows = new ArrayList<>();
            outcome.forEach((k, v) -> rows.add(MultiGauge.Row.of(Tags.of("outcome", k), v)));
            byStatus.register(rows, true);

            List<MultiGauge.Row<?>> ch = new ArrayList<>();
            for (Map<String, Object> r : jdbc.queryForList("SELECT COALESCE(channel, 'WEB') AS c, COUNT(*) AS n FROM transactions WHERE created_at >= ? GROUP BY c", today)) {
                ch.add(row(Tags.of("channel", String.valueOf(r.get("c"))), r.get("n")));
            }
            channelTx.register(ch, true);

            List<MultiGauge.Row<?>> up = new ArrayList<>();
            for (Map<String, Object> r : jdbc.queryForList("SELECT provider_code, provider_type, status FROM provider_health")) {
                String s = String.valueOf(r.get("status"));
                double v = "UP".equals(s) || "REACHABLE".equals(s) ? 1 : "DOWN".equals(s) ? 0 : -1;
                up.add(MultiGauge.Row.of(Tags.of("provider", String.valueOf(r.get("provider_code")), "type", String.valueOf(r.get("provider_type"))), v));
            }
            providerUp.register(up, true);

            List<MultiGauge.Row<?>> tx = new ArrayList<>(), rate = new ArrayList<>(), lat = new ArrayList<>();
            for (Map<String, Object> r : jdbc.queryForList("SELECT COALESCE(payment_provider_code, 'UNKNOWN') AS p, COUNT(*) AS n, "
                    + "SUM(status IN (" + SUCCESS + ")) AS ok, SUM(status IN (" + FAILED + ")) AS bad, "
                    + "AVG(CASE WHEN payment_confirmed_at IS NOT NULL AND payment_submitted_at IS NOT NULL THEN TIMESTAMPDIFF(MICROSECOND, payment_submitted_at, payment_confirmed_at) END) / 1000000 AS lat "
                    + "FROM transactions WHERE created_at >= ? GROUP BY p", hour)) {
                Tags t = Tags.of("provider", String.valueOf(r.get("p")));
                tx.add(row(t, r.get("n")));
                double okP = r.get("ok") == null ? 0 : ((Number) r.get("ok")).doubleValue(), badP = r.get("bad") == null ? 0 : ((Number) r.get("bad")).doubleValue();
                rate.add(MultiGauge.Row.of(t, okP + badP == 0 ? Double.NaN : okP / (okP + badP)));
                lat.add(row(t, r.get("lat")));
            }
            providerTx.register(tx, true);
            providerSuccess.register(rate, true);
            providerLatency.register(lat, true);

            List<MultiGauge.Row<?>> st = new ArrayList<>();
            for (Map<String, Object> r : jdbc.queryForList("SELECT status, COUNT(*) AS n FROM stl_batches WHERE created_at >= ? OR status IN ('DRAFT','APPROVED','INSTRUCTED') GROUP BY status",
                    Timestamp.valueOf(LocalDateTime.now().minusDays(7)))) {
                st.add(row(Tags.of("status", String.valueOf(r.get("status"))), r.get("n")));
            }
            settlement.register(st, true);

            List<MultiGauge.Row<?>> wh = new ArrayList<>();
            for (Map<String, Object> r : jdbc.queryForList("SELECT status, COUNT(*) AS n FROM webhook_deliveries WHERE created_at >= ? GROUP BY status", day)) {
                wh.add(row(Tags.of("status", String.valueOf(r.get("status"))), r.get("n")));
            }
            webhooks.register(wh, true);

            List<MultiGauge.Row<?>> ra = new ArrayList<>();
            for (Map<String, Object> r : jdbc.queryForList("SELECT severity, COUNT(*) AS n FROM risk_alerts WHERE status = 'OPEN' GROUP BY severity")) {
                ra.add(row(Tags.of("severity", String.valueOf(r.get("severity"))), r.get("n")));
            }
            riskAlerts.register(ra, true);
        } catch (RuntimeException e) {
            values.merge("refresh_errors", 1.0, Double::sum);
            log.warn("[CONTROL-CENTRE] metrics refresh failed: {}", e.getMessage());
        }
    }
}
