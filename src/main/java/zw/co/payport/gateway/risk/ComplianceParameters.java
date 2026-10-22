package zw.co.payport.gateway.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.platform.ApprovalStore;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compliance parameters (thresholds, deadlines, screening settings, calendars), each with its
 * source and status (SOURCED / PROVISIONAL / CONFIRMED). Values change, and reach CONFIRMED, only
 * through a request by one compliance officer and approval by another. Read paths cache for 30 s.
 */
@Service
public class ComplianceParameters {

    private final JdbcTemplate jdbc;
    private final ComplianceAudit audit;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Object[]> cache = new ConcurrentHashMap<>();

    public ComplianceParameters(JdbcTemplate jdbc, ComplianceAudit audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public JsonNode get(String key) {
        Object[] c = cache.get(key);
        if (c != null && System.currentTimeMillis() - (long) c[1] < 30_000) return (JsonNode) c[0];
        List<String> v = jdbc.queryForList("SELECT value_json FROM compliance_parameters WHERE param_key = ?", String.class, key);
        if (v.isEmpty()) throw new IllegalStateException("Compliance parameter missing: " + key);
        try {
            JsonNode n = json.readTree(v.get(0));
            cache.put(key, new Object[]{n, System.currentTimeMillis()});
            return n;
        } catch (Exception e) {
            throw new IllegalStateException("Compliance parameter unreadable: " + key);
        }
    }

    public double number(String key) {
        return get(key).asDouble();
    }

    /** Minor-unit amount for a currency from a {"USD":..,"ZWG":..} parameter; null if not set for it. */
    public Long perCurrency(String key, String currency) {
        JsonNode n = get(key);
        return n.has(currency) ? n.get(currency).asLong() : null;
    }

    public List<Map<String, Object>> all() {
        return jdbc.queryForList("SELECT * FROM compliance_parameters ORDER BY category, param_key");
    }

    public void evict() {
        cache.clear();
    }

    @Transactional
    public Map<String, Object> requestChange(String key, JsonNode value, boolean confirm, String source, String reason, String by) {
        Map<String, Object> current = jdbc.queryForMap("SELECT * FROM compliance_parameters WHERE param_key = ?", key);
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("key", key);
        change.put("value", value == null ? current.get("value_json") : value.toString());
        change.put("confirm", confirm);
        change.put("source", source == null || source.isBlank() ? current.get("source") : source);
        change.put("fromVersion", current.get("version"));
        try {
            json.readTree((String) change.get("value"));
            String payload = json.writeValueAsString(change);
            ApprovalStore.Approval a = jdbc.execute((ConnectionCallback<ApprovalStore.Approval>) c ->
                    ApprovalStore.request(c, "COMPLIANCE_PARAMETER_CHANGE", "compliance_parameter:" + key, payload, reason, ComplianceService.ROLE, by, 72));
            audit.record(by, "PARAMETER_CHANGE_REQUESTED", "compliance_parameter:" + key, payload);
            return Map.of("approvalId", a.id(), "approvalRef", a.ref(), "status", a.status(), "change", change);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("value must be JSON");
        }
    }

    @Transactional
    public Map<String, Object> decide(long approvalId, boolean approve, String note, String by) {
        ApprovalStore.Approval a = jdbc.execute((ConnectionCallback<ApprovalStore.Approval>) c ->
                ApprovalStore.decide(c, approvalId, approve, by, Set.of(ComplianceService.ROLE), note));
        if (!"COMPLIANCE_PARAMETER_CHANGE".equals(a.actionType())) throw new IllegalStateException("not a parameter change");
        if (approve) {
            try {
                JsonNode ch = json.readTree(a.payload());
                boolean confirm = ch.path("confirm").asBoolean();
                LocalDateTime now = LocalDateTime.now();
                int n = jdbc.update("UPDATE compliance_parameters SET value_json = ?, source = ?, version = version + 1, updated_by = ?, updated_at = ?, "
                                + "status = IF(?, 'CONFIRMED', status), confirmed_by = IF(?, ?, confirmed_by), confirmed_at = IF(?, ?, confirmed_at) "
                                + "WHERE param_key = ? AND version = ?",
                        ch.path("value").asText(), ch.path("source").asText(), a.requestedBy() + " / " + by, Timestamp.valueOf(now),
                        confirm, confirm, a.requestedBy() + " / " + by, confirm, Timestamp.valueOf(now), ch.path("key").asText(), ch.path("fromVersion").asInt());
                if (n == 0) throw new IllegalStateException("The parameter changed since this request was made; request again");
                cache.remove(ch.path("key").asText());
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
        audit.record(by, approve ? "PARAMETER_CHANGE_APPROVED" : "PARAMETER_CHANGE_REJECTED", a.subjectRef(), a.payload());
        return Map.of("approvalId", a.id(), "status", a.status());
    }
}
