package zw.co.payport.gateway.risk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Alerts and the cases they open. Alerts are written in their OWN transaction, so an alert for a
 * payment that is refused (and rolled back) still exists. One alert per rule, subject and day:
 * repeated hits increase its count. HIGH alerts open a case automatically (one open case per subject).
 */
@Slf4j
@Component
public class AlertStore {

    public record Hit(String ruleCode, int ruleVersion, String mode, String severity, String subjectType, String subjectKey,
                      String subjectName, String transactionReference, Double score, String details, boolean blocked, String dedupeSuffix) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate own;
    private final int caseSlaDays;

    public AlertStore(JdbcTemplate jdbc, PlatformTransactionManager tm, @Value("${payport.compliance.case-sla-days:5}") int caseSlaDays) {
        this.jdbc = jdbc;
        this.own = new TransactionTemplate(tm);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.caseSlaDays = caseSlaDays;
    }

    /** Records the hit; returns the alert id. */
    public long record(Hit h) {
        return own.execute(s -> {
            String key = h.ruleCode() + "|" + h.subjectKey() + "|" + (h.dedupeSuffix() != null ? h.dedupeSuffix() : LocalDate.now().toString());
            if (key.length() > 250) key = key.substring(0, 180) + "#" + sha256(key);
            Timestamp now = Timestamp.valueOf(LocalDateTime.now());
            int inserted = jdbc.update("INSERT IGNORE INTO risk_alerts (dedupe_key, rule_code, rule_version, mode, severity, subject_type, subject_key, subject_name, "
                            + "transaction_reference, score, details, action_taken, status, created_at, last_hit_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'OPEN', ?, ?)",
                    key, h.ruleCode(), h.ruleVersion(), h.mode(), h.severity(), h.subjectType(), h.subjectKey(), h.subjectName(), h.transactionReference(),
                    h.score(), h.details(), h.blocked() ? "BLOCKED" : "ALERT", now, now);
            if (inserted == 0) {
                jdbc.update("UPDATE risk_alerts SET hits = hits + 1, last_hit_at = ?, transaction_reference = COALESCE(?, transaction_reference), "
                        + "details = ?, action_taken = IF(? = 'BLOCKED', 'BLOCKED', action_taken) WHERE dedupe_key = ?",
                        now, h.transactionReference(), h.details(), h.blocked() ? "BLOCKED" : "ALERT", key);
            }
            long id = jdbc.queryForObject("SELECT id FROM risk_alerts WHERE dedupe_key = ?", Long.class, key);
            String status = jdbc.queryForObject("SELECT status FROM risk_alerts WHERE id = ?", String.class, id);
            if ("HIGH".equals(h.severity()) && !"CLOSED".equals(status)) attachToCase(id, h.subjectType(), h.subjectKey(), h.subjectName(), "HIGH", "SYSTEM:risk-engine");
            log.info("[RISK] {} {} on {} ({}{})", h.mode(), h.ruleCode(), h.subjectKey(), h.blocked() ? "BLOCKED" : "alert", inserted == 0 ? ", repeat" : "");
            return id;
        });
    }

    /** Puts an alert into the subject's open case, opening one if needed. Returns the case id. */
    public long attachToCase(long alertId, String subjectType, String subjectKey, String subjectName, String priority, String by) {
        List<Long> open = jdbc.queryForList("SELECT id FROM risk_cases WHERE subject_type = ? AND subject_key = ? AND status NOT LIKE 'CLOSED%' "
                + "ORDER BY id LIMIT 1 FOR UPDATE", Long.class, subjectType, subjectKey);
        long caseId;
        if (open.isEmpty()) {
            byte[] b = new byte[4];
            RANDOM.nextBytes(b);
            String ref = "CASE-" + LocalDate.now().toString().replace("-", "") + "-" + HexFormat.of().formatHex(b).toUpperCase();
            LocalDateTime now = LocalDateTime.now();
            jdbc.update("INSERT INTO risk_cases (case_ref, subject_type, subject_key, subject_name, status, priority, opened_at, sla_due_at) "
                    + "VALUES (?, ?, ?, ?, 'OPEN', ?, ?, ?)", ref, subjectType, subjectKey, subjectName, priority,
                    Timestamp.valueOf(now), Timestamp.valueOf(now.plusDays(caseSlaDays)));
            caseId = jdbc.queryForObject("SELECT id FROM risk_cases WHERE case_ref = ?", Long.class, ref);
            jdbc.update("INSERT INTO risk_case_notes (case_id, author, note, created_at) VALUES (?, ?, ?, ?)",
                    caseId, by, "Case opened from alert " + alertId, Timestamp.valueOf(now));
        } else {
            caseId = open.get(0);
        }
        jdbc.update("UPDATE risk_alerts SET status = 'IN_CASE', case_id = ? WHERE id = ? AND status <> 'CLOSED'", caseId, alertId);
        return caseId;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean hasOpenCase(String subjectType, String subjectKey) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM risk_cases WHERE subject_type = ? AND subject_key = ? AND status NOT LIKE 'CLOSED%'",
                Integer.class, subjectType, subjectKey);
        return n != null && n > 0;
    }

    public List<Map<String, Object>> alertsFor(String subjectKey) {
        return jdbc.queryForList("SELECT * FROM risk_alerts WHERE subject_key = ? ORDER BY id", subjectKey);
    }
}
