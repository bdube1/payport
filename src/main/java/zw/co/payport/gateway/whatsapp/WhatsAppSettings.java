package zw.co.payport.gateway.whatsapp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.platform.SecretBox;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The WhatsApp Cloud API connection, set from the ops console (whatsapp_settings, one row): the
 * business number, Meta's phone number id and WhatsApp Business Account id, and the three secrets
 * (access token, app secret, webhook verify token), which are stored encrypted (SecretBox) and never
 * shown again. A value not set in ops falls back to the application properties.
 */
@Component
public class WhatsAppSettings {

    public record Values(String businessNumber, String phoneNumberId, String wabaId, String accessToken, String appSecret, String verifyToken, String apiBase) {}

    private final JdbcTemplate jdbc;
    private final SecretBox box;
    private final Values defaults;
    private volatile Values current;
    private volatile long loadedAt;

    public WhatsAppSettings(JdbcTemplate jdbc, SecretBox box,
                            @Value("${payport.whatsapp.phone-number-id:}") String phoneNumberId,
                            @Value("${payport.whatsapp.access-token:}") String accessToken,
                            @Value("${payport.whatsapp.app-secret:}") String appSecret,
                            @Value("${payport.whatsapp.verify-token:}") String verifyToken,
                            @Value("${payport.whatsapp.api-base:https://graph.facebook.com/v20.0}") String apiBase) {
        this.jdbc = jdbc;
        this.box = box;
        this.defaults = new Values("", phoneNumberId, "", accessToken, appSecret, verifyToken, apiBase);
    }

    public Values get() {
        Values v = current;
        if (v == null || System.currentTimeMillis() - loadedAt > 30_000) v = reload();
        return v;
    }

    public synchronized Values reload() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM whatsapp_settings WHERE id = 1");
        Map<String, Object> r = rows.isEmpty() ? Map.of() : rows.get(0);
        Values v = new Values(
                or((String) r.get("business_number"), defaults.businessNumber()),
                or((String) r.get("phone_number_id"), defaults.phoneNumberId()),
                or((String) r.get("waba_id"), defaults.wabaId()),
                or(open((String) r.get("access_token")), defaults.accessToken()),
                or(open((String) r.get("app_secret")), defaults.appSecret()),
                or(open((String) r.get("verify_token")), defaults.verifyToken()),
                or((String) r.get("api_base"), defaults.apiBase()));
        current = v;
        loadedAt = System.currentTimeMillis();
        return v;
    }

    /** What ops sees: identifiers, and only whether each secret is set. */
    public Map<String, Object> view() {
        Values v = get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("businessNumber", v.businessNumber());
        out.put("phoneNumberId", v.phoneNumberId());
        out.put("wabaId", v.wabaId());
        out.put("apiBase", v.apiBase());
        out.put("accessTokenSet", !v.accessToken().isBlank());
        out.put("appSecretSet", !v.appSecret().isBlank());
        out.put("verifyTokenSet", !v.verifyToken().isBlank());
        out.put("webhookPath", "/api/v1/whatsapp/webhook");
        jdbc.queryForList("SELECT updated_by, updated_at FROM whatsapp_settings WHERE id = 1").stream().findFirst().ifPresent(r -> {
            out.put("updatedBy", r.get("updated_by"));
            out.put("updatedAt", r.get("updated_at"));
        });
        return out;
    }

    /** Blank or missing fields keep their current value; secrets are encrypted before they are stored. */
    public Map<String, Object> update(String businessNumber, String phoneNumberId, String wabaId, String accessToken, String appSecret,
                                      String verifyToken, String apiBase, String by) {
        if (businessNumber != null && !businessNumber.isBlank() && !businessNumber.replaceAll("[\\s-]", "").matches("\\+?\\d{9,15}")) {
            throw new IllegalArgumentException("The business number should be a phone number with its country code, e.g. +263719183557");
        }
        if (phoneNumberId != null && !phoneNumberId.isBlank() && !phoneNumberId.trim().matches("\\d{5,30}")) {
            throw new IllegalArgumentException("The phone number ID is the number Meta shows under WhatsApp > API Setup");
        }
        if (apiBase != null && !apiBase.isBlank() && !apiBase.trim().startsWith("https://")) throw new IllegalArgumentException("The API address must start with https://");
        jdbc.update("INSERT INTO whatsapp_settings (id, updated_by, updated_at) VALUES (1, ?, ?) ON DUPLICATE KEY UPDATE updated_by = VALUES(updated_by), updated_at = VALUES(updated_at)",
                by, Timestamp.valueOf(LocalDateTime.now()));
        set("business_number", businessNumber == null || businessNumber.isBlank() ? null : normalise(businessNumber));
        set("phone_number_id", blank(phoneNumberId));
        set("waba_id", blank(wabaId));
        set("api_base", blank(apiBase));
        set("access_token", blank(accessToken) == null ? null : box.seal(accessToken.trim()));
        set("app_secret", blank(appSecret) == null ? null : box.seal(appSecret.trim()));
        set("verify_token", blank(verifyToken) == null ? null : box.seal(verifyToken.trim()));
        reload();
        return view();
    }

    private void set(String column, String value) {
        if (value != null) jdbc.update("UPDATE whatsapp_settings SET " + column + " = ? WHERE id = 1", value);
    }

    private String open(String sealed) {
        return sealed == null || sealed.isBlank() ? null : box.open(sealed);
    }

    private static String normalise(String n) {
        String d = n.replaceAll("[^0-9]", "");
        return "+" + d;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String or(String v, String d) {
        return v == null || v.isBlank() ? (d == null ? "" : d) : v;
    }
}
