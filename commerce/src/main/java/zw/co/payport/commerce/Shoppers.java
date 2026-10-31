package zw.co.payport.commerce;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shopper accounts (owner, 31 Oct 2026): "Sign in with PayPort". The store keeps its own 30-day session (a random
 * token, only its hash stored) once PayPort Payments has said who the shopper is. Orders, saved addresses and favourite
 * stores belong to the signed-in shopper; orders placed earlier with the same phone number are shown too.
 */
@Service
public class Shoppers {

    public static final String COOKIE = "pp_me";
    static final int DAYS = 30;

    public record Shopper(long id, String name, String phone) {
        public String firstName() {
            return name == null || name.isBlank() ? "there" : name.trim().split("\\s+")[0];
        }
    }

    private final JdbcTemplate jdbc;
    private final Psp psp;
    private final Settings settings;

    public Shoppers(JdbcTemplate jdbc, Psp psp, Settings settings) {
        this.jdbc = jdbc;
        this.psp = psp;
        this.settings = settings;
    }

    /** Exchanges PayPort's one-time code; sets the store's own session cookie. */
    public Shopper signIn(String code, HttpServletResponse res) {
        JsonNode who = psp.customerCode(code);
        long userId = who.path("userId").asLong(0);
        if (userId == 0) throw new IllegalArgumentException("Please sign in again");
        String name = Shop.clip(who.path("name").asText(null), 120), phone = Shop.clip(who.path("phone").asText(null), 20);
        Timestamp now = Shop.now();
        jdbc.update("INSERT INTO shoppers (psp_user_id, name, phone, created_at, updated_at) VALUES (?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE name = VALUES(name), phone = VALUES(phone), updated_at = VALUES(updated_at)", userId, name, phone, now, now);
        long id = jdbc.queryForObject("SELECT id FROM shoppers WHERE psp_user_id = ?", Long.class, userId);
        String token = Shop.token() + Shop.token();
        jdbc.update("INSERT INTO shopper_sessions (token_hash, shopper_id, expires_at, created_at) VALUES (?,?,?,?)",
                Shop.sha256(token), id, Timestamp.valueOf(LocalDateTime.now().plusDays(DAYS)), now);
        res.addHeader("Set-Cookie", cookie(token, DAYS * 86400L).toString());
        return new Shopper(id, name, phone);
    }

    ResponseCookie cookie(String value, long maxAge) {
        return ResponseCookie.from(COOKIE, value).httpOnly(true).secure(settings.publicUrl.startsWith("https://")).sameSite("Lax").path("/").maxAge(maxAge).build();
    }

    public Optional<Shopper> current(String token) {
        if (token == null || token.isBlank() || token.length() > 100) return Optional.empty();
        return jdbc.queryForList("SELECT s.id, s.name, s.phone FROM shopper_sessions x JOIN shoppers s ON s.id = x.shopper_id WHERE x.token_hash = ? AND x.expires_at > ?",
                Shop.sha256(token), Shop.now()).stream().findFirst()
                .map(r -> new Shopper(((Number) r.get("id")).longValue(), (String) r.get("name"), (String) r.get("phone")));
    }

    public void signOut(String token, HttpServletResponse res) {
        if (token != null) jdbc.update("DELETE FROM shopper_sessions WHERE token_hash = ?", Shop.sha256(token));
        res.addHeader("Set-Cookie", cookie("", 0).toString());
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    public void tidy() {
        jdbc.update("DELETE FROM shopper_sessions WHERE expires_at < ?", Shop.now());
    }

    // ─── orders, addresses, favourites ───────────────────────────────────────

    /** The order is the shopper's (and the address is remembered for next time). */
    public void claim(Shopper s, String orderRef, String address) {
        jdbc.update("UPDATE orders SET shopper_id = ? WHERE order_ref = ? AND shopper_id IS NULL", s.id(), orderRef);
        if (address != null && !address.isBlank()) {
            jdbc.update("INSERT INTO shopper_addresses (shopper_id, address, used_at) VALUES (?,?,?) ON DUPLICATE KEY UPDATE used_at = VALUES(used_at)",
                    s.id(), Shop.clip(address.trim(), 300), Shop.now());
            jdbc.update("DELETE FROM shopper_addresses WHERE shopper_id = ? AND id NOT IN (SELECT id FROM (SELECT id FROM shopper_addresses WHERE shopper_id = ? "
                    + "ORDER BY used_at DESC LIMIT 5) keep)", s.id(), s.id());
        }
    }

    public List<Map<String, Object>> orders(Shopper s) {
        String d = s.phone() == null ? "" : s.phone().replaceAll("\\D", "");
        String like = d.length() >= 9 ? "%" + d.substring(d.length() - 9) : "~none~";
        return jdbc.queryForList("SELECT o.*, st.name AS store_name, st.slug AS store_slug FROM orders o JOIN stores st ON st.id = o.store_id "
                + "WHERE o.shopper_id = ? OR (o.shopper_id IS NULL AND o.customer_phone LIKE ?) ORDER BY o.id DESC LIMIT 50", s.id(), like);
    }

    /** One of the shopper's orders (theirs by account or by phone), or none. */
    public Optional<Map<String, Object>> order(Shopper s, String ref) {
        return orders(s).stream().filter(o -> ref.equals(o.get("order_ref"))).findFirst();
    }

    public List<Map<String, Object>> addresses(Shopper s) {
        return jdbc.queryForList("SELECT id, address FROM shopper_addresses WHERE shopper_id = ? ORDER BY used_at DESC", s.id());
    }

    public void removeAddress(Shopper s, long id) {
        jdbc.update("DELETE FROM shopper_addresses WHERE id = ? AND shopper_id = ?", id, s.id());
    }

    public boolean favourite(Shopper s, long storeId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM favourites WHERE shopper_id = ? AND store_id = ?", Integer.class, s.id(), storeId);
        return n != null && n > 0;
    }

    public boolean toggleFavourite(Shopper s, long storeId) {
        if (jdbc.update("DELETE FROM favourites WHERE shopper_id = ? AND store_id = ?", s.id(), storeId) > 0) return false;
        jdbc.update("INSERT IGNORE INTO favourites (shopper_id, store_id, created_at) VALUES (?,?,?)", s.id(), storeId, Shop.now());
        return true;
    }

    public List<Map<String, Object>> favourites(Shopper s) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT st.name, st.slug, st.business_type FROM favourites f JOIN stores st ON st.id = f.store_id "
                + "WHERE f.shopper_id = ? AND st.status = 'OPEN' ORDER BY f.created_at DESC", s.id())) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("name", r.get("name"));
            v.put("url", "/s/" + r.get("slug"));
            v.put("type", Storefront.SHORT.getOrDefault(String.valueOf(r.get("business_type")), "Other"));
            out.add(v);
        }
        return out;
    }
}
