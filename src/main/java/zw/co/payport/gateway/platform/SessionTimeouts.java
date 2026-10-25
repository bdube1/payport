package zw.co.payport.gateway.platform;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.risk.ComplianceAudit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inactivity time-outs per portal (owner, 2026-10-25): after this many minutes without activity a portal or the
 * app signs the person out and returns to its login page. Read by every portal at start-up; set by administrators.
 */
@RestController
public class SessionTimeouts {

    public static final List<String> PORTALS = List.of("ops", "merchant", "agent", "consumer", "mobile");
    private static final Map<String, Integer> DEFAULTS = Map.of("ops", 15, "merchant", 15, "agent", 15, "consumer", 10, "mobile", 5);
    static final int MIN = 1, MAX = 1440;

    private final JdbcTemplate jdbc;
    private final ComplianceAudit audit;

    public SessionTimeouts(JdbcTemplate jdbc, ComplianceAudit audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static String key(String portal) {
        return "session_timeout_" + portal + "_minutes";
    }

    public Map<String, Integer> all() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String p : PORTALS) {
            Integer v = DEFAULTS.get(p);
            try {
                List<String> rows = jdbc.queryForList("SELECT setting_value FROM platform_settings WHERE setting_key = ?", String.class, key(p));
                if (!rows.isEmpty()) v = Integer.parseInt(rows.get(0).trim());
            } catch (RuntimeException ignored) { /* keep the default */ }
            out.put(p, Math.max(MIN, Math.min(MAX, v)));
        }
        return out;
    }

    public Map<String, Integer> save(Map<String, Object> body, String by) {
        for (String p : PORTALS) {
            if (body == null || !body.containsKey(p) || body.get(p) == null) continue;
            int v;
            try { v = (int) Math.round(Double.parseDouble(String.valueOf(body.get(p)))); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Enter whole minutes for " + p); }
            if (v < MIN || v > MAX) throw new IllegalArgumentException("Time-outs are between " + MIN + " and " + MAX + " minutes");
            jdbc.update("INSERT INTO platform_settings (setting_key, setting_value, updated_at) VALUES (?, ?, NOW()) "
                    + "ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW()", key(p), String.valueOf(v));
        }
        Map<String, Integer> now = all();
        audit.record(by, "SESSION_TIMEOUTS_CHANGED", "platform_settings:session_timeouts", now.toString());
        return now;
    }

    /** Public: every portal and the app read their own time-out before anyone signs in. */
    @GetMapping("/api/v1/session-timeouts")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> get(HttpServletRequest req) {
        return Web.run(this::all, req);
    }

    @PutMapping("/api/v1/admin/session-timeouts")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> put(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        return Web.run(() -> save(body, Web.staff()), req);
    }
}
