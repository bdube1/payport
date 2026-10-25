package zw.co.payport.gateway.platform;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.risk.ComplianceAudit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The account each biller's health check looks up (owner, 2026-10-25). A provider is Operational when the lookup is
 * answered, so the number must be a real account at that biller. Changed in Provider Tools; kept in platform_settings
 * as health_lookup_<PROVIDER>; a blank value goes back to the default.
 */
@Component
public class HealthLookups {

    public static final Map<String, String> DEFAULTS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ZESA", "14309986827");
        m.put("BULAWAYO", "44900607");
        m.put("NYARADZO", "SCPK301027");
        m.put("MOONLIGHT", "6764097");
        m.put("ZOL", "2401427");
        DEFAULTS = java.util.Collections.unmodifiableMap(m);
    }

    private final JdbcTemplate jdbc;
    private final ComplianceAudit audit;

    public HealthLookups(JdbcTemplate jdbc, ComplianceAudit audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static String key(String provider) {
        return "health_lookup_" + provider;
    }

    public Map<String, String> all() {
        Map<String, String> out = new LinkedHashMap<>(DEFAULTS);
        try {
            for (Map<String, Object> r : jdbc.queryForList("SELECT setting_key, setting_value FROM platform_settings WHERE setting_key LIKE 'health\\_lookup\\_%'")) {
                String p = ((String) r.get("setting_key")).substring("health_lookup_".length());
                Object v = r.get("setting_value");
                if (DEFAULTS.containsKey(p) && v != null && !String.valueOf(v).isBlank()) out.put(p, String.valueOf(v).trim());
            }
        } catch (RuntimeException e) {
            // The defaults still work when the settings cannot be read.
        }
        return out;
    }

    public String number(String provider) {
        return all().get(provider);
    }

    public Map<String, String> save(Map<String, Object> body, String by) {
        if (body != null) {
            for (Map.Entry<String, Object> e : body.entrySet()) {
                String p = e.getKey().trim().toUpperCase();
                if (!DEFAULTS.containsKey(p)) throw new IllegalArgumentException("No lookup check for " + e.getKey());
                String v = e.getValue() == null ? "" : String.valueOf(e.getValue()).trim();
                if (v.isEmpty()) {
                    jdbc.update("DELETE FROM platform_settings WHERE setting_key = ?", key(p));
                    continue;
                }
                if (!v.matches("[A-Za-z0-9-]{3,40}")) throw new IllegalArgumentException("Enter a valid account or meter number for " + p);
                jdbc.update("INSERT INTO platform_settings (setting_key, setting_value, updated_at) VALUES (?, ?, NOW()) "
                        + "ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW()", key(p), v);
            }
        }
        Map<String, String> now = all();
        audit.record(by, "HEALTH_LOOKUPS_CHANGED", "platform_settings:health_lookups", now.toString());
        return now;
    }
}
