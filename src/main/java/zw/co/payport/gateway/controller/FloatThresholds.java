package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.Web;
import zw.co.payport.gateway.risk.ComplianceAudit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimum float per provider account (e.g. NETONE_USD, or ESOL:ESOLUTIONS_USD for the bill-payment wallets): below it the Float Balances page and the dashboard warn.
 * Kept in platform_settings (key float_threshold_<PROVIDER>_<CCY>) so they survive restarts.
 */
@RestController
@Component
public class FloatThresholds {

    public static final List<String> ACCOUNTS = List.of("NETONE_USD", "NETONE_ZWG", "ECONET_USD", "ECONET_ZWG", "TELECEL_USD", "TELECEL_ZWG");

    private final JdbcTemplate jdbc;
    private final ComplianceAudit audit;

    public FloatThresholds(JdbcTemplate jdbc, ComplianceAudit audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static String key(String account) {
        return "float_threshold_" + account;
    }

    public Map<String, Double> all() {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String a : ACCOUNTS) out.put(a, null);
        for (Map<String, Object> r : jdbc.queryForList("SELECT setting_key, setting_value FROM platform_settings WHERE setting_key LIKE 'float\\_threshold\\_%'")) {
            String acct = ((String) r.get("setting_key")).substring("float_threshold_".length());
            try { out.put(acct, Double.parseDouble(String.valueOf(r.get("setting_value")))); } catch (NumberFormatException ignored) { /* skip */ }
        }
        return out;
    }

    public Map<String, Double> save(Map<String, Object> body, String by) {
        if (body != null) {
            for (Map.Entry<String, Object> e : body.entrySet()) {
                String acct = e.getKey().trim().toUpperCase();
                if (!acct.matches("[A-Z0-9_:]{3,60}")) throw new IllegalArgumentException("Unknown float account " + e.getKey());
                Object v = e.getValue();
                if (v == null || String.valueOf(v).isBlank()) {
                    jdbc.update("DELETE FROM platform_settings WHERE setting_key = ?", key(acct));
                    continue;
                }
                double d;
                try { d = Double.parseDouble(String.valueOf(v)); } catch (NumberFormatException x) { throw new IllegalArgumentException("Enter an amount for " + acct); }
                if (d < 0) throw new IllegalArgumentException("A minimum cannot be negative");
                jdbc.update("INSERT INTO platform_settings (setting_key, setting_value, updated_at) VALUES (?, ?, NOW()) "
                        + "ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW()", key(acct), String.valueOf(d));
            }
        }
        Map<String, Double> now = all();
        audit.record(by, "FLOAT_THRESHOLDS_CHANGED", "platform_settings:float_thresholds", now.toString());
        return now;
    }

    @GetMapping("/api/v1/admin/float-thresholds")
    @PreAuthorize("hasAnyRole('OPS','ADMIN','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Double>>> get(HttpServletRequest req) {
        return Web.run(this::all, req);
    }

    @PutMapping("/api/v1/admin/float-thresholds")
    @PreAuthorize("hasAnyRole('OPS','ADMIN','FINANCE')")
    public ResponseEntity<ApiResponse<Map<String, Double>>> put(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        return Web.run(() -> save(body, Web.staff()), req);
    }
}
