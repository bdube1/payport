package zw.co.payport.commerce;

import org.springframework.dao.DuplicateKeyException;
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
 * Merchant growth tools (owner, 31 Oct 2026): sales reports, offers by text to customers who agreed to them, reminders
 * about carts left behind, and loyalty points. Offers and reminders go only to customers who ticked "send me offers"
 * for that store, at most one offer a day per store, each with a link to stop them.
 */
@Service
public class Marketing {

    static final int POINTS_PER_UNIT = 100;   // 100 points = 1.00 off
    static final long CAMPAIGN_GAP_HOURS = 24;

    private final JdbcTemplate jdbc;
    private final Growth growth;
    private final Settings settings;

    public Marketing(JdbcTemplate jdbc, Growth growth, Settings settings) {
        this.jdbc = jdbc;
        this.growth = growth;
        this.settings = settings;
    }

    static String phone9(String phone) {
        String d = phone == null ? "" : phone.replaceAll("\\D", "");
        return d.length() >= 9 ? d.substring(d.length() - 9) : null;
    }

    // ─── consent ─────────────────────────────────────────────────────────────

    public void consent(long merchantId, String phone, String name) {
        String p9 = phone9(phone);
        if (p9 == null) return;
        Timestamp now = Shop.now();
        try {
            jdbc.update("INSERT INTO marketing_consent (merchant_id, phone9, phone, name, consented_at, stop_token) VALUES (?,?,?,?,?,?)",
                    merchantId, p9, Shop.clip(phone, 20), Shop.clip(name, 120), now, Shop.code(32).toLowerCase());
        } catch (DuplicateKeyException e) {
            jdbc.update("UPDATE marketing_consent SET withdrawn_at = NULL, consented_at = ?, phone = ?, name = COALESCE(?, name) WHERE merchant_id = ? AND phone9 = ?",
                    now, Shop.clip(phone, 20), Shop.clip(name, 120), merchantId, p9);
        }
    }

    public boolean consented(long merchantId, String phone) {
        String p9 = phone9(phone);
        if (p9 == null) return false;
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM marketing_consent WHERE merchant_id = ? AND phone9 = ? AND withdrawn_at IS NULL", Integer.class, merchantId, p9);
        return n != null && n > 0;
    }

    /** The stop link: no more offers or reminders from that store. Returns the store's name. */
    public Optional<String> stop(String token) {
        if (token == null || !token.matches("[a-z0-9]{32}")) return Optional.empty();
        List<Map<String, Object>> r = jdbc.queryForList("SELECT c.id, s.name FROM marketing_consent c JOIN stores s ON s.merchant_id = c.merchant_id WHERE c.stop_token = ?", token);
        if (r.isEmpty()) return Optional.empty();
        jdbc.update("UPDATE marketing_consent SET withdrawn_at = COALESCE(withdrawn_at, ?) WHERE id = ?", Shop.now(), r.get(0).get("id"));
        return Optional.of(String.valueOf(r.get(0).get("name")));
    }

    public long audience(long merchantId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM marketing_consent WHERE merchant_id = ? AND withdrawn_at IS NULL", Long.class, merchantId);
        return n == null ? 0 : n;
    }

    // ─── offers by text ──────────────────────────────────────────────────────

    public List<Map<String, Object>> campaigns(long merchantId) {
        return jdbc.queryForList("SELECT id, text, recipients, created_at AS createdAt FROM campaigns WHERE merchant_id = ? ORDER BY id DESC LIMIT 100", merchantId);
    }

    /** Sends an offer to every customer who agreed (at most one a day per store). */
    public Map<String, Object> send(long merchantId, String text, String by) {
        String t = Shop.text(text, 120, true, "message");
        Integer recent = jdbc.queryForObject("SELECT COUNT(*) FROM campaigns WHERE merchant_id = ? AND created_at > ?", Integer.class, merchantId,
                Timestamp.valueOf(LocalDateTime.now().minusHours(CAMPAIGN_GAP_HOURS)));
        if (recent != null && recent > 0) throw new IllegalStateException("One offer a day: you can send the next one tomorrow");
        Map<String, Object> st = jdbc.queryForMap("SELECT name, slug FROM stores WHERE merchant_id = ?", merchantId);
        List<Map<String, Object>> to = jdbc.queryForList("SELECT phone, stop_token FROM marketing_consent WHERE merchant_id = ? AND withdrawn_at IS NULL LIMIT 450", merchantId);
        if (to.isEmpty()) throw new IllegalStateException("No customers have agreed to offers yet");
        jdbc.update("INSERT INTO campaigns (merchant_id, text, recipients, sent_by, created_at) VALUES (?,?,?,?,?)", merchantId, t, to.size(), by, Shop.now());
        for (Map<String, Object> r : to) {
            growth.textLater(merchantId, (String) r.get("phone"), st.get("name") + ": " + t + " " + settings.publicUrl + "/s/" + st.get("slug")
                    + " Stop: " + settings.publicUrl + "/u/" + r.get("stop_token"), "OFFER");
        }
        return Map.of("recipients", to.size());
    }

    // ─── carts left behind ───────────────────────────────────────────────────

    /** A signed-in shopper's cart left for 2 to 24 hours, at a store with reminders on, to a customer who agreed: one text. */
    @Scheduled(fixedDelayString = "${commerce.reminders-ms:900000}", initialDelay = 300_000)
    public void remindCarts() {
        for (Map<String, Object> c : jdbc.queryForList("SELECT c.id, c.store_id, s.merchant_id, s.name, s.slug, sh.phone, mc.stop_token FROM carts c "
                + "JOIN stores s ON s.id = c.store_id JOIN shoppers sh ON sh.id = c.shopper_id "
                + "JOIN marketing_consent mc ON mc.merchant_id = s.merchant_id AND mc.withdrawn_at IS NULL AND mc.phone9 = RIGHT(REGEXP_REPLACE(sh.phone, '[^0-9]', ''), 9) "
                + "WHERE s.cart_reminders = TRUE AND s.status = 'OPEN' AND c.reminded_at IS NULL AND c.updated_at < ? AND c.updated_at > ? "
                + "AND EXISTS (SELECT 1 FROM cart_items i WHERE i.cart_id = c.id) LIMIT 200",
                Timestamp.valueOf(LocalDateTime.now().minusHours(2)), Timestamp.valueOf(LocalDateTime.now().minusHours(24)))) {
            if (jdbc.update("UPDATE carts SET reminded_at = ? WHERE id = ? AND reminded_at IS NULL", Shop.now(), c.get("id")) == 0) continue;
            growth.textLater(((Number) c.get("merchant_id")).longValue(), (String) c.get("phone"), "You left something in your cart at " + c.get("name") + ": "
                    + settings.publicUrl + "/s/" + c.get("slug") + "/cart Stop: " + settings.publicUrl + "/u/" + c.get("stop_token"), "CART");
        }
    }

    // ─── loyalty points ──────────────────────────────────────────────────────

    public boolean loyaltyOn(long merchantId) {
        return jdbc.queryForList("SELECT loyalty_enabled FROM stores WHERE merchant_id = ?", Boolean.class, merchantId).stream().findFirst().orElse(false);
    }

    public int points(long merchantId, String phone) {
        String p9 = phone9(phone);
        if (p9 == null) return 0;
        Integer n = jdbc.queryForObject("SELECT COALESCE(SUM(points), 0) FROM loyalty_points WHERE merchant_id = ? AND phone9 = ?", Integer.class, merchantId, p9);
        return n == null ? 0 : Math.max(0, n);
    }

    /** Points to use on items worth itemsMinor: whole hundreds, at most half the items. */
    public static int usable(int balance, long itemsMinor) {
        long cap = itemsMinor / 2;   // 1 point = 1 minor unit (100 points = 1.00)
        long p = Math.min(balance, cap);
        return (int) (p / POINTS_PER_UNIT * POINTS_PER_UNIT);
    }

    /** Spends points on an order (once per order). False if the balance is no longer there. */
    public boolean redeem(long merchantId, String phone, String orderRef, int points) {
        if (points <= 0) return true;
        if (points(merchantId, phone) < points) return false;
        try {
            jdbc.update("INSERT INTO loyalty_points (merchant_id, phone9, points, reason, order_ref, created_at) VALUES (?,?,?, 'REDEEM', ?, ?)",
                    merchantId, phone9(phone), -points, orderRef, Shop.now());
            jdbc.update("UPDATE orders SET points_used = ? WHERE order_ref = ?", points, orderRef);
            return true;
        } catch (DuplicateKeyException e) {
            return true;
        }
    }

    /** Paid: points earned (1 per whole unit paid), once per order. */
    void earn(long orderId) {
        Map<String, Object> o = jdbc.queryForMap("SELECT merchant_id, order_ref, amount_minor, customer_phone FROM orders WHERE id = ?", orderId);
        long merchantId = ((Number) o.get("merchant_id")).longValue();
        String p9 = phone9((String) o.get("customer_phone"));
        if (p9 == null || !loyaltyOn(merchantId)) return;
        int pts = (int) (((Number) o.get("amount_minor")).longValue() / 100);
        if (pts <= 0) return;
        try {
            jdbc.update("INSERT INTO loyalty_points (merchant_id, phone9, points, reason, order_ref, created_at) VALUES (?,?,?, 'EARN', ?, ?)",
                    merchantId, p9, pts, o.get("order_ref"), Shop.now());
        } catch (DuplicateKeyException e) {
            // earned already
        }
    }

    /** The order did not go ahead: points spent on it come back (once). */
    void giveBack(long orderId) {
        Map<String, Object> o = jdbc.queryForMap("SELECT merchant_id, order_ref, customer_phone, points_used, payment_status FROM orders WHERE id = ?", orderId);
        int used = ((Number) o.get("points_used")).intValue();
        if (used <= 0 || "PAID".equals(o.get("payment_status"))) return;
        try {
            jdbc.update("INSERT INTO loyalty_points (merchant_id, phone9, points, reason, order_ref, created_at) VALUES (?,?,?, 'RETURN', ?, ?)",
                    o.get("merchant_id"), phone9((String) o.get("customer_phone")), used, o.get("order_ref"), Shop.now());
        } catch (DuplicateKeyException e) {
            // given back already
        }
    }

    // ─── reports ─────────────────────────────────────────────────────────────

    public Map<String, Object> report(long merchantId, int days) {
        int d = Math.max(1, Math.min(days, 366));
        Timestamp from = Timestamp.valueOf(LocalDateTime.now().minusDays(d));
        String paid = "FROM orders o WHERE o.merchant_id = ? AND o.payment_status = 'PAID' AND o.paid_at >= ?";
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("days", d);
        v.put("byDay", jdbc.queryForList("SELECT DATE(o.paid_at) AS day, o.currency, COUNT(*) AS orders, SUM(o.amount_minor) AS salesMinor " + paid
                + " GROUP BY DATE(o.paid_at), o.currency ORDER BY day", merchantId, from));
        v.put("byChannel", jdbc.queryForList("SELECT o.channel, o.currency, COUNT(*) AS orders, SUM(o.amount_minor) AS salesMinor " + paid
                + " GROUP BY o.channel, o.currency ORDER BY salesMinor DESC", merchantId, from));
        v.put("byPayment", jdbc.queryForList("SELECT CASE WHEN o.pay_method = 'CASH' THEN 'CASH' ELSE COALESCE(o.pay_choice, 'ONLINE') END AS method, o.currency, COUNT(*) AS orders, "
                + "SUM(o.amount_minor) AS salesMinor " + paid + " GROUP BY method, o.currency ORDER BY salesMinor DESC", merchantId, from));
        v.put("topItems", jdbc.queryForList("SELECT i.name, o.currency, SUM(i.quantity) AS sold, SUM(i.quantity * i.unit_minor) AS salesMinor FROM order_items i "
                + "JOIN orders o ON o.id = i.order_id WHERE o.merchant_id = ? AND o.payment_status = 'PAID' AND o.paid_at >= ? GROUP BY i.name, o.currency "
                + "ORDER BY salesMinor DESC LIMIT 20", merchantId, from));
        v.put("discounts", jdbc.queryForList("SELECT o.discount_code AS code, o.currency, COUNT(*) AS orders, SUM(o.discount_minor) AS discountMinor " + paid
                + " AND o.discount_code IS NOT NULL GROUP BY o.discount_code, o.currency ORDER BY orders DESC", merchantId, from));
        Map<String, Object> c = jdbc.queryForMap("SELECT COUNT(*) AS customers, COALESCE(SUM(CASE WHEN n > 1 THEN 1 ELSE 0 END), 0) AS returning FROM "
                + "(SELECT RIGHT(REGEXP_REPLACE(o.customer_phone, '[^0-9]', ''), 9) AS p, COUNT(*) AS n " + paid + " AND o.customer_phone IS NOT NULL GROUP BY p) x",
                merchantId, from);
        v.put("customers", c.get("customers"));
        v.put("returningCustomers", c.get("returning"));
        v.put("audience", audience(merchantId));
        List<Map<String, Object>> totals = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT o.currency, COUNT(*) AS orders, SUM(o.amount_minor) AS salesMinor " + paid + " GROUP BY o.currency", merchantId, from))
            totals.add(r);
        v.put("totals", totals);
        return v;
    }
}
