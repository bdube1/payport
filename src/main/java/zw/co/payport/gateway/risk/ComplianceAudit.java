package zw.co.payport.gateway.risk;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tamper-evident audit trail for compliance actions (rule and parameter changes, case work, report
 * approvals, list loads, screening, authority grants). Each entry stores the SHA-256 of the previous
 * entry, so deleting or editing any row breaks the chain, which verify() detects. Entries join the
 * caller's transaction: an action that rolls back leaves no audit entry, and an action that commits
 * always has one.
 */
@Component
public class ComplianceAudit {

    private final JdbcTemplate jdbc;

    public ComplianceAudit(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String actor, String action, String subject, String details) {
        String prev = jdbc.queryForObject("SELECT last_hash FROM compliance_audit_head WHERE id = 1 FOR UPDATE", String.class);
        LocalDateTime at = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        String d = details == null ? "" : (details.length() > 60000 ? details.substring(0, 60000) : details);
        String hash = hash(prev, at, actor, action, subject, d);
        jdbc.update("INSERT INTO compliance_audit_log (at, actor, action, subject, details, prev_hash, hash) VALUES (?,?,?,?,?,?,?)",
                Timestamp.valueOf(at), actor, action, subject, d, prev, hash);
        jdbc.update("UPDATE compliance_audit_head SET last_hash = ? WHERE id = 1", hash);
    }

    static String hash(String prev, LocalDateTime at, String actor, String action, String subject, String details) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{prev, at.toString(), actor, action, subject, details}) {
                md.update(part.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0x1F);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Recomputes the whole chain. */
    public Map<String, Object> verify() {
        String expectedPrev = "0".repeat(64);
        long checked = 0;
        Long brokenAt = null;
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM compliance_audit_log ORDER BY id")) {
            LocalDateTime at = r.get("at") instanceof Timestamp t ? t.toLocalDateTime() : (LocalDateTime) r.get("at");
            String recomputed = hash((String) r.get("prev_hash"), at, (String) r.get("actor"), (String) r.get("action"),
                    (String) r.get("subject"), r.get("details") == null ? "" : (String) r.get("details"));
            if (!expectedPrev.equals(r.get("prev_hash")) || !recomputed.equals(r.get("hash"))) {
                brokenAt = ((Number) r.get("id")).longValue();
                break;
            }
            expectedPrev = (String) r.get("hash");
            checked++;
        }
        String head = jdbc.queryForObject("SELECT last_hash FROM compliance_audit_head WHERE id = 1", String.class);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entriesChecked", checked);
        m.put("intact", brokenAt == null && head.equals(expectedPrev));
        m.put("brokenAtId", brokenAt);
        m.put("headMatches", head.equals(expectedPrev));
        return m;
    }

    public List<Map<String, Object>> trail(String subject) {
        return jdbc.queryForList("SELECT id, at, actor, action, subject, details FROM compliance_audit_log WHERE subject = ? ORDER BY id", subject);
    }
}
