package zw.co.payport.gateway.intelligence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fraud scoring in shadow mode: every completed payment gets a score next to the existing risk rules, and the score
 * does nothing (no block, no alert, no message). It is measured against what investigations later confirm (risk
 * cases closed as confirmed fraud, and high-priority fraud complaints) and compared with the rules on the same
 * payments; only if it beats them would anyone consider letting it act, and that is a separate decision.
 *
 * Version 1 is an anomaly model on the payer's own history: how unusual the amount is for this payer (log-amount
 * z-score over 90 days), how many payments in the last hour, recent failed attempts, a first payment to this recipient
 * from an established payer, and the time of night. Its weights become learned weights once enough confirmed cases
 * exist. Only the reference and the score are stored, never the phone number.
 */
@Service
public class FraudShadow {

    private static final Logger log = LoggerFactory.getLogger(FraudShadow.class);
    static final int VERSION = 1;

    private final JdbcTemplate jdbc;
    private final Intelligence intelligence;

    public FraudShadow(JdbcTemplate jdbc, Intelligence intelligence) {
        this.jdbc = jdbc;
        this.intelligence = intelligence;
    }

    /** The features and the score for one payment (pure: tested on its own). */
    record Features(double z, int lastHour, int failed24h, boolean newRecipient, boolean night, int history) {}

    static double score(Features f) {
        double s = -4.0 + 0.8 * Math.max(0, Math.min(f.z(), 6)) + 0.35 * Math.max(0, Math.min(f.lastHour() - 1, 10))
                + 0.4 * Math.min(f.failed24h(), 5) + (f.newRecipient() && f.history() >= 3 ? 0.8 : 0) + (f.night() ? 0.6 : 0);
        return 1 / (1 + Math.exp(-s));
    }

    static String band(double p) {
        return p >= 0.7 ? "HIGH" : p >= 0.4 ? "MEDIUM" : "LOW";
    }

    static List<String> reasons(Features f) {
        List<String> r = new ArrayList<>();
        if (f.z() >= 2) r.add("amount unusual for this payer");
        if (f.lastHour() > 3) r.add(f.lastHour() + " payments in the last hour");
        if (f.failed24h() > 1) r.add(f.failed24h() + " failed attempts in 24 hours");
        if (f.newRecipient() && f.history() >= 3) r.add("first payment to this recipient");
        if (f.night()) r.add("between midnight and 5am");
        return r;
    }

    Features features(Map<String, Object> t) {
        String phone = (String) t.get("customer_phone");
        LocalDateTime at = t.get("created_at") instanceof LocalDateTime l ? l : ((Timestamp) t.get("created_at")).toLocalDateTime();
        long amount = ((Number) t.get("amount_cents")).longValue();
        Map<String, Object> h = jdbc.queryForMap("SELECT COUNT(*) AS n, AVG(LN(amount_cents)) AS mean, STDDEV_POP(LN(amount_cents)) AS sd FROM transactions "
                + "WHERE customer_phone = ? AND currency = ? AND status = 'COMPLETED' AND amount_cents > 0 AND reference <> ? AND created_at BETWEEN ? AND ?",
                phone, t.get("currency"), t.get("reference"), Timestamp.valueOf(at.minusDays(90)), Timestamp.valueOf(at));
        int n = ((Number) h.get("n")).intValue();
        double z = 0;
        if (n >= 3 && h.get("mean") != null) {
            double sd = h.get("sd") == null ? 0 : ((Number) h.get("sd")).doubleValue();
            z = (Math.log(Math.max(amount, 1)) - ((Number) h.get("mean")).doubleValue()) / Math.max(sd, 0.25);
        }
        Integer hour = jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE customer_phone = ? AND created_at BETWEEN ? AND ?", Integer.class,
                phone, Timestamp.valueOf(at.minusHours(1)), Timestamp.valueOf(at));
        Integer failed = jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE customer_phone = ? AND status IN ('PAYMENT_FAILED','PAYMENT_TIMEOUT') AND created_at BETWEEN ? AND ?",
                Integer.class, phone, Timestamp.valueOf(at.minusHours(24)), Timestamp.valueOf(at));
        Integer before = t.get("recipient_identifier") == null ? 1 : jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE customer_phone = ? AND recipient_identifier = ? "
                + "AND reference <> ? AND created_at < ?", Integer.class, phone, t.get("recipient_identifier"), t.get("reference"), Timestamp.valueOf(at));
        return new Features(z, hour == null ? 0 : hour, failed == null ? 0 : failed, before != null && before == 0, at.getHour() < 5, n);
    }

    /** Scores the payments completed since last time (from the platform event stream), while the feature is on. */
    @Scheduled(fixedDelayString = "${payport.intelligence.fraud-ms:60000}", initialDelay = 90_000)
    public int run() {
        if (!intelligence.on(Intelligence.FRAUD)) return 0;
        long cursor = jdbc.queryForObject("SELECT last_id FROM ai_cursor WHERE name = 'FRAUD_SHADOW'", Long.class);
        List<Map<String, Object>> events = jdbc.queryForList("SELECT id, subject_ref FROM platform_events WHERE id > ? AND event_type = 'payment.completed' ORDER BY id LIMIT 500", cursor);
        int scored = 0;
        for (Map<String, Object> e : events) {
            String ref = (String) e.get("subject_ref");
            try {
                List<Map<String, Object>> t = jdbc.queryForList("SELECT reference, customer_phone, recipient_identifier, amount_cents, currency, created_at FROM transactions WHERE reference = ?", ref);
                if (!t.isEmpty() && t.get(0).get("customer_phone") != null && t.get(0).get("amount_cents") != null) {
                    Features f = features(t.get(0));
                    double p = score(f);
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("reasons", reasons(f));
                    d.put("z", Math.round(f.z() * 100) / 100.0);
                    d.put("lastHour", f.lastHour());
                    d.put("failed24h", f.failed24h());
                    d.put("rulesFlagged", flagged(ref));
                    intelligence.predict(Intelligence.FRAUD, ref, VERSION, band(p), Math.round(p * 1000) / 1000.0, d);
                    scored++;
                }
            } catch (RuntimeException ex) {
                log.debug("[INTELLIGENCE] shadow score for {} skipped: {}", ref, ex.getMessage());
            }
            jdbc.update("UPDATE ai_cursor SET last_id = ? WHERE name = 'FRAUD_SHADOW'", ((Number) e.get("id")).longValue());
        }
        label();
        return scored;
    }

    boolean flagged(String ref) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM risk_alerts WHERE transaction_reference = ?", Integer.class, ref);
        return n != null && n > 0;
    }

    /** What investigations found: confirmed fraud, or (after 30 quiet days) not fraud. */
    void label() {
        jdbc.update("UPDATE ai_predictions p SET outcome = 'FRAUD', outcome_at = NOW(6) WHERE p.feature = 'FRAUD_SHADOW' AND p.outcome IS NULL AND ("
                + "EXISTS (SELECT 1 FROM risk_alerts a JOIN risk_cases c ON c.id = a.case_id WHERE a.transaction_reference = p.subject_ref AND c.status = 'CLOSED_CONFIRMED') "
                + "OR EXISTS (SELECT 1 FROM complaints k WHERE k.reference = p.subject_ref AND k.priority = 'HIGH'))");
        jdbc.update("UPDATE ai_predictions SET outcome = 'NOT_FRAUD', outcome_at = NOW(6) WHERE feature = 'FRAUD_SHADOW' AND outcome IS NULL AND created_at < NOW() - INTERVAL 30 DAY");
    }

    /** How the shadow score compares with the rules on the same payments. */
    public Map<String, Object> summary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("scored30d", jdbc.queryForList("SELECT label, COUNT(*) AS n FROM ai_predictions WHERE feature = 'FRAUD_SHADOW' AND created_at >= NOW() - INTERVAL 30 DAY GROUP BY label"));
        Map<String, Object> c = jdbc.queryForMap("SELECT "
                + "COALESCE(SUM(outcome = 'FRAUD'), 0) AS fraud, "
                + "COALESCE(SUM(outcome = 'FRAUD' AND label = 'HIGH'), 0) AS model_caught, COALESCE(SUM(label = 'HIGH' AND outcome IS NOT NULL), 0) AS model_flagged, "
                + "COALESCE(SUM(outcome = 'FRAUD' AND JSON_UNQUOTE(JSON_EXTRACT(detail, '$.rulesFlagged')) = 'true'), 0) AS rules_caught, "
                + "COALESCE(SUM(JSON_UNQUOTE(JSON_EXTRACT(detail, '$.rulesFlagged')) = 'true' AND outcome IS NOT NULL), 0) AS rules_flagged "
                + "FROM ai_predictions WHERE feature = 'FRAUD_SHADOW'");
        long fraud = ((Number) c.get("fraud")).longValue();
        s.put("confirmedFraud", fraud);
        s.put("model", rates(((Number) c.get("model_caught")).longValue(), ((Number) c.get("model_flagged")).longValue(), fraud));
        s.put("rules", rates(((Number) c.get("rules_caught")).longValue(), ((Number) c.get("rules_flagged")).longValue(), fraud));
        s.put("recentHigh", jdbc.queryForList("SELECT subject_ref AS reference, score, detail, created_at FROM ai_predictions WHERE feature = 'FRAUD_SHADOW' AND label = 'HIGH' "
                + "ORDER BY id DESC LIMIT 20"));
        return s;
    }

    static Map<String, Object> rates(long caught, long flagged, long fraud) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("flagged", flagged);
        r.put("caught", caught);
        r.put("precision", flagged == 0 ? null : Math.round(caught * 1000.0 / flagged) / 1000.0);
        r.put("recall", fraud == 0 ? null : Math.round(caught * 1000.0 / fraud) / 1000.0);
        return r;
    }
}
