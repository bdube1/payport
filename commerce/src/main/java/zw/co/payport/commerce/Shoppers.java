package zw.co.payport.commerce;

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
 * Payport Store users (owner, 31 Oct 2026): the store's own accounts, separate from Payport app customers. They
 * register with their name, phone (and email if they like) and a password, sign in with either, and reset the password
 * with a code sent to their phone. The store keeps a 30-day session (a random token; only its hash is stored). Orders,
 * saved addresses, favourite stores and loyalty points belong to the account.
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

    static final int MAX_FAILED = 5, LOCK_MINUTES = 15, RESET_MINUTES = 10;

    static String email(Object o) {
        String e = o == null ? null : String.valueOf(o).trim().toLowerCase(java.util.Locale.ROOT);
        if (e == null || e.isEmpty()) return null;
        if (e.length() > 190 || !e.matches("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}")) throw new IllegalArgumentException("Enter a valid email address.");
        return e;
    }

    /** +263771234567 from 0771234567, 263771234567 or +263 77 123 4567. */
    static String phone(Object o) {
        String d = o == null ? "" : String.valueOf(o).replaceAll("[^0-9+]", "");
        if (d.isEmpty()) return null;
        if (d.startsWith("+")) d = d.substring(1);
        if (d.startsWith("0")) d = "263" + d.substring(1);
        if (!d.matches("[0-9]{11,15}")) throw new IllegalArgumentException("Enter a valid phone number.");
        return "+" + d;
    }

    /** A new Payport Store account: name, email and/or phone, password. Signs in at once. */
    public Shopper register(String name, String emailIn, String phoneIn, String password, HttpServletResponse res) {
        String n = Shop.text(name, 120, true, "name");
        String e = email(emailIn), p = phone(phoneIn);
        if (e == null && p == null) throw new IllegalArgumentException("Enter your email address or phone number.");
        if (p == null) throw new IllegalArgumentException("Enter your phone number (for order updates and to reset your password).");
        Passwords.check(password);
        Timestamp now = Shop.now();
        try {
            jdbc.update("INSERT INTO shoppers (name, email, phone, password_hash, created_at, updated_at) VALUES (?,?,?,?,?,?)", n, e, p, Passwords.hash(password), now, now);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new IllegalStateException("There is already an account with that email or phone number. Sign in instead.");
        }
        long id = jdbc.queryForObject("SELECT id FROM shoppers WHERE phone = ?", Long.class, p);
        startSession(id, res);
        return new Shopper(id, n, p);
    }

    /** Email or phone, and password. Five wrong passwords lock the account for 15 minutes. */
    public Shopper login(String who, String password, HttpServletResponse res) {
        String w = who == null ? "" : who.trim();
        List<Map<String, Object>> r;
        if (w.contains("@")) r = jdbc.queryForList("SELECT * FROM shoppers WHERE email = ?", email(w));
        else {
            String p;
            try { p = phone(w); } catch (IllegalArgumentException e) { p = null; }
            r = p == null ? List.of() : jdbc.queryForList("SELECT * FROM shoppers WHERE phone = ?", p);
        }
        String wrong = "The email or phone number, or the password, is not right.";
        if (r.isEmpty()) {
            Passwords.matches(password == null ? "" : password, Passwords.hash("timing"));   // same time whether or not the account exists
            throw new IllegalArgumentException(wrong);
        }
        Map<String, Object> s = r.get(0);
        long id = ((Number) s.get("id")).longValue();
        if (s.get("locked_until") != null && Shop.time(s.get("locked_until")).isAfter(LocalDateTime.now()))
            throw new IllegalStateException("Too many wrong passwords. Try again in a few minutes, or reset your password.");
        if (!Passwords.matches(password, (String) s.get("password_hash"))) {
            jdbc.update("UPDATE shoppers SET failed_logins = failed_logins + 1, locked_until = CASE WHEN failed_logins + 1 >= ? THEN ? ELSE locked_until END WHERE id = ?",
                    MAX_FAILED, Timestamp.valueOf(LocalDateTime.now().plusMinutes(LOCK_MINUTES)), id);
            throw new IllegalArgumentException(wrong);
        }
        jdbc.update("UPDATE shoppers SET failed_logins = 0, locked_until = NULL WHERE id = ?", id);
        startSession(id, res);
        return new Shopper(id, (String) s.get("name"), (String) s.get("phone"));
    }

    void startSession(long id, HttpServletResponse res) {
        String token = Shop.token() + Shop.token();
        jdbc.update("INSERT INTO shopper_sessions (token_hash, shopper_id, expires_at, created_at) VALUES (?,?,?,?)",
                Shop.sha256(token), id, Timestamp.valueOf(LocalDateTime.now().plusDays(DAYS)), Shop.now());
        res.addHeader("Set-Cookie", cookie(token, DAYS * 86400L).toString());
    }

    /** Forgot password: a 6-digit code by SMS to the account's phone (the answer is the same whether or not it exists). */
    public void sendReset(String phoneIn) {
        String p = phone(phoneIn);
        if (p == null) throw new IllegalArgumentException("Enter your phone number.");
        List<Long> ids = jdbc.queryForList("SELECT id FROM shoppers WHERE phone = ?", Long.class, p);
        if (ids.isEmpty()) return;
        String code = String.format("%06d", new java.security.SecureRandom().nextInt(1_000_000));
        jdbc.update("UPDATE shopper_resets SET used_at = ? WHERE shopper_id = ? AND used_at IS NULL", Shop.now(), ids.get(0));
        jdbc.update("INSERT INTO shopper_resets (shopper_id, code_hash, expires_at, created_at) VALUES (?,?,?,?)", ids.get(0), Shop.sha256(p + ":" + code),
                Timestamp.valueOf(LocalDateTime.now().plusMinutes(RESET_MINUTES)), Shop.now());
        try {
            psp.message(Map.of("to", "STORE_USER", "phone", p, "text", "Your Payport Store code is " + code + ". It works for 10 minutes. Do not share it."));
        } catch (RuntimeException e) {
            throw new IllegalStateException("The code could not be sent just now. Please try again in a few minutes.");
        }
    }

    /** The code and a new password: signed in, and every other session ends. */
    public Shopper reset(String phoneIn, String code, String password, HttpServletResponse res) {
        String p = phone(phoneIn);
        Passwords.check(password);
        String bad = "That code is not right or has expired.";
        if (p == null || code == null || !code.trim().matches("[0-9]{6}")) throw new IllegalArgumentException(bad);
        List<Map<String, Object>> r = jdbc.queryForList("SELECT x.id, x.code_hash, x.attempts, s.id AS sid, s.name FROM shopper_resets x JOIN shoppers s ON s.id = x.shopper_id "
                + "WHERE s.phone = ? AND x.used_at IS NULL AND x.expires_at > ? ORDER BY x.id DESC LIMIT 1", p, Shop.now());
        if (r.isEmpty() || ((Number) r.get(0).get("attempts")).intValue() >= 5) throw new IllegalArgumentException(bad);
        Map<String, Object> x = r.get(0);
        if (!java.security.MessageDigest.isEqual(Shop.sha256(p + ":" + code.trim()).getBytes(), String.valueOf(x.get("code_hash")).getBytes())) {
            jdbc.update("UPDATE shopper_resets SET attempts = attempts + 1 WHERE id = ?", x.get("id"));
            throw new IllegalArgumentException(bad);
        }
        long id = ((Number) x.get("sid")).longValue();
        jdbc.update("UPDATE shopper_resets SET used_at = ? WHERE id = ?", Shop.now(), x.get("id"));
        jdbc.update("UPDATE shoppers SET password_hash = ?, failed_logins = 0, locked_until = NULL, updated_at = ? WHERE id = ?", Passwords.hash(password), Shop.now(), id);
        jdbc.update("DELETE FROM shopper_sessions WHERE shopper_id = ?", id);
        startSession(id, res);
        return new Shopper(id, (String) x.get("name"), p);
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

    /** The account's own orders (placed while signed in). */
    public List<Map<String, Object>> orders(Shopper s) {
        return jdbc.queryForList("SELECT o.*, st.name AS store_name, st.slug AS store_slug FROM orders o JOIN stores st ON st.id = o.store_id "
                + "WHERE o.shopper_id = ? ORDER BY o.id DESC LIMIT 50", s.id());
    }

    /** One of the shopper's orders, or none. */
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

    /** Saved products (the heart on a product page). */
    public boolean saved(Shopper s, long productId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM saved_products WHERE shopper_id = ? AND product_id = ?", Integer.class, s.id(), productId);
        return n != null && n > 0;
    }

    public boolean toggleSaved(Shopper s, long productId) {
        if (jdbc.update("DELETE FROM saved_products WHERE shopper_id = ? AND product_id = ?", s.id(), productId) > 0) return false;
        jdbc.update("INSERT IGNORE INTO saved_products (shopper_id, product_id, created_at) VALUES (?,?,?)", s.id(), productId, Shop.now());
        return true;
    }

    public List<Map<String, Object>> savedProducts(Shopper s) {
        return jdbc.queryForList("SELECT p.name, p.slug, st.slug AS store_slug, st.name AS store FROM saved_products sp JOIN products p ON p.id = sp.product_id "
                + "JOIN stores st ON st.id = p.store_id WHERE sp.shopper_id = ? AND p.status = 'ACTIVE' ORDER BY sp.created_at DESC LIMIT 50", s.id());
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
