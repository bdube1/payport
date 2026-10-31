package zw.co.payport.commerce;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 3, from PayPort's own data (no outside AI provider): what sells, what will run out, what customers buy
 * together. Everything comes from paid orders only.
 */
@Service
public class Insights {

    private final JdbcTemplate jdbc;

    public Insights(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Products often bought with this one (in the same paid orders), most often first; on sale only. */
    public List<Map<String, Object>> alsoBought(long merchantId, long productId, int limit) {
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
