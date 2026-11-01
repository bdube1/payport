package zw.co.payport.commerce;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The notification bell (owner, 1 Nov 2026). For a merchant: unread customer messages, and new orders and reviews
 * since the bell was last opened (a store customer's unread answers are counted by Chats).
 */
@Service
public class Bell {

    private final JdbcTemplate jdbc;
    private final Sealed sealed;

    public Bell(JdbcTemplate jdbc, Sealed sealed) {
        this.jdbc = jdbc;
        this.sealed = sealed;
    }

    private Timestamp seen(long merchantId) {
        List<Timestamp> t = jdbc.queryForList("SELECT bell_seen_at FROM merchants WHERE merchant_id = ?", Timestamp.class, merchantId);
        Timestamp week = Timestamp.valueOf(LocalDateTime.now().minusDays(7));
        return t.isEmpty() || t.get(0) == null || t.get(0).before(week) ? week : t.get(0);
    }

    static Map<String, Object> item(String kind, String text, String link, Object at, boolean unread) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("kind", kind);
        v.put("text", text);
        v.put("link", link);
        v.put("at", at == null ? null : Shop.time(at).toString().replace('T', ' ').substring(0, 16));
        v.put("unread", unread);
        return v;
    }

    /** The merchant's notifications, newest first, with the number still unread. */
    public Map<String, Object> merchant(long merchantId) {
        Timestamp since = seen(merchantId);
        List<Map<String, Object>> items = new ArrayList<>();
        int unread = 0;
        for (Map<String, Object> c : jdbc.queryForList("SELECT id, name_enc, shopper_id, unread_merchant, last_at FROM chats WHERE merchant_id = ? AND unread_merchant > 0 "
                + "ORDER BY last_at DESC LIMIT 20", merchantId)) {
            String name = sealed.open((String) c.get("name_enc"));
            if (name == null && c.get("shopper_id") != null)
                name = jdbc.queryForList("SELECT name FROM shoppers WHERE id = ?", String.class, c.get("shopper_id")).stream().findFirst().orElse(null);
            int n = ((Number) c.get("unread_merchant")).intValue();
            unread += n;
            items.add(item("CHAT", (name == null ? "A customer" : name) + " sent " + (n == 1 ? "a message" : n + " messages"), "/store/chats?c=" + c.get("id"), c.get("last_at"), true));
        }
        for (Map<String, Object> o : jdbc.queryForList("SELECT order_ref, item_name, payment_status, created_at FROM orders WHERE merchant_id = ? AND store_id IS NOT NULL "
                + "AND payment_status IN ('PAID','ON_DELIVERY') AND created_at > ? ORDER BY created_at DESC LIMIT 20", merchantId, since)) {
            unread++;
            items.add(item("ORDER", "New order " + o.get("order_ref") + ": " + Shop.clip(String.valueOf(o.get("item_name")), 60)
                    + ("ON_DELIVERY".equals(o.get("payment_status")) ? " (pay on delivery)" : ""), "/store/orders", o.get("created_at"), true));
        }
        for (Map<String, Object> r : jdbc.queryForList("SELECT r.rating, p.name, r.created_at FROM reviews r JOIN products p ON p.id = r.product_id WHERE r.merchant_id = ? AND r.created_at > ? "
                + "ORDER BY r.created_at DESC LIMIT 20", merchantId, since)) {
            unread++;
            items.add(item("REVIEW", "New " + r.get("rating") + "★ review of " + r.get("name"), "/store/reviews", r.get("created_at"), true));
        }
        items.sort((a, b) -> String.valueOf(b.get("at")).compareTo(String.valueOf(a.get("at"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("unread", unread);
        out.put("items", items.size() > 30 ? items.subList(0, 30) : items);
        return out;
    }

    /** The merchant opened the bell: new orders and reviews are seen (messages stay unread until opened). */
    public Map<String, Object> merchantSeen(long merchantId) {
        jdbc.update("UPDATE merchants SET bell_seen_at = ? WHERE merchant_id = ?", Shop.now(), merchantId);
        return merchant(merchantId);
    }
}
