package zw.co.payport.gateway.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PayPort Intelligence: the switches for each feature and the record of what each model said (ai_predictions), so
 * every model is measured on real outcomes before anyone relies on it. Rules: every feature is off until an
 * administrator switches it on; models suggest and explain, people decide (none of them can request or approve a
 * reversal, block a payment or contact a customer); nothing leaves PayPort's servers.
 */
@Service
public class Intelligence {

    public static final String TRIAGE = "COMPLAINT_TRIAGE", HELPER = "INVESTIGATION_HELPER", FRAUD = "FRAUD_SHADOW";
    public static final Set<String> FEATURES = Set.of(TRIAGE, HELPER, FRAUD);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public Intelligence(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean on(String feature) {
        List<Boolean> v = jdbc.queryForList("SELECT enabled FROM ai_features WHERE feature = ?", Boolean.class, feature);
        return !v.isEmpty() && Boolean.TRUE.equals(v.get(0));
    }

    public void require(String feature) {
        if (!on(feature)) throw new IllegalStateException("This PayPort Intelligence feature is switched off");
    }

    public List<Map<String, Object>> features() {
        return jdbc.queryForList("SELECT feature, enabled, updated_by, updated_at FROM ai_features ORDER BY feature");
    }

    public Map<String, Object> set(String feature, boolean enabled, String by) {
        if (!FEATURES.contains(feature)) throw new IllegalArgumentException("No such feature");
        jdbc.update("UPDATE ai_features SET enabled = ?, updated_by = ?, updated_at = ? WHERE feature = ?", enabled, by, Timestamp.valueOf(LocalDateTime.now()), feature);
        return jdbc.queryForMap("SELECT feature, enabled, updated_by, updated_at FROM ai_features WHERE feature = ?", feature);
    }

    /** One prediction per subject and feature (a repeat is ignored). */
    public void predict(String feature, String subject, int version, String label, double score, Map<String, Object> detail) {
        try {
            jdbc.update("INSERT IGNORE INTO ai_predictions (feature, subject_ref, model_version, label, score, detail, created_at) VALUES (?,?,?,?,?,?,?)",
                    feature, subject, version, label, score, detail == null ? null : json.writeValueAsString(detail), Timestamp.valueOf(LocalDateTime.now()));
        } catch (Exception e) {
            // a prediction that is not recorded only means it is not measured
        }
    }

    public void outcome(String feature, String subject, String outcome) {
        jdbc.update("UPDATE ai_predictions SET outcome = ?, outcome_at = ? WHERE feature = ? AND subject_ref = ?",
                outcome, Timestamp.valueOf(LocalDateTime.now()), feature, subject);
    }

    public int saveModel(String feature, int samples, Map<String, Object> metrics) {
        Integer v = jdbc.queryForObject("SELECT COALESCE(MAX(version), 0) + 1 FROM ai_models WHERE feature = ?", Integer.class, feature);
        try {
            jdbc.update("INSERT INTO ai_models (feature, version, trained_at, samples, metrics) VALUES (?,?,?,?,?)",
                    feature, v, Timestamp.valueOf(LocalDateTime.now()), samples, json.writeValueAsString(metrics));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return v;
    }

    public Map<String, Object> latestModel(String feature) {
        List<Map<String, Object>> m = jdbc.queryForList("SELECT version, trained_at, samples, metrics FROM ai_models WHERE feature = ? ORDER BY version DESC LIMIT 1", feature);
        if (m.isEmpty()) return null;
        Map<String, Object> out = new LinkedHashMap<>(m.get(0));
        try {
            Object raw = out.get("metrics");
            out.put("metrics", json.readValue(raw instanceof byte[] b ? new String(b, java.nio.charset.StandardCharsets.UTF_8) : String.valueOf(raw), Map.class));
        } catch (Exception e) {
            out.put("metrics", Map.of());
        }
        return out;
    }
}
