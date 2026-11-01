package zw.co.payport.commerce;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Collections (the merchant's own groups), discount codes, and the Payport store directory. */
@Service
public class Catalog {

    public static final Map<String, String> TYPES = new LinkedHashMap<>();
    static {
        TYPES.put("SHOP", "Shops");
        TYPES.put("CLOTHING", "Clothing and fashion");
        TYPES.put("FOOD", "Food");
        TYPES.put("DRINKS", "Drinks");
        TYPES.put("BEAUTY", "Beauty and health");
        TYPES.put("ELECTRONICS", "Electronics");
        TYPES.put("HOME", "Home and garden");
        TYPES.put("CRAFTS", "Crafts and gifts");
        TYPES.put("SERVICES", "Services");
        TYPES.put("OTHER", "Other");
    }

    private final JdbcTemplate jdbc;
    private final Shop shop;

    public Catalog(JdbcTemplate jdbc, Shop shop) {
        this.jdbc = jdbc;
        this.shop = shop;
    }

    long storeId(long merchantId) {
        return ((Number) shop.store(merchantId).get("id")).longValue();
    }

    // ─── collections ─────────────────────────────────────────────────────────

    public List<Map<String, Object>> collections(long merchantId) {
        return jdbc.queryForList("SELECT c.id, c.name, c.slug, c.position, (SELECT COUNT(*) FROM product_collections pc WHERE pc.collection_id = c.id) AS products "
                + "FROM collections c WHERE c.merchant_id = ? ORDER BY c.position, c.name", merchantId);
    }

    public Map<String, Object> addCollection(long merchantId, Map<String, Object> b) {
        String name = Shop.text(b.get("name"), 60, true, "collection name");
        long store = storeId(merchantId);
        Integer pos = jdbc.queryForObject("SELECT COALESCE(MAX(position), 0) + 1 FROM collections WHERE store_id = ?", Integer.class, store);
        String base = Shop.slug(name);
        for (int i = 0; i < 10; i++) {
            String slug = i == 0 ? base : base + "-" + Shop.code(3).toLowerCase(Locale.ROOT);
            try {
                jdbc.update("INSERT INTO collections (merchant_id, store_id, name, slug, position) VALUES (?,?,?,?,?)", merchantId, store, name, slug, pos);
                return collections(merchantId).stream().filter(c -> slug.equals(c.get("slug"))).findFirst().orElseThrow();
            } catch (DuplicateKeyException e) {
                // name taken: try another slug
            }
        }
        throw new IllegalStateException("Please try again");
    }

    public void removeCollection(long merchantId, long id) {
        if (jdbc.update("DELETE FROM collections WHERE id = ? AND merchant_id = ?", id, merchantId) == 0) throw new Shop.NotFound("No such collection");
    }

    public void moveCollection(long merchantId, long id, int position) {
        if (jdbc.update("UPDATE collections SET position = ? WHERE id = ? AND merchant_id = ?", position, id, merchantId) == 0) throw new Shop.NotFound("No such collection");
    }

    /** Puts a product in exactly these collections (the merchant's own ones only). */
    public void setProductCollections(long merchantId, long productId, List<?> ids) {
        List<Long> keep = own(merchantId, ids);
        Integer own = jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE id = ? AND merchant_id = ?", Integer.class, productId, merchantId);
        if (own == null || own == 0) throw new Shop.NotFound("No such product");
        jdbc.update("DELETE pc FROM product_collections pc JOIN collections c ON c.id = pc.collection_id WHERE pc.product_id = ? AND c.merchant_id = ?", productId, merchantId);
        for (long cid : keep) jdbc.update("INSERT IGNORE INTO product_collections (collection_id, product_id) VALUES (?,?)", cid, productId);
    }

    /** The collection ids, checked to be this merchant's own. */
    public List<Long> own(long merchantId, List<?> ids) {
        List<Long> keep = new ArrayList<>();
        for (Object o : ids == null ? List.of() : ids) {
            long cid;
            try {
                cid = Long.parseLong(String.valueOf(o));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Choose your own collections");
            }
            Integer mine = jdbc.queryForObject("SELECT COUNT(*) FROM collections WHERE id = ? AND merchant_id = ?", Integer.class, cid, merchantId);
            if (mine == null || mine == 0) throw new IllegalArgumentException("Choose your own collections");
            keep.add(cid);
        }
        return keep;
    }

    public List<Long> collectionsOf(long productId) {
        return jdbc.queryForList("SELECT collection_id FROM product_collections WHERE product_id = ?", Long.class, productId);
    }

    public List<Map<String, Object>> publicCollections(long storeId) {
        return jdbc.queryForList("SELECT c.name, c.slug FROM collections c WHERE c.store_id = ? AND EXISTS (SELECT 1 FROM product_collections pc JOIN products p ON p.id = pc.product_id "
                + "WHERE pc.collection_id = c.id AND p.status = 'ACTIVE') ORDER BY c.position, c.name", storeId);
    }

    public List<Long> productsIn(long storeId, String collectionSlug) {
        return jdbc.queryForList("SELECT pc.product_id FROM product_collections pc JOIN collections c ON c.id = pc.collection_id WHERE c.store_id = ? AND c.slug = ?",
                Long.class, storeId, collectionSlug);
    }

    // ─── discount codes ──────────────────────────────────────────────────────

    public List<Map<String, Object>> discounts(long merchantId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM discount_codes WHERE merchant_id = ? ORDER BY id DESC", merchantId)) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", ((Number) r.get("id")).longValue());
            v.put("code", r.get("code"));
            v.put("kind", r.get("kind"));
            v.put("value", r.get("value"));
            v.put("minOrderMinor", r.get("min_order_minor"));
            v.put("endsAt", r.get("ends_at") == null ? null : String.valueOf(r.get("ends_at")));
            v.put("maxUses", r.get("max_uses"));
            v.put("used", r.get("used"));
            v.put("active", Shop.bool(r.get("active")));
            out.add(v);
        }
        return out;
    }

    public Map<String, Object> addDiscount(long merchantId, Map<String, Object> b) {
        String code = Shop.text(b.get("code"), 30, true, "code").toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        if (!code.matches("[A-Z0-9_-]{3,30}")) throw new IllegalArgumentException("Use letters and numbers for the code");
        String kind = "AMOUNT".equals(b.get("kind")) ? "AMOUNT" : "PERCENT";
        long value = Shop.money(b.get("value"), kind.equals("PERCENT") ? "percentage" : "amount");
        if (kind.equals("PERCENT") && value > 90) throw new IllegalArgumentException("At most 90% off");
        Long min = Shop.optionalMoney(b.get("minOrderMinor"), "minimum order");
        Integer max = b.get("maxUses") == null || String.valueOf(b.get("maxUses")).isBlank() ? null : Integer.valueOf(String.valueOf(b.get("maxUses")));
        java.sql.Timestamp ends = null;
        if (b.get("endsAt") != null && !String.valueOf(b.get("endsAt")).isBlank()) {
            LocalDate d = LocalDate.parse(String.valueOf(b.get("endsAt")).substring(0, 10));
            if (d.isBefore(LocalDate.now())) throw new IllegalArgumentException("The end date must be today or later");
            ends = java.sql.Timestamp.valueOf(d.atTime(23, 59, 59));
        }
        try {
            jdbc.update("INSERT INTO discount_codes (merchant_id, store_id, code, kind, value, min_order_minor, ends_at, max_uses, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    merchantId, storeId(merchantId), code, kind, value, min, ends, max, Shop.now());
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("You already have that code");
        }
        shop.event("discount.created", merchantId, code, kind);
        return discounts(merchantId).stream().filter(d -> code.equals(d.get("code"))).findFirst().orElseThrow();
    }

    public void setDiscountActive(long merchantId, long id, boolean active) {
        if (jdbc.update("UPDATE discount_codes SET active = ? WHERE id = ? AND merchant_id = ?", active, id, merchantId) == 0) throw new Shop.NotFound("No such code");
    }

    public record Applied(long id, String code, long discountMinor) {}

    /** The discount a code gives on these items now, or why it does not apply. Used at checkout, never trusted from the browser. */
    public Applied apply(long storeId, String raw, long itemsMinor) {
        String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        Map<String, Object> d = jdbc.queryForList("SELECT * FROM discount_codes WHERE store_id = ? AND code = ?", storeId, code).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("That discount code is not valid"));
        if (!Shop.bool(d.get("active"))) throw new IllegalArgumentException("That discount code is not valid");
        if (d.get("ends_at") != null && Shop.time(d.get("ends_at")).isBefore(java.time.LocalDateTime.now())) throw new IllegalArgumentException("That discount code has expired");
        if (d.get("max_uses") != null && ((Number) d.get("used")).intValue() >= ((Number) d.get("max_uses")).intValue()) throw new IllegalArgumentException("That discount code has been used up");
        if (d.get("min_order_minor") != null && itemsMinor < ((Number) d.get("min_order_minor")).longValue())
            throw new IllegalArgumentException("That code needs items of at least " + Shop.major(((Number) d.get("min_order_minor")).longValue()));
        long v = ((Number) d.get("value")).longValue();
        long off = "PERCENT".equals(d.get("kind")) ? itemsMinor * v / 100 : Math.min(v, itemsMinor - 1);
        return new Applied(((Number) d.get("id")).longValue(), code, Math.max(0, off));
    }

    /** Counts one use, atomically (the last use cannot be taken twice). */
    public boolean use(long discountId) {
        return jdbc.update("UPDATE discount_codes SET used = used + 1 WHERE id = ? AND active = TRUE AND (max_uses IS NULL OR used < max_uses)", discountId) > 0;
    }

    // ─── the Payport store directory ─────────────────────────────────────────

    /** Listed, open stores with something on sale, by type; optional search and type filter. */
    public List<Map<String, Object>> directory(String type, String q) {
        StringBuilder sql = new StringBuilder("SELECT s.id, s.slug, s.name, s.about, s.tagline, s.business_type, s.logo_image, s.banner_image, s.theme_color, s.cod_enabled, s.delivery_enabled, s.directory_featured, s.directory_rank, "
                + "EXISTS (SELECT 1 FROM products p WHERE p.store_id = s.id AND p.status = 'ACTIVE' AND p.compare_at_minor IS NOT NULL) AS on_sale, "
                + "(SELECT COUNT(*) FROM orders o WHERE o.store_id = s.id AND o.payment_status = 'PAID' AND o.paid_at >= NOW() - INTERVAL 30 DAY) AS orders30, "
                + "(SELECT COUNT(*) FROM products p WHERE p.store_id = s.id AND p.status = 'ACTIVE') AS products FROM stores s JOIN merchants m ON m.merchant_id = s.merchant_id "
                + "WHERE s.listed = TRUE AND s.status = 'OPEN' AND s.suspended_reason IS NULL AND m.psp_status = 'ACTIVE' AND EXISTS (SELECT 1 FROM products p WHERE p.store_id = s.id AND p.status = 'ACTIVE')");
        List<Object> args = new ArrayList<>();
        if (type != null && TYPES.containsKey(type)) {
            sql.append(" AND s.business_type = ?");
            args.add(type);
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (s.name LIKE ? OR s.about LIKE ? OR s.tagline LIKE ? OR EXISTS (SELECT 1 FROM products p WHERE p.store_id = s.id AND p.status = 'ACTIVE' AND p.name LIKE ?))");
            String like = "%" + q.trim().replace("%", "") + "%";
            for (int i = 0; i < 4; i++) args.add(like);
        }
        sql.append(" ORDER BY COALESCE(s.directory_rank, 100000), s.name LIMIT 500");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /** Items on sale in listed, open stores matching the assistant's reading of a question (best matches first). */
    public List<Map<String, Object>> searchProducts(Ai.Query q, int limit) {
        if (q.terms().isEmpty()) return List.of();
        StringBuilder score = new StringBuilder("(0");
        List<Object> args = new ArrayList<>();
        StringBuilder any = new StringBuilder("(");
        for (int i = 0; i < Math.min(q.terms().size(), 24); i++) {
            String like = "%" + q.terms().get(i).replace("%", "") + "%";
            score.append(" + CASE WHEN p.name LIKE ? THEN 3 ELSE 0 END + CASE WHEN COALESCE(p.category,'') LIKE ? THEN 2 ELSE 0 END + CASE WHEN COALESCE(p.description,'') LIKE ? THEN 1 ELSE 0 END");
            args.add(like); args.add(like); args.add(like);
            any.append(i == 0 ? "" : " OR ").append("p.name LIKE ? OR COALESCE(p.category,'') LIKE ? OR COALESCE(p.description,'') LIKE ? OR s.name LIKE ?");
        }
        score.append(")");
        any.append(")");
        List<Object> all = new ArrayList<>(args);
        for (int i = 0; i < Math.min(q.terms().size(), 24); i++) {
            String like = "%" + q.terms().get(i).replace("%", "") + "%";
            all.add(like); all.add(like); all.add(like); all.add(like);
        }
        String sql = "SELECT p.id, p.slug, p.name, p.price_minor, p.currency, p.image_id, p.compare_at_minor, s.slug AS store_slug, s.name AS store_name, s.cod_enabled, "
                + score + " AS score FROM products p JOIN stores s ON s.id = p.store_id JOIN merchants m ON m.merchant_id = s.merchant_id "
                + "WHERE p.status = 'ACTIVE' AND (p.stock IS NULL OR p.stock > 0) AND s.listed = TRUE AND s.status = 'OPEN' AND s.suspended_reason IS NULL AND m.psp_status = 'ACTIVE' AND " + any
                + (q.delivery() ? " AND s.delivery_enabled = TRUE" : "") + (q.cashOnDelivery() ? " AND s.cod_enabled = TRUE" : "")
                + " ORDER BY " + (q.cheap() ? "p.price_minor, score DESC" : "score DESC, p.price_minor") + " LIMIT " + Math.max(1, Math.min(limit, 60));
        return jdbc.queryForList(sql, all.toArray());
    }

    public Optional<String> typeName(String type) {
        return Optional.ofNullable(TYPES.get(type));
    }
}
