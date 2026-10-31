package zw.co.payport.commerce;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Stores, products, payment links and orders. Every read and write is scoped to one merchant (merchant_id is in
 * every WHERE clause), so one merchant can never see, change or pay into another's things, even with a guessed id.
 */
@Service
public class Shop {

    public static class NotFound extends RuntimeException {
        public NotFound(String m) { super(m); }
    }

    static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final Settings settings;

    public Shop(JdbcTemplate jdbc, Settings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    /** A DATETIME as read by the MySQL driver (LocalDateTime, or Timestamp from older drivers). */
    static LocalDateTime time(Object o) {
        if (o instanceof LocalDateTime l) return l;
        if (o instanceof Timestamp t) return t.toLocalDateTime();
        return null;
    }

    static Timestamp now() {
        return Timestamp.valueOf(LocalDateTime.now());
    }

    static String code(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    static String token() {
        byte[] b = new byte[18];
        RANDOM.nextBytes(b);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String slug(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (n.length() > 50) n = n.substring(0, 50).replaceAll("-+$", "");
        return n.isEmpty() ? "shop" : n;
    }

    static String text(Object o, int max, boolean required, String what) {
        String s = o == null ? "" : String.valueOf(o).replaceAll("[\\u0000-\\u001F\\u007F]", " ").trim();
        if (required && s.isEmpty()) throw new IllegalArgumentException("Enter the " + what);
        if (s.length() > max) throw new IllegalArgumentException("The " + what + " is too long (at most " + max + " characters)");
        return s.isEmpty() ? null : s;
    }

    static long money(Object o, String what) {
        long v;
        try {
            v = o instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(o).trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Enter the " + what);
        }
        if (v <= 0 || v > 100_000_000L) throw new IllegalArgumentException("Enter a valid " + what);
        return v;
    }

    static Long optionalMoney(Object o, String what) {
        return o == null || String.valueOf(o).isBlank() ? null : money(o, what);
    }

    /** Majors with two decimals ("12.50"), the way prices are shown. */
    public static String major(long minor) {
        return java.math.BigDecimal.valueOf(minor, 2).toPlainString();
    }

    // ─── merchants (copied from the PSP at sign-in) ─────────────────────────

    @Transactional
    public void rememberMerchant(long merchantId, String name, List<String> currencies, String status) {
        String ccy = String.join(",", currencies == null || currencies.isEmpty() ? List.of("USD") : currencies);
        Timestamp now = now();
        boolean first = jdbc.update("INSERT IGNORE INTO merchants (merchant_id, business_name, currencies, psp_status, first_seen_at, updated_at) VALUES (?,?,?,?,?,?)",
                merchantId, name, ccy, status == null ? "ACTIVE" : status, now, now) > 0;
        if (!first) jdbc.update("UPDATE merchants SET business_name = ?, currencies = ?, psp_status = ?, updated_at = ? WHERE merchant_id = ?",
                name, ccy, status == null ? "ACTIVE" : status, now, merchantId);
        else event("merchant.first_seen", merchantId, null, null);
        store(merchantId);   // every merchant has a store from the first sign-in
    }

    public List<String> currencies(long merchantId) {
        List<String> c = jdbc.queryForList("SELECT currencies FROM merchants WHERE merchant_id = ?", String.class, merchantId);
        if (c.isEmpty() || c.get(0) == null) return List.of("USD");
        return List.of(c.get(0).split(","));
    }

    void requireCurrency(long merchantId, String ccy) {
        if (ccy == null || !currencies(merchantId).contains(ccy)) throw new IllegalArgumentException("Choose one of your currencies: " + String.join(", ", currencies(merchantId)));
    }

    public void event(String type, long merchantId, String subject, String detail) {
        jdbc.update("INSERT INTO commerce_events (event_type, merchant_id, subject_ref, detail, occurred_at) VALUES (?,?,?,?,?)",
                type, merchantId, subject, detail, now());
    }

    // ─── store ───────────────────────────────────────────────────────────────

    public Map<String, Object> store(long merchantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM stores WHERE merchant_id = ?", merchantId);
        if (!rows.isEmpty()) return storeView(rows.get(0));
        String name = jdbc.queryForList("SELECT business_name FROM merchants WHERE merchant_id = ?", String.class, merchantId).stream().findFirst().orElse("My shop");
        String base = slug(name);
        for (int i = 0; i < 20; i++) {
            String s = i == 0 ? base : base + "-" + code(3).toLowerCase(Locale.ROOT);
            try {
                jdbc.update("INSERT INTO stores (merchant_id, slug, name, created_at, updated_at) VALUES (?,?,?,?,?)", merchantId, s, clip(name, 120), now(), now());
                event("store.created", merchantId, s, null);
                return storeView(jdbc.queryForMap("SELECT * FROM stores WHERE merchant_id = ?", merchantId));
            } catch (DuplicateKeyException e) {
                List<Map<String, Object>> again = jdbc.queryForList("SELECT * FROM stores WHERE merchant_id = ?", merchantId);
                if (!again.isEmpty()) return storeView(again.get(0));
            }
        }
        throw new IllegalStateException("Please try again");
    }

    Map<String, Object> storeView(Map<String, Object> r) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("slug", r.get("slug"));
        v.put("name", r.get("name"));
        v.put("about", r.get("about"));
        v.put("whatsapp", r.get("whatsapp"));
        v.put("themeColor", r.get("theme_color"));
        v.put("logoImage", r.get("logo_image"));
        v.put("status", r.get("status"));
        v.put("url", settings.publicUrl + "/s/" + r.get("slug"));
        v.put("id", ((Number) r.get("id")).longValue());
        v.put("pickupEnabled", bool(r.get("pickup_enabled")));
        v.put("pickupNote", r.get("pickup_note"));
        v.put("deliveryEnabled", bool(r.get("delivery_enabled")));
        v.put("deliveryFeeMinor", r.get("delivery_fee_minor") == null ? 0L : ((Number) r.get("delivery_fee_minor")).longValue());
        v.put("freeDeliveryFromMinor", r.get("free_delivery_from_minor"));
        v.put("deliveryNote", r.get("delivery_note"));
        v.put("design", r.get("design"));
        v.put("bannerImage", r.get("banner_image"));
        v.put("tagline", r.get("tagline"));
        v.put("deliveryProvider", r.get("delivery_provider"));
        v.put("businessType", r.get("business_type"));
        v.put("announcement", r.get("announcement"));
        v.put("codEnabled", bool(r.get("cod_enabled")));
        v.put("listed", bool(r.get("listed")));
        return v;
    }

    public Map<String, Object> updateStore(long merchantId, Map<String, Object> b) {
        Map<String, Object> cur = store(merchantId);
        String name = text(b.getOrDefault("name", cur.get("name")), 120, true, "store name");
        String about = text(b.getOrDefault("about", cur.get("about")), 600, false, "description");
        String wa = text(b.getOrDefault("whatsapp", cur.get("whatsapp")), 20, false, "WhatsApp number");
        if (wa != null && !wa.replaceAll("[\\s-]", "").matches("\\+?[0-9]{9,15}")) throw new IllegalArgumentException("Enter the WhatsApp number with the country code");
        String color = text(b.getOrDefault("themeColor", cur.get("themeColor")), 7, true, "colour");
        if (!color.matches("#[0-9A-Fa-f]{6}")) throw new IllegalArgumentException("Choose a colour");
        String slug = text(b.getOrDefault("slug", cur.get("slug")), 60, true, "store address");
        slug = slug.toLowerCase(Locale.ROOT);
        if (!slug.matches("[a-z0-9](?:[a-z0-9-]{1,58}[a-z0-9])")) throw new IllegalArgumentException("The store address uses letters, numbers and dashes");
        String logo = b.containsKey("logoImage") ? text(b.get("logoImage"), 32, false, "logo") : (String) cur.get("logoImage");
        if (logo != null) ownImage(merchantId, logo);
        String status = "CLOSED".equals(b.get("status")) ? "CLOSED" : "OPEN";
        boolean pickup = b.containsKey("pickupEnabled") ? bool(b.get("pickupEnabled")) : (Boolean) cur.get("pickupEnabled");
        boolean delivery = b.containsKey("deliveryEnabled") ? bool(b.get("deliveryEnabled")) : (Boolean) cur.get("deliveryEnabled");
        if (!pickup && !delivery) throw new IllegalArgumentException("Offer pickup, delivery or both");
        String pickupNote = text(b.getOrDefault("pickupNote", cur.get("pickupNote")), 300, false, "pickup details");
        String deliveryNote = text(b.getOrDefault("deliveryNote", cur.get("deliveryNote")), 300, false, "delivery details");
        Object feeIn = b.getOrDefault("deliveryFeeMinor", cur.get("deliveryFeeMinor"));
        long fee;
        try { fee = feeIn == null || String.valueOf(feeIn).isBlank() ? 0 : Long.parseLong(String.valueOf(feeIn)); } catch (NumberFormatException e) { throw new IllegalArgumentException("Enter the delivery fee"); }
        if (fee < 0 || fee > 100_000_000L) throw new IllegalArgumentException("Enter the delivery fee");
        Long freeFrom = optionalMoney(b.getOrDefault("freeDeliveryFromMinor", cur.get("freeDeliveryFromMinor")), "free delivery amount");
        String design = String.valueOf(b.getOrDefault("design", cur.get("design")));
        if (!java.util.Set.of("PRODUCT", "BOUTIQUE", "LOCAL").contains(design)) throw new IllegalArgumentException("Choose a store design");
        String banner = b.containsKey("bannerImage") ? text(b.get("bannerImage"), 32, false, "banner") : (String) cur.get("bannerImage");
        if (banner != null) ownImage(merchantId, banner);
        String tagline = text(b.getOrDefault("tagline", cur.get("tagline")), 120, false, "tagline");
        String type = String.valueOf(b.getOrDefault("businessType", cur.get("businessType")));
        if (!Catalog.TYPES.containsKey(type)) throw new IllegalArgumentException("Choose what your store sells");
        String announcement = text(b.getOrDefault("announcement", cur.get("announcement")), 200, false, "announcement");
        boolean cod = b.containsKey("codEnabled") ? bool(b.get("codEnabled")) : (Boolean) cur.get("codEnabled");
        boolean listed = b.containsKey("listed") ? bool(b.get("listed")) : (Boolean) cur.get("listed");
        try {
            jdbc.update("UPDATE stores SET name = ?, about = ?, whatsapp = ?, theme_color = ?, slug = ?, logo_image = ?, status = ?, pickup_enabled = ?, pickup_note = ?, "
                            + "delivery_enabled = ?, delivery_fee_minor = ?, free_delivery_from_minor = ?, delivery_note = ?, design = ?, banner_image = ?, tagline = ?, business_type = ?, announcement = ?, cod_enabled = ?, listed = ?, updated_at = ? WHERE merchant_id = ?",
                    name, about, wa == null ? null : wa.replaceAll("[\\s-]", ""), color.toUpperCase(Locale.ROOT), slug, logo, status, pickup, pickupNote,
                    delivery, fee, freeFrom, deliveryNote, design, banner, tagline, type, announcement, cod, listed, now(), merchantId);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("That store address is taken");
        }
        return store(merchantId);
    }

    // ─── images ──────────────────────────────────────────────────────────────

    void ownImage(long merchantId, String id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM images WHERE id = ? AND merchant_id = ?", Integer.class, id, merchantId);
        if (n == null || n == 0) throw new IllegalArgumentException("Upload the picture again");
    }

    public String saveImage(long merchantId, Images.Clean img) {
        String id = HexFormat.of().formatHex(java.nio.ByteBuffer.allocate(16).putLong(RANDOM.nextLong()).putLong(RANDOM.nextLong()).array());
        jdbc.update("INSERT INTO images (id, merchant_id, content_type, bytes, sha256, width, height, created_at) VALUES (?,?,?,?,?,?,?,?)",
                id, merchantId, img.contentType(), img.bytes(), img.sha256(), img.width(), img.height(), now());
        return id;
    }

    public Optional<Map<String, Object>> image(String id) {
        if (id == null || !id.matches("[0-9a-f]{32}")) return Optional.empty();
        return jdbc.queryForList("SELECT content_type, bytes, sha256 FROM images WHERE id = ?", id).stream().findFirst();
    }

    // ─── products ────────────────────────────────────────────────────────────

    public List<Map<String, Object>> products(long merchantId, boolean activeOnly) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM products WHERE merchant_id = ?" + (activeOnly ? " AND status = 'ACTIVE'" : "") + " ORDER BY id DESC", merchantId)) {
            out.add(productView(r));
        }
        return out;
    }

    Map<String, Object> productView(Map<String, Object> r) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", ((Number) r.get("id")).longValue());
        v.put("slug", r.get("slug"));
        v.put("name", r.get("name"));
        v.put("description", r.get("description"));
        v.put("priceMinor", ((Number) r.get("price_minor")).longValue());
        v.put("price", major(((Number) r.get("price_minor")).longValue()));
        v.put("currency", r.get("currency"));
        v.put("imageId", r.get("image_id"));
        v.put("stock", r.get("stock") == null ? null : ((Number) r.get("stock")).intValue());
        v.put("category", r.get("category"));
        v.put("kind", r.get("kind"));
        v.put("compareAtMinor", r.get("compare_at_minor"));
        v.put("featured", bool(r.get("featured")));
        v.put("status", r.get("status"));
        String storeSlug = jdbc.queryForList("SELECT slug FROM stores WHERE id = ?", String.class, r.get("store_id")).stream().findFirst().orElse("");
        v.put("url", settings.publicUrl + "/s/" + storeSlug + "/" + r.get("slug"));
        return v;
    }

    public Map<String, Object> product(long merchantId, long id) {
        return jdbc.queryForList("SELECT * FROM products WHERE id = ? AND merchant_id = ?", id, merchantId).stream().findFirst()
                .map(this::productView).orElseThrow(() -> new NotFound("No such product"));
    }

    @Transactional
    public Map<String, Object> saveProduct(long merchantId, Long id, Map<String, Object> b) {
        long store = ((Number) store(merchantId).get("id")).longValue();
        String name = text(b.get("name"), 120, true, "product name");
        String description = text(b.get("description"), 2000, false, "description");
        long price = money(b.get("priceMinor"), "price");
        String ccy = String.valueOf(b.getOrDefault("currency", "USD")).toUpperCase(Locale.ROOT);
        requireCurrency(merchantId, ccy);
        String image = text(b.get("imageId"), 32, false, "picture");
        if (image != null) ownImage(merchantId, image);
        String status = "HIDDEN".equals(b.get("status")) ? "HIDDEN" : "ACTIVE";
        String category = text(b.get("category"), 40, false, "category");
        String kind = "SERVICE".equals(b.get("kind")) ? "SERVICE" : "GOODS";
        Long compareAt = optionalMoney(b.get("compareAtMinor"), "old price");
        if (compareAt != null && compareAt <= price) throw new IllegalArgumentException("The old price must be higher than the price");
        boolean featured = bool(b.get("featured"));
        Integer stock = null;
        Object st = b.get("stock");
        if (st != null && !String.valueOf(st).isBlank()) {
            try { stock = Integer.valueOf(String.valueOf(st).trim()); } catch (NumberFormatException e) { throw new IllegalArgumentException("Enter the number in stock, or leave it empty"); }
            if (stock < 0 || stock > 1_000_000) throw new IllegalArgumentException("Enter the number in stock, or leave it empty");
        }
        if ("SERVICE".equals(kind)) stock = null;   // services are not counted
        if (id == null) {
            String base = slug(name);
            if (java.util.Set.of("cart", "checkout").contains(base)) base = base + "-item";
            for (int i = 0; i < 20; i++) {
                String s = i == 0 ? base : base + "-" + code(3).toLowerCase(Locale.ROOT);
                try {
                    jdbc.update("INSERT INTO products (merchant_id, store_id, slug, name, description, price_minor, currency, image_id, status, stock, category, kind, compare_at_minor, featured, created_at, updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", merchantId, store, s, name, description, price, ccy, image, status, stock, category, kind, compareAt, featured, now(), now());
                    long nid = jdbc.queryForObject("SELECT id FROM products WHERE store_id = ? AND slug = ?", Long.class, store, s);
                    event("product.created", merchantId, String.valueOf(nid), null);
                    return product(merchantId, nid);
                } catch (DuplicateKeyException e) {
                    // slug taken in this store: try another
                }
            }
            throw new IllegalStateException("Please try again");
        }
        int n = jdbc.update("UPDATE products SET name = ?, description = ?, price_minor = ?, currency = ?, image_id = ?, status = ?, stock = ?, category = ?, kind = ?, compare_at_minor = ?, featured = ?, updated_at = ? WHERE id = ? AND merchant_id = ?",
                name, description, price, ccy, image, status, stock, category, kind, compareAt, featured, now(), id, merchantId);
        if (n == 0) throw new NotFound("No such product");
        return product(merchantId, id);
    }

    // ─── links ───────────────────────────────────────────────────────────────

    Map<String, Object> linkView(Map<String, Object> r) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", ((Number) r.get("id")).longValue());
        v.put("code", r.get("code"));
        v.put("title", r.get("title"));
        v.put("description", r.get("description"));
        v.put("currency", r.get("currency"));
        v.put("amountMinor", r.get("amount_minor"));
        v.put("minMinor", r.get("min_minor"));
        v.put("maxMinor", r.get("max_minor"));
        v.put("reference", r.get("reference"));
        v.put("productId", r.get("product_id"));
        v.put("reusable", Boolean.TRUE.equals(r.get("reusable")) || Integer.valueOf(1).equals(r.get("reusable")));
        v.put("expiresAt", r.get("expires_at") == null ? null : String.valueOf(r.get("expires_at")));
        v.put("status", effectiveStatus(r));
        v.put("url", settings.publicUrl + "/l/" + r.get("code"));
        Map<String, Object> stats = jdbc.queryForMap("SELECT COUNT(*) AS orders, COALESCE(SUM(payment_status = 'PAID'), 0) AS paid, "
                + "COALESCE(SUM(CASE WHEN payment_status = 'PAID' THEN amount_minor END), 0) AS paid_minor FROM orders WHERE link_id = ?", r.get("id"));
        v.put("orders", ((Number) stats.get("orders")).longValue());
        v.put("paidOrders", ((Number) stats.get("paid")).longValue());
        v.put("paidMinor", ((Number) stats.get("paid_minor")).longValue());
        return v;
    }

    /** ACTIVE, DISABLED, USED (a single-use link that has been paid) or EXPIRED. */
    static String effectiveStatus(Map<String, Object> r) {
        String s = String.valueOf(r.get("status"));
        Object exp = r.get("expires_at");
        if ("ACTIVE".equals(s) && exp != null) {
            LocalDateTime e = time(exp);
            if (e != null && e.isBefore(LocalDateTime.now())) return "EXPIRED";
        }
        return s;
    }

    public List<Map<String, Object>> links(long merchantId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM links WHERE merchant_id = ? ORDER BY id DESC LIMIT 500", merchantId)) out.add(linkView(r));
        return out;
    }

    public Map<String, Object> link(long merchantId, long id) {
        return jdbc.queryForList("SELECT * FROM links WHERE id = ? AND merchant_id = ?", id, merchantId).stream().findFirst()
                .map(this::linkView).orElseThrow(() -> new NotFound("No such link"));
    }

    @Transactional
    public Map<String, Object> createLink(long merchantId, Map<String, Object> b) {
        Long productId = b.get("productId") == null || String.valueOf(b.get("productId")).isBlank() ? null : Long.valueOf(String.valueOf(b.get("productId")));
        String title, ccy, description;
        Long amount, min, max;
        if (productId != null) {
            Map<String, Object> p = product(merchantId, productId);
            title = text(b.getOrDefault("title", p.get("name")), 120, true, "title");
            description = text(b.getOrDefault("description", p.get("description")), 600, false, "description");
            ccy = (String) p.get("currency");
            amount = (Long) p.get("priceMinor");
            min = null;
            max = null;
        } else {
            title = text(b.get("title"), 120, true, "title");
            description = text(b.get("description"), 600, false, "description");
            ccy = String.valueOf(b.getOrDefault("currency", "USD")).toUpperCase(Locale.ROOT);
            requireCurrency(merchantId, ccy);
            amount = optionalMoney(b.get("amountMinor"), "amount");
            min = amount == null ? optionalMoney(b.get("minMinor"), "smallest amount") : null;
            max = amount == null ? optionalMoney(b.get("maxMinor"), "largest amount") : null;
            if (min != null && max != null && max < min) throw new IllegalArgumentException("The largest amount must be at least the smallest");
        }
        String reference = text(b.get("reference"), 60, false, "reference");
        boolean reusable = !Boolean.FALSE.equals(b.get("reusable")) && !"false".equals(String.valueOf(b.get("reusable")));
        LocalDateTime expires = null;
        if (b.get("expiresAt") != null && !String.valueOf(b.get("expiresAt")).isBlank()) {
            try {
                String e = String.valueOf(b.get("expiresAt")).trim();
                expires = e.length() == 10 ? java.time.LocalDate.parse(e).atTime(23, 59, 59) : LocalDateTime.parse(e.replace("Z", ""));
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("Choose the expiry date");
            }
            if (expires.isBefore(LocalDateTime.now())) throw new IllegalArgumentException("The expiry must be in the future");
        }
        for (int i = 0; i < 20; i++) {
            String code = code(8);
            try {
                jdbc.update("INSERT INTO links (merchant_id, code, title, description, currency, amount_minor, min_minor, max_minor, reference, product_id, reusable, expires_at, status, created_at, updated_at) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'ACTIVE', ?, ?)",
                        merchantId, code, title, description, ccy, amount, min, max, reference, productId, reusable, expires == null ? null : Timestamp.valueOf(expires), now(), now());
                long id = jdbc.queryForObject("SELECT id FROM links WHERE code = ?", Long.class, code);
                event("link.created", merchantId, code, productId == null ? "amount" : "product");
                return link(merchantId, id);
            } catch (DuplicateKeyException e) {
                // code taken: try another
            }
        }
        throw new IllegalStateException("Please try again");
    }

    public Map<String, Object> setLinkStatus(long merchantId, long id, boolean active) {
        int n = jdbc.update("UPDATE links SET status = ?, updated_at = ? WHERE id = ? AND merchant_id = ? AND status <> 'USED'", active ? "ACTIVE" : "DISABLED", now(), id, merchantId);
        if (n == 0) {
            link(merchantId, id);   // not found -> 404
            throw new IllegalStateException("A single-use link that has been paid cannot be switched on again");
        }
        return link(merchantId, id);
    }

    // ─── public lookups (store pages) ────────────────────────────────────────

    public Optional<Map<String, Object>> publicStore(String slug) {
        return jdbc.queryForList("SELECT s.*, m.business_name, m.psp_status FROM stores s JOIN merchants m ON m.merchant_id = s.merchant_id WHERE s.slug = ?",
                slug == null ? "" : slug.toLowerCase(Locale.ROOT)).stream().findFirst();
    }

    public Optional<Map<String, Object>> publicProduct(long storeId, String slug) {
        return jdbc.queryForList("SELECT * FROM products WHERE store_id = ? AND slug = ?", storeId, slug).stream().findFirst();
    }

    public Optional<Map<String, Object>> publicLink(String code) {
        if (code == null || !code.matches("[" + ALPHABET + "]{8}")) return Optional.empty();
        return jdbc.queryForList("SELECT * FROM links WHERE code = ?", code).stream().findFirst();
    }

    public Optional<Map<String, Object>> publicStoreOf(long merchantId) {
        return jdbc.queryForList("SELECT s.*, m.business_name, m.psp_status FROM stores s JOIN merchants m ON m.merchant_id = s.merchant_id WHERE s.merchant_id = ?",
                merchantId).stream().findFirst();
    }

    public List<Map<String, Object>> publicProducts(long merchantId) {
        return jdbc.queryForList("SELECT * FROM products WHERE merchant_id = ? AND status = 'ACTIVE' ORDER BY id DESC LIMIT 200", merchantId);
    }

    public Optional<Map<String, Object>> publicProductById(long id) {
        return jdbc.queryForList("SELECT * FROM products WHERE id = ?", id).stream().findFirst();
    }

    static boolean bool(Object o) {
        return Boolean.TRUE.equals(o) || Integer.valueOf(1).equals(o) || "true".equals(String.valueOf(o)) || (o instanceof Number n && n.intValue() == 1);
    }

    /**
     * The merchant's customers, worked out from its paid orders when asked: paid orders only (pending, failed and
     * cancelled ones never count), and the amount spent is net of refunds paid out. Grouped by phone number.
     */
    public List<Map<String, Object>> customers(long merchantId, String q) {
        StringBuilder sql = new StringBuilder("SELECT customer_phone AS phone, MAX(customer_name) AS name, COUNT(*) AS orders, "
                + "GROUP_CONCAT(DISTINCT currency) AS currencies, MIN(paid_at) AS first_order_at, MAX(paid_at) AS last_order_at FROM orders "
                + "WHERE merchant_id = ? AND payment_status = 'PAID' AND customer_phone IS NOT NULL");
        List<Object> args = new ArrayList<>(List.of(merchantId));
        if (q != null && !q.isBlank()) {
            sql.append(" AND (customer_name LIKE ? OR customer_phone LIKE ?)");
            String like = "%" + q.trim().replace("%", "") + "%";
            args.add(like);
            args.add(like);
        }
        sql.append(" GROUP BY customer_phone ORDER BY last_order_at DESC LIMIT 1000");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(sql.toString(), args.toArray())) {
            List<String> spent = new ArrayList<>();
            for (Map<String, Object> c : jdbc.queryForList("SELECT currency, SUM(amount_minor - refunded_minor) AS net FROM orders WHERE merchant_id = ? AND customer_phone = ? "
                    + "AND payment_status = 'PAID' GROUP BY currency ORDER BY currency", merchantId, r.get("phone"))) {
                spent.add(c.get("currency") + " " + major(((Number) c.get("net")).longValue()));
            }
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("name", r.get("name"));
            v.put("phone", r.get("phone"));
            v.put("orders", ((Number) r.get("orders")).longValue());
            v.put("spent", String.join(" · ", spent));
            v.put("firstOrderAt", String.valueOf(r.get("first_order_at")));
            v.put("lastOrderAt", String.valueOf(r.get("last_order_at")));
            out.add(v);
        }
        return out;
    }

    static String clip(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }
}
