package zw.co.payport.gateway.risk;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dispute and complaint timelines, from compliance parameters (each shown with its source and
 * status, so provisional settings are visible wherever a deadline is shown):
 *
 *   COMPLAINT   acknowledge within DISPUTE.ACK_HOURS; resolve within DISPUTE.RESOLVE_WORKING_DAYS
 *               working days; if late, progress updates every DISPUTE.PROGRESS_UPDATE_DAYS
 *   ERROR       resolve within DISPUTE.ERROR_RESOLVE_HOURS; if not, notify the RBZ within
 *               DISPUTE.ERROR_RBZ_NOTICE_DAYS
 *   CHARGEBACK  acknowledge as a complaint; respond within DISPUTE.CHARGEBACK_RESPONSE_DAYS
 *   all         tell the complainant the outcome within DISPUTE.OUTCOME_NOTICE_DAYS of resolution
 */
@Service
public class DisputeSlaService {

    public static final Set<String> TYPES = Set.of("COMPLAINT", "ERROR", "CHARGEBACK");
    public static final Set<String> UPDATE_KINDS = Set.of("PROGRESS_UPDATE", "OUTCOME_NOTICE", "RBZ_NOTICE");

    private final JdbcTemplate jdbc;
    private final ComplianceParameters params;
    private final WorkingCalendar calendar;

    public DisputeSlaService(JdbcTemplate jdbc, ComplianceParameters params, WorkingCalendar calendar) {
        this.jdbc = jdbc;
        this.params = params;
        this.calendar = calendar;
    }

    public void setDeadlines(long disputeId) {
        Map<String, Object> d = jdbc.queryForMap("SELECT id, created_at, dispute_type, ack_due_at FROM disputes WHERE id = ?", disputeId);
        if (d.get("ack_due_at") != null) return;
        LocalDateTime created = ts(d.get("created_at"));
        String type = (String) d.get("dispute_type");
        LocalDateTime ack = "ERROR".equals(type) ? null : created.plusHours((long) params.number("DISPUTE.ACK_HOURS"));
        LocalDateTime resolve = switch (type) {
            case "ERROR" -> created.plusHours((long) params.number("DISPUTE.ERROR_RESOLVE_HOURS"));
            case "CHARGEBACK" -> created.plusDays((long) params.number("DISPUTE.CHARGEBACK_RESPONSE_DAYS"));
            default -> calendar.addWorkingDays(created, (int) params.number("DISPUTE.RESOLVE_WORKING_DAYS"), "ZW");
        };
        jdbc.update("UPDATE disputes SET ack_due_at = ?, resolve_due_at = ? WHERE id = ?",
                ack == null ? Timestamp.valueOf(created) : Timestamp.valueOf(ack), Timestamp.valueOf(resolve), disputeId);
    }

    @Transactional
    public Map<String, Object> acknowledge(long disputeId, String by) {
        int n = jdbc.update("UPDATE disputes SET acknowledged_at = ?, acknowledged_by = ? WHERE id = ? AND acknowledged_at IS NULL",
                Timestamp.valueOf(LocalDateTime.now()), by, disputeId);
        if (n == 0) throw new IllegalStateException("Dispute not found or already acknowledged");
        jdbc.update("INSERT INTO dispute_updates (dispute_id, kind, note, by_user, at) VALUES (?, 'ACKNOWLEDGEMENT', 'Acknowledged', ?, ?)",
                disputeId, by, Timestamp.valueOf(LocalDateTime.now()));
        return view(jdbc.queryForMap("SELECT * FROM disputes WHERE id = ?", disputeId), LocalDateTime.now());
    }

    /** Records a progress update, the outcome notice to the complainant, or the notice to the RBZ. */
    @Transactional
    public Map<String, Object> recordUpdate(long disputeId, String kind, String note, String by) {
        if (!UPDATE_KINDS.contains(kind)) throw new IllegalArgumentException("kind must be one of " + UPDATE_KINDS);
        if (note == null || note.isBlank()) throw new IllegalArgumentException("a note of what was communicated is required");
        Map<String, Object> d = jdbc.queryForMap("SELECT * FROM disputes WHERE id = ? FOR UPDATE", disputeId);
        if ("OUTCOME_NOTICE".equals(kind) && d.get("resolved_at") == null) throw new IllegalStateException("Resolve the dispute before sending the outcome");
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO dispute_updates (dispute_id, kind, note, by_user, at) VALUES (?, ?, ?, ?, ?)", disputeId, kind, note, by, now);
        if ("OUTCOME_NOTICE".equals(kind)) jdbc.update("UPDATE disputes SET outcome_notified_at = ? WHERE id = ?", now, disputeId);
        if ("RBZ_NOTICE".equals(kind)) jdbc.update("UPDATE disputes SET rbz_notified_at = ? WHERE id = ?", now, disputeId);
        return view(jdbc.queryForMap("SELECT * FROM disputes WHERE id = ?", disputeId), LocalDateTime.now());
    }

    public List<Map<String, Object>> timelines(LocalDateTime now) {
        for (Long id : jdbc.queryForList("SELECT id FROM disputes WHERE ack_due_at IS NULL", Long.class)) setDeadlines(id);
        return jdbc.queryForList("SELECT * FROM disputes ORDER BY created_at DESC LIMIT 500").stream().map(d -> view(d, now)).toList();
    }

    /** The parameters behind the deadlines, with source and status (for "provisional" badges). */
    public List<Map<String, Object>> settings() {
        return jdbc.queryForList("SELECT param_key, value_json, unit, description, source, status FROM compliance_parameters "
                + "WHERE category = 'DISPUTES' OR param_key LIKE 'CALENDAR.%' ORDER BY param_key");
    }

    Map<String, Object> view(Map<String, Object> d, LocalDateTime now) {
        String type = (String) d.get("dispute_type");
        long id = ((Number) d.get("id")).longValue();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", type);
        m.put("checkoutSessionId", d.get("checkout_session_id"));
        m.put("amountCents", d.get("amount_cents"));
        m.put("status", d.get("status"));
        m.put("createdAt", d.get("created_at"));
        LocalDateTime resolved = ts(d.get("resolved_at")), resolveDue = ts(d.get("resolve_due_at"));
        List<Map<String, Object>> deadlines = new ArrayList<>();
        if (!"ERROR".equals(type)) deadlines.add(deadline("ACKNOWLEDGE", ts(d.get("ack_due_at")), ts(d.get("acknowledged_at")), now, "DISPUTE.ACK_HOURS"));
        deadlines.add(deadline("RESOLVE", resolveDue, resolved, now, switch (type) {
            case "ERROR" -> "DISPUTE.ERROR_RESOLVE_HOURS";
            case "CHARGEBACK" -> "DISPUTE.CHARGEBACK_RESPONSE_DAYS";
            default -> "DISPUTE.RESOLVE_WORKING_DAYS";
        }));
        if (resolved == null && resolveDue != null && now.isAfter(resolveDue) && !"ERROR".equals(type)) {
            Timestamp last = jdbc.queryForObject("SELECT MAX(at) FROM dispute_updates WHERE dispute_id = ? AND kind = 'PROGRESS_UPDATE'", Timestamp.class, id);
            LocalDateTime base = last == null ? resolveDue : last.toLocalDateTime();
            deadlines.add(deadline("PROGRESS_UPDATE", base.plusDays((long) params.number("DISPUTE.PROGRESS_UPDATE_DAYS")), null, now, "DISPUTE.PROGRESS_UPDATE_DAYS"));
        }
        if ("ERROR".equals(type) && resolveDue != null && (resolved == null ? now.isAfter(resolveDue) : resolved.isAfter(resolveDue))) {
            deadlines.add(deadline("RBZ_NOTICE", ts(d.get("created_at")).plusDays((long) params.number("DISPUTE.ERROR_RBZ_NOTICE_DAYS")),
                    ts(d.get("rbz_notified_at")), now, "DISPUTE.ERROR_RBZ_NOTICE_DAYS"));
        }
        if (resolved != null) {
            deadlines.add(deadline("OUTCOME_NOTICE", resolved.plusDays((long) params.number("DISPUTE.OUTCOME_NOTICE_DAYS")),
                    ts(d.get("outcome_notified_at")), now, "DISPUTE.OUTCOME_NOTICE_DAYS"));
        }
        m.put("deadlines", deadlines);
        m.put("worst", deadlines.stream().map(x -> (String) x.get("state")).reduce("ON_TRACK", DisputeSlaService::worse));
        // Backwards-compatible fields
        m.put("acknowledgement", deadlines.stream().filter(x -> "ACKNOWLEDGE".equals(x.get("kind"))).map(x -> x.get("state")).findFirst().orElse("NOT_APPLICABLE"));
        m.put("resolution", deadlines.stream().filter(x -> "RESOLVE".equals(x.get("kind"))).map(x -> x.get("state")).findFirst().orElse("NO_DEADLINE"));
        return m;
    }

    private Map<String, Object> deadline(String kind, LocalDateTime due, LocalDateTime done, LocalDateTime now, String paramKey) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("dueAt", due);
        m.put("doneAt", done);
        m.put("state", state(due, done, now));
        Map<String, Object> p = jdbc.queryForMap("SELECT status, source FROM compliance_parameters WHERE param_key = ?", paramKey);
        m.put("rule", paramKey);
        m.put("ruleStatus", p.get("status"));
        m.put("ruleSource", p.get("source"));
        return m;
    }

    private static String worse(String a, String b) {
        List<String> order = List.of("MET", "ON_TRACK", "NO_DEADLINE", "AT_RISK", "MET_LATE", "BREACHED");
        return order.indexOf(a) >= order.indexOf(b) ? a : b;
    }

    static String state(LocalDateTime due, LocalDateTime done, LocalDateTime now) {
        if (due == null) return "NO_DEADLINE";
        if (done != null) return done.isAfter(due) ? "MET_LATE" : "MET";
        if (now.isAfter(due)) return "BREACHED";
        return now.plusHours(24).isAfter(due) ? "AT_RISK" : "ON_TRACK";
    }

    private static LocalDateTime ts(Object o) {
        if (o instanceof Timestamp t) return t.toLocalDateTime();
        if (o instanceof LocalDateTime l) return l;
        return null;
    }
}
