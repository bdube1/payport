package zw.co.payport.gateway.commerce;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.entity.User;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Sign in with PayPort" for the PayPort Store (owner, 31 Oct 2026). A signed-in customer gets a one-time code that the
 * store exchanges, server to server, for who they are. The code lasts two minutes, works once and is stored only as a
 * hash; the address the customer returns to is always the store's own (never taken from the request).
 */
@Service
public class StoreSignIn {

    static final int MINUTES = 2;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final String storeUrl;

    public StoreSignIn(JdbcTemplate jdbc, @Value("${payport.commerce.public-url:https://store.payport.africa}") String storeUrl) {
        this.jdbc = jdbc;
        this.storeUrl = storeUrl == null ? "" : storeUrl.replaceAll("/+$", "");
    }

    /** next: a path on the store to return to ("/s/mais-kitchen"); anything else goes to the store's account page. */
    public Map<String, Object> code(User u, String next) {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        String code = HexFormat.of().formatHex(b);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO store_login_codes (code_hash, user_id, expires_at, created_at) VALUES (?,?,?,?)",
                sha(code), u.getId(), Timestamp.valueOf(LocalDateTime.now().plusMinutes(MINUTES)), now);
        String path = next != null && next.matches("/[A-Za-z0-9/_.?=&%-]{0,200}") && !next.startsWith("//") ? next : "/me";
        return Map.of("url", storeUrl + "/auth/callback?code=" + code + "&next=" + URLEncoder.encode(path, StandardCharsets.UTF_8));
    }

    /** The store's side: who the code belongs to, once. */
    @Transactional
    public Map<String, Object> redeem(String code) {
        if (code == null || !code.matches("[0-9a-f]{48}")) throw new IllegalArgumentException("Sign in again");
        String h = sha(code);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT c.user_id, u.full_name, u.phone, u.is_active FROM store_login_codes c JOIN users u ON u.id = c.user_id "
                + "WHERE c.code_hash = ? AND c.used_at IS NULL AND c.expires_at > ? FOR UPDATE", h, Timestamp.valueOf(LocalDateTime.now()));
        if (rows.isEmpty() || !Boolean.TRUE.equals(rows.get(0).get("is_active")) && !Integer.valueOf(1).equals(rows.get(0).get("is_active")))
            throw new IllegalArgumentException("Sign in again");
        jdbc.update("UPDATE store_login_codes SET used_at = ? WHERE code_hash = ?", Timestamp.valueOf(LocalDateTime.now()), h);
        jdbc.update("DELETE FROM store_login_codes WHERE expires_at < ?", Timestamp.valueOf(LocalDateTime.now().minusDays(1)));
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("userId", ((Number) rows.get(0).get("user_id")).longValue());
        v.put("name", rows.get(0).get("full_name"));
        v.put("phone", rows.get(0).get("phone"));
        return v;
    }

    static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
