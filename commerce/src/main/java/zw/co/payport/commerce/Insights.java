package zw.co.payport.commerce;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 3, from Payport's own data (no outside AI provider): what sells, what will run out, what customers buy
 * together. Everything comes from paid orders only.
 */
@Service
public class Insights {

    private final JdbcTemplate jdbc;

    public Insights(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static final String PRODUCT_COLS = "p.id, p.name, p.slug, p.image_id, p.price_minor, p.compare_at_minor, p.currency, p.stock, p.kind, p.category, st.slug AS store_slug, st.name AS store_name";
    /** Products a customer can buy now: on sale, in a live, open store. */
    static final String LIVE = "p.status = 'ACTIVE' AND (p.stock IS NULL OR p.stock > 0) AND st.status = 'OPEN' AND st.suspended_reason IS NULL";

    /**
     * "Customers also bought": products bought in the same paid orders as this one; then products the same customers
     * bought within 90 days; then the store's best sellers in the same category. On sale only, this store only.
     */
    public List<Map<String, Object>> alsoBought(long merchantId, long productId, int limit) {
        int n = Math.max(1, Math.min(limit, 8));
        List<Map<String, Object>> out = new ArrayList<>(togetherInOrders(merchantId, productId, n));
        java.util.Set<Object> have = new java.util.HashSet<>();
        have.add(productId);
        for (Map<String, Object> m : out) have.add(((Number) m.get("id")).longValue());
        if (out.size() < n) for (Map<String, Object> m : jdbc.queryForList("SELECT " + PRODUCT_COLS + ", COUNT(DISTINCT ob.id) AS score FROM orders oa "
                + "JOIN order_items a ON a.order_id = oa.id AND a.product_id = ? "
                + "JOIN orders ob ON ob.merchant_id = oa.merchant_id AND ob.id <> oa.id AND ob.payment_status = 'PAID' AND ob.paid_at >= NOW() - INTERVAL 90 DAY "
                + "AND ((oa.shopper_id IS NOT NULL AND ob.shopper_id = oa.shopper_id) OR (oa.customer_phone IS NOT NULL AND ob.customer_phone = oa.customer_phone)) "
                + "JOIN order_items b ON b.order_id = ob.id JOIN products p ON p.id = b.product_id JOIN stores st ON st.id = p.store_id "
                + "WHERE oa.merchant_id = ? AND oa.payment_status = 'PAID' AND " + LIVE + " GROUP BY " + PRODUCT_COLS.replace(" AS store_slug", "").replace(" AS store_name", "")
                + " ORDER BY score DESC LIMIT 20", productId, merchantId)) add(out, have, m, n);
        if (out.size() < n) for (Map<String, Object> m : jdbc.queryForList("SELECT " + PRODUCT_COLS + ", COALESCE(SUM(i.quantity), 0) AS score FROM products p "
                + "JOIN stores st ON st.id = p.store_id JOIN products me ON me.id = ? "
                + "LEFT JOIN order_items i ON i.product_id = p.id AND i.order_id IN (SELECT id FROM orders WHERE merchant_id = ? AND payment_status = 'PAID' AND paid_at >= NOW() - INTERVAL 30 DAY) "
                + "WHERE p.merchant_id = ? AND " + LIVE + " AND (me.category IS NULL OR p.category = me.category) "
                + "GROUP BY " + PRODUCT_COLS.replace(" AS store_slug", "").replace(" AS store_name", "") + " ORDER BY score DESC, p.id DESC LIMIT 20", productId, merchantId, merchantId)) add(out, have, m, n);
        return out;
    }

    private static void add(List<Map<String, Object>> out, java.util.Set<Object> have, Map<String, Object> m, int n) {
        if (out.size() >= n) return;
        if (have.add(((Number) m.get("id")).longValue())) out.add(m);
    }

    /**
     * Picked for a signed-in customer (item-to-item collaborative filtering over paid orders): products other customers
     * bought together with what this customer bought or saved, weighted by how often; then best sellers in the
     * categories they buy; then what is selling now. Never what they already bought. In one store, or across stores.
     */
    public List<Map<String, Object>> forShopper(long shopperId, Long merchantId, int limit) {
        int n = Math.max(1, Math.min(limit, 12));
        List<Long> seeds = jdbc.queryForList("SELECT DISTINCT i.product_id FROM orders o JOIN order_items i ON i.order_id = o.id WHERE o.shopper_id = ? AND o.payment_status = 'PAID' "
                + "AND i.product_id IS NOT NULL UNION SELECT product_id FROM saved_products WHERE shopper_id = ?", Long.class, shopperId, shopperId);
        List<Long> bought = jdbc.queryForList("SELECT DISTINCT i.product_id FROM orders o JOIN order_items i ON i.order_id = o.id WHERE o.shopper_id = ? AND o.payment_status = 'PAID' "
                + "AND i.product_id IS NOT NULL", Long.class, shopperId);
        String inStore = merchantId == null ? "" : " AND p.merchant_id = " + merchantId.longValue();
        List<Map<String, Object>> out = new ArrayList<>();
        java.util.Set<Object> have = new java.util.HashSet<>(bought);
        if (!seeds.isEmpty()) {
            String ids = String.join(",", seeds.stream().limit(200).map(String::valueOf).toList());
            for (Map<String, Object> m : jdbc.queryForList("SELECT " + PRODUCT_COLS + ", COUNT(DISTINCT a.order_id) AS score FROM order_items a "
                    + "JOIN orders o ON o.id = a.order_id AND o.payment_status = 'PAID' AND o.paid_at >= NOW() - INTERVAL 180 DAY "
                    + "JOIN order_items b ON b.order_id = a.order_id AND b.product_id <> a.product_id "
                    + "JOIN products p ON p.id = b.product_id JOIN stores st ON st.id = p.store_id "
                    + "WHERE a.product_id IN (" + ids + ") AND " + LIVE + inStore
                    + " GROUP BY " + PRODUCT_COLS.replace(" AS store_slug", "").replace(" AS store_name", "") + " ORDER BY score DESC LIMIT 40")) add(out, have, m, n);
            if (out.size() < n) for (Map<String, Object> m : jdbc.queryForList("SELECT " + PRODUCT_COLS + ", COALESCE(SUM(i.quantity), 0) AS score FROM products p "
                    + "JOIN stores st ON st.id = p.store_id "
                    + "LEFT JOIN order_items i ON i.product_id = p.id AND i.order_id IN (SELECT id FROM orders WHERE payment_status = 'PAID' AND paid_at >= NOW() - INTERVAL 30 DAY) "
                    + "WHERE " + LIVE + inStore + " AND p.category IN (SELECT DISTINCT category FROM products WHERE id IN (" + ids + ") AND category IS NOT NULL) "
                    + "GROUP BY " + PRODUCT_COLS.replace(" AS store_slug", "").replace(" AS store_name", "") + " ORDER BY score DESC LIMIT 40")) add(out, have, m, n);
        }
        if (out.size() < n) for (Map<String, Object> m : trending(merchantId, 40)) add(out, have, m, n);
        return out;
    }

    /** What is selling now (paid quantity, 30 days). */
    public List<Map<String, Object>> trending(Long merchantId, int limit) {
        String inStore = merchantId == null ? "" : " AND p.merchant_id = " + merchantId.longValue();
        return jdbc.queryForList("SELECT " + PRODUCT_COLS + ", SUM(i.quantity) AS score FROM order_items i "
                + "JOIN orders o ON o.id = i.order_id AND o.payment_status = 'PAID' AND o.paid_at >= NOW() - INTERVAL 30 DAY "
                + "JOIN products p ON p.id = i.product_id JOIN stores st ON st.id = p.store_id WHERE " + LIVE + inStore
                + " GROUP BY " + PRODUCT_COLS.replace(" AS store_slug", "").replace(" AS store_name", "") + " ORDER BY score DESC LIMIT " + Math.max(1, Math.min(limit, 50)));
    }

    private List<Map<String, Object>> togetherInOrders(long merchantId, long productId, int limit) {
        return jdbc.queryForList("SELECT p.id, p.name, p.slug, p.image_id, p.price_minor, p.currency, p.stock, COUNT(*) AS together FROM order_items a "
                + "JOIN orders o ON o.id = a.order_id AND o.payment_status = 'PAID' AND o.merchant_id = ? "
                + "JOIN order_items b ON b.order_id = a.order_id AND b.product_id <> a.product_id "
                + "JOIN products p ON p.id = b.product_id AND p.status = 'ACTIVE' "
                + "WHERE a.product_id = ? GROUP BY p.id, p.name, p.slug, p.image_id, p.price_minor, p.currency, p.stock ORDER BY together DESC, p.name LIMIT " + Math.max(1, Math.min(limit, 8)),
                merchantId, productId);
    }

    /**
     * Best sellers (30 days), items that will run out within a week at the current pace (or have), and items with
     * stock that have not sold in 30 days.
     */
    public Map<String, Object> merchant(long merchantId) {
        List<Map<String, Object>> sales = jdbc.queryForList("SELECT p.id, p.name, p.currency, p.stock, COALESCE(SUM(i.quantity), 0) AS sold, "
                + "COALESCE(SUM(i.quantity * i.unit_minor), 0) AS revenue FROM products p "
                + "LEFT JOIN order_items i ON i.product_id = p.id AND i.order_id IN (SELECT id FROM orders WHERE merchant_id = ? AND payment_status = 'PAID' AND paid_at >= NOW() - INTERVAL 30 DAY) "
                + "WHERE p.merchant_id = ? AND p.status = 'ACTIVE' GROUP BY p.id, p.name, p.currency, p.stock ORDER BY sold DESC, p.name", merchantId, merchantId);
        List<Map<String, Object>> best = new ArrayList<>(), restock = new ArrayList<>(), slow = new ArrayList<>();
        for (Map<String, Object> r : sales) {
            long sold = ((Number) r.get("sold")).longValue();
            Integer stock = r.get("stock") == null ? null : ((Number) r.get("stock")).intValue();
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("productId", ((Number) r.get("id")).longValue());
            v.put("name", r.get("name"));
            v.put("sold30d", sold);
            v.put("revenue30dMinor", ((Number) r.get("revenue")).longValue());
            v.put("currency", r.get("currency"));
            v.put("stock", stock);
            if (sold > 0 && best.size() < 5) best.add(v);
            if (stock != null) {
                double perDay = sold / 30.0;
                Integer daysLeft = perDay == 0 ? null : (int) Math.floor(stock / perDay);
                v.put("daysLeft", daysLeft);
                if (stock == 0 && sold > 0) { v.put("why", "SOLD_OUT"); restock.add(v); }
                else if (daysLeft != null && daysLeft < 7) { v.put("why", "RUNNING_OUT"); restock.add(v); }
                else if (sold == 0 && stock > 0) slow.add(v);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bestSellers", best);
        out.put("restock", restock);
        out.put("notSelling", slow.size() > 10 ? slow.subList(0, 10) : slow);
        return out;
    }
}
