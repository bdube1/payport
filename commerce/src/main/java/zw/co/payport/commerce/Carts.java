package zw.co.payport.commerce;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shopping carts: kept here, found by a random cookie (only its hash is stored), one per store and one currency per
 * cart. Prices and stock are always read fresh, so a cart never sells at an old price or beyond what is left.
 */
@Service
public class Carts {

    public static final String COOKIE = "pp_cart";
    private final JdbcTemplate jdbc;

    public Carts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static boolean valid(String token) {
        return token != null && token.matches("[A-Za-z0-9_-]{20,64}");
    }

    Optional<Long> find(long storeId, String token) {
        if (!valid(token)) return Optional.empty();
        return jdbc.queryForList("SELECT id FROM carts WHERE token_hash = ? AND store_id = ?", Long.class, Shop.sha256(token), storeId).stream().findFirst();
    }

    long open(long storeId, String token) {
        Optional<Long> c = find(storeId, token);
        if (c.isPresent()) return c.get();
        try {
            jdbc.update("INSERT INTO carts (token_hash, store_id, created_at, updated_at) VALUES (?,?,?,?)", Shop.sha256(token), storeId, Shop.now(), Shop.now());
        } catch (DuplicateKeyException ignored) {
            // opened by a parallel request
        }
        return find(storeId, token).orElseThrow();
    }

    /** Adds (or sets) a product's quantity; explains anything that cannot be added. */
    public void set(long storeId, String token, long productId, int quantity, boolean add) {
        Map<String, Object> p = jdbc.queryForList("SELECT * FROM products WHERE id = ? AND store_id = ? AND status = 'ACTIVE'", productId, storeId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("This product is not available"));
        long cart = open(storeId, token);
        if (quantity <= 0 && !add) {
            jdbc.update("DELETE FROM cart_items WHERE cart_id = ? AND product_id = ?", cart, productId);
            touch(cart);
            return;
        }
        String ccy = jdbc.queryForObject("SELECT currency FROM carts WHERE id = ?", String.class, cart);
        Integer items = jdbc.queryForObject("SELECT COUNT(*) FROM cart_items WHERE cart_id = ?", Integer.class, cart);
        if (ccy != null && items != null && items > 0 && !ccy.equals(p.get("currency")))
            throw new IllegalArgumentException("Your cart is in " + ccy + ". Pay for it first, then add items priced in " + p.get("currency") + ".");
        int current = jdbc.queryForList("SELECT quantity FROM cart_items WHERE cart_id = ? AND product_id = ?", Integer.class, cart, productId).stream().findFirst().orElse(0);
        int want = add ? current + Math.max(1, quantity) : quantity;
        if (want > 50) throw new IllegalArgumentException("At most 50 of one item");
        Object stock = p.get("stock");
        if (stock != null && want > ((Number) stock).intValue())
            throw new IllegalArgumentException(((Number) stock).intValue() == 0 ? "Sold out" : "Only " + stock + " left");
        jdbc.update("INSERT INTO cart_items (cart_id, product_id, quantity) VALUES (?,?,?) ON DUPLICATE KEY UPDATE quantity = VALUES(quantity)", cart, productId, want);
        jdbc.update("UPDATE carts SET currency = ?, updated_at = ? WHERE id = ?", p.get("currency"), Shop.now(), cart);
    }

    void touch(long cart) {
        jdbc.update("UPDATE carts SET updated_at = ? WHERE id = ?", Shop.now(), cart);
    }

    public record Line(long productId, String name, String slug, String image, long unitMinor, int quantity, Integer stock, String problem) {}

    public record View(long cartId, String currency, List<Line> lines, long itemsMinor, int count, boolean ok) {}

    /** The cart with today's prices; a line that can no longer be bought says why. */
    public Optional<View> view(long storeId, String token) {
        Optional<Long> c = find(storeId, token);
        if (c.isEmpty()) return Optional.empty();
        List<Line> lines = new ArrayList<>();
        long total = 0;
        int count = 0;
        boolean ok = true;
        String ccy = null;
        for (Map<String, Object> r : jdbc.queryForList("SELECT i.quantity, p.id, p.name, p.slug, p.image_id, p.price_minor, p.currency, p.stock, p.status "
                + "FROM cart_items i JOIN products p ON p.id = i.product_id WHERE i.cart_id = ? ORDER BY p.name", c.get())) {
            int q = ((Number) r.get("quantity")).intValue();
            long unit = ((Number) r.get("price_minor")).longValue();
            Integer stock = r.get("stock") == null ? null : ((Number) r.get("stock")).intValue();
            String problem = !"ACTIVE".equals(r.get("status")) ? "No longer available" : stock != null && stock == 0 ? "Sold out" : stock != null && q > stock ? "Only " + stock + " left" : null;
            if (problem != null) ok = false;
            ccy = (String) r.get("currency");
            lines.add(new Line(((Number) r.get("id")).longValue(), (String) r.get("name"), (String) r.get("slug"), r.get("image_id") == null ? null : "/img/" + r.get("image_id"),
                    unit, q, stock, problem));
            total += unit * q;
            count += q;
        }
        return Optional.of(new View(c.get(), ccy, lines, total, count, ok && !lines.isEmpty()));
    }

    public int count(long storeId, String token) {
        return find(storeId, token).map(id -> jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM cart_items WHERE cart_id = ?", Integer.class, id)).orElse(0);
    }

    public void empty(long cartId) {
        jdbc.update("DELETE FROM cart_items WHERE cart_id = ?", cartId);
    }

    @Scheduled(cron = "0 30 4 * * *")
    public void tidy() {
        jdbc.update("DELETE FROM carts WHERE updated_at < NOW() - INTERVAL 30 DAY");
    }

    /** For the page: a line as a map (templates read maps). */
    static Map<String, Object> line(Line l, String ccy) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", l.productId());
        m.put("name", l.name());
        m.put("slug", l.slug());
        m.put("image", l.image());
        m.put("unit", Storefront.price(l.unitMinor(), ccy));
        m.put("quantity", l.quantity());
        m.put("line", Storefront.price(l.unitMinor() * l.quantity(), ccy));
        m.put("problem", l.problem());
        return m;
    }
}
