package zw.co.payport.gateway.risk;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dispute timelines: every dispute gets an acknowledgement deadline and a resolution deadline when
 * it is opened. The durations are configuration (payport.disputes.sla.acknowledge-hours and
 * resolve-days) and MUST be set to the RBZ rules before go-live; the defaults are placeholders.
 */
@Service
public class DisputeSlaService {

    private final JdbcTemplate jdbc;
    private final int ackHours;
    private final int resolveDays;

    public DisputeSlaService(JdbcTemplate jdbc,
                             @Value("${payport.disputes.sla.acknowledge-hours:48}") int ackHours,
                             @Value("${payport.disputes.sla.resolve-days:30}") int resolveDays) {
        this.jdbc = jdbc;
        this.ackHours = ackHours;
        this.resolveDays = resolveDays;
    }

    public void setDeadlines(long disputeId) {
        jdbc.update("UPDATE disputes SET ack_due_at = created_at + INTERVAL ? HOUR, resolve_due_at = created_at + INTERVAL ? DAY WHERE id = ? AND ack_due_at IS NULL",
                ackHours, resolveDays, disputeId);
    }

    public Map<String, Object> acknowledge(long disputeId, String by) {
        int n = jdbc.update("UPDATE disputes SET acknowledged_at = ?, acknowledged_by = ? WHERE id = ? AND acknowledged_at IS NULL",
                Timestamp.valueOf(LocalDateTime.now()), by, disputeId);
        if (n == 0) throw new IllegalStateException("Dispute not found or already acknowledged");
        return view(jdbc.queryForMap("SELECT * FROM disputes WHERE id = ?", disputeId), LocalDateTime.now());
    }

    /** Every dispute with its deadlines and whether each is met, at risk (within 24 h) or breached. */
    public List<Map<String, Object>> timelines(LocalDateTime now) {
        jdbc.update("UPDATE disputes SET ack_due_at = created_at + INTERVAL ? HOUR, resolve_due_at = created_at + INTERVAL ? DAY WHERE ack_due_at IS NULL",
                ackHours, resolveDays);
        return jdbc.queryForList("SELECT * FROM disputes ORDER BY created_at DESC LIMIT 500").stream().map(d -> view(d, now)).toList();
    }

    private static Map<String, Object> view(Map<String, Object> d, LocalDateTime now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.get("id"));
        m.put("checkoutSessionId", d.get("checkout_session_id"));
        m.put("amountCents", d.get("amount_cents"));
        m.put("status", d.get("status"));
        m.put("createdAt", d.get("created_at"));
        m.put("acknowledgedAt", d.get("acknowledged_at"));
        m.put("ackDueAt", d.get("ack_due_at"));
        m.put("resolveDueAt", d.get("resolve_due_at"));
        m.put("acknowledgement", state(ts(d.get("ack_due_at")), ts(d.get("acknowledged_at")), now));
        m.put("resolution", state(ts(d.get("resolve_due_at")), ts(d.get("resolved_at")), now));
        return m;
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
