package zw.co.payport.commerce;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The pages customers see: a merchant's mini-store, product pages, payment links and their order. All of it is
 * rendered from Commerce's own database, so the pages work while the PSP is down; only "Pay" needs the PSP, and if it
 * cannot be reached the order is kept and the customer can pay a moment later. Customers are always sent to the
 * PSP's hosted checkout to pay: card details never touch these pages.
 */
@Controller
public class Storefront {

    private final Shop shop;
    private final Payments payments;
    private final Settings settings;
    private final Carts carts;
    private final DeliveryProvider.Registry delivery;
    private final Domains domains;
    @org.springframework.beans.factory.annotation.Autowired
    private Insights insights;
    @org.springframework.beans.factory.annotation.Autowired
    private Catalog catalog;

    @org.springframework.beans.factory.annotation.Autowired Growth growth;
    @org.springframework.beans.factory.annotation.Autowired Options options;
    @org.springframework.beans.factory.annotation.Autowired Ai ai;
    @org.springframework.beans.factory.annotation.Autowired Shoppers shoppers;
    @org.springframework.beans.factory.annotation.Autowired Marketing marketing;

    /** The signed-in shopper on this request (the store's own session cookie), if any. */
    Optional<Shoppers.Shopper> shopper() {
        var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra) || sra.getRequest().getCookies() == null) return Optional.empty();
        for (jakarta.servlet.http.Cookie c : sra.getRequest().getCookies())
            if (Shoppers.COOKIE.equals(c.getName())) return shoppers.current(c.getValue());
        return Optional.empty();
    }

    @org.springframework.beans.factory.annotation.Autowired Chats chats;

    Map<String, Object> meModel() {
        return shopper().<Map<String, Object>>map(s -> new LinkedHashMap<>(Map.of("first", s.firstName(), "name", s.name() == null ? "" : s.name(),
                "phone", s.phone() == null ? "" : s.phone().replaceFirst("^\\+263", "0"), "id", s.id(), "unread", chats.unreadForShopper(s.id())))).orElse(null);
    }
    @org.springframework.beans.factory.annotation.Value("${commerce.directory-announcement:Free delivery this weekend at selected stores}") String directoryAnnouncement;

    public Storefront(Shop shop, Payments payments, Settings settings, Carts carts, DeliveryProvider.Registry delivery, Domains domains) {
        this.delivery = delivery;
        this.domains = domains;
        this.shop = shop;
        this.payments = payments;
        this.settings = settings;
        this.carts = carts;
    }

    /** The cart cookie: random, HttpOnly, 30 days; only its hash is stored. */
    String cartToken(String current, HttpServletResponse res) {
        if (Carts.valid(current)) return current;
        String t = Shop.token() + Shop.token();
        res.addHeader("Set-Cookie", org.springframework.http.ResponseCookie.from(Carts.COOKIE, t).httpOnly(true).secure(settings.publicUrl.startsWith("https"))
                .sameSite("Lax").path("/").maxAge(java.time.Duration.ofDays(30)).build().toString());
        return t;
    }

    @org.springframework.web.bind.annotation.ModelAttribute("publicUrl")
    public String publicUrl() {
        return settings.publicUrl;
    }

    /** The store colour as a stylesheet (pages allow styles from this site only, never inline). */
    @GetMapping(value = "/theme.css", produces = "text/css")
    public ResponseEntity<String> theme(@RequestParam(name = "c", defaultValue = "0B6E4F") String c) {
        String hex = c.matches("[0-9A-Fa-f]{6}") ? c : "0B6E4F";
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic()).body(":root{--brand:#" + hex + "}");
    }

    /** The header for pages of a merchant without a store record (every key present: templates read them all). */
    static Map<String, Object> defaultStore() {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("name", "Payport");
        m.put("slug", null);
        m.put("about", null);
        m.put("color", "#0B6E4F");
        m.put("logo", null);
        m.put("whatsapp", null);
        m.put("open", false);
        m.put("cartCount", 0);
        m.put("design", "product");
        m.put("banner", null);
        m.put("tagline", null);
        m.put("announcement", null);
        m.put("pickup", null);
        m.put("delivers", null);
        return m;
    }

    /** What the share buttons need (WhatsApp, Facebook, X, email, copy, QR). */
    Map<String, Object> share(String path, String text) {
        String url = settings.publicUrl + path;
        String u = enc(url), t = enc(text);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", url);
        m.put("text", text);
        m.put("whatsapp", "https://wa.me/?text=" + enc(text + " " + url));
        m.put("facebook", "https://www.facebook.com/sharer/sharer.php?u=" + u);
        m.put("x", "https://twitter.com/intent/tweet?url=" + u + "&text=" + t);
        m.put("email", "mailto:?subject=" + t + "&body=" + enc(text + "\n" + url));
        m.put("qr", "/qr.svg?path=" + enc(path));
        return m;
    }

    static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String price(long minor, String ccy) {
        return ccy + " " + Shop.major(minor);
    }

    Map<String, Object> storeModel(Map<String, Object> s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.get("name"));
        m.put("slug", s.get("slug"));
        m.put("about", s.get("about"));
        m.put("color", s.get("theme_color"));
        m.put("logo", s.get("logo_image") == null ? null : "/img/" + s.get("logo_image"));
        m.put("whatsapp", s.get("whatsapp") == null ? null : "https://wa.me/" + String.valueOf(s.get("whatsapp")).replace("+", ""));
        m.put("open", "OPEN".equals(s.get("status")) && "ACTIVE".equals(s.get("psp_status") == null ? "ACTIVE" : s.get("psp_status")));
        m.put("cartCount", 0);
        m.put("design", s.get("design") == null ? "PRODUCT" : String.valueOf(s.get("design")).toLowerCase(Locale.ROOT));
        m.put("banner", s.get("banner_image") == null ? null : "/img/" + s.get("banner_image"));
        m.put("tagline", s.get("tagline"));
        m.put("announcement", s.get("announcement"));
        m.put("pickup", Shop.bool(s.get("pickup_enabled")) ? (s.get("pickup_note") == null ? "Pickup available" : "Pickup: " + s.get("pickup_note")) : null);
        m.put("delivers", Shop.bool(s.get("delivery_enabled")) ? (s.get("delivery_note") == null ? "Delivery available" : "Delivery: " + s.get("delivery_note")) : null);
        return m;
    }

    Map<String, Object> productModel(Map<String, Object> p, String storeSlug) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.get("name"));
        m.put("slug", p.get("slug"));
        m.put("description", p.get("description"));
        m.put("price", price(((Number) p.get("price_minor")).longValue(), (String) p.get("currency")));
        m.put("image", p.get("image_id") == null ? null : "/img/" + p.get("image_id"));
        m.put("url", "/s/" + storeSlug + "/" + p.get("slug"));
        Integer stock = p.get("stock") == null ? null : ((Number) p.get("stock")).intValue();
        m.put("soldOut", stock != null && stock == 0);
        m.put("was", p.get("compare_at_minor") == null ? null : price(((Number) p.get("compare_at_minor")).longValue(), (String) p.get("currency")));
        m.put("service", "SERVICE".equals(p.get("kind")));
        m.put("few", stock != null && stock > 0 && stock <= 5 ? "Only " + stock + " left" : null);
        m.put("rating", null);
        m.put("id", ((Number) p.get("id")).longValue());
        return m;
    }

    String missing(Model model, HttpServletResponse res, String what) {
        res.setStatus(404);
        model.addAttribute("title", what);
        return "missing";
    }

    /** The site's front page, or the store itself on a verified custom domain. */
    @GetMapping("/")
    public String home(jakarta.servlet.http.HttpServletRequest req, @org.springframework.web.bind.annotation.CookieValue(name = Carts.COOKIE, required = false) String cart,
                       @RequestParam(name = "c", required = false) String category, Model model, HttpServletResponse res) {
        Optional<String> slug = domains.storeFor(req.getHeader("Host"));
        if (slug.isPresent()) return store(slug.get(), cart, category, model, res);
        String type = req.getParameter("t"), q = req.getParameter("q");
        if (type != null && !Catalog.TYPES.containsKey(type)) type = null;
        String query = q == null || q.isBlank() ? null : q.trim().length() > 200 ? q.trim().substring(0, 200) : q.trim();
        List<Map<String, Object>> all = catalog.directory(null, null);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> st : all) counts.merge(String.valueOf(st.get("business_type")), 1, Integer::sum);

        // The search box is Payport AI's shopping assistant: plain words find matching items and stores.
        List<Map<String, Object>> found = new ArrayList<>();
        java.util.Set<String> foundStores = new java.util.LinkedHashSet<>();
        Ai.Query aq = null;
        if (query != null) {
            aq = ai.read(query);
            for (Map<String, Object> p : catalog.searchProducts(aq, 24)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", p.get("name"));
                m.put("price", price(((Number) p.get("price_minor")).longValue(), (String) p.get("currency")));
                m.put("was", p.get("compare_at_minor") == null ? null : price(((Number) p.get("compare_at_minor")).longValue(), (String) p.get("currency")));
                m.put("image", p.get("image_id") == null ? null : "/img/" + p.get("image_id"));
                m.put("url", "/s/" + p.get("store_slug") + "/" + p.get("slug"));
                m.put("store", p.get("store_name"));
                m.put("cod", Shop.bool(p.get("cod_enabled")));
                found.add(m);
                foundStores.add(String.valueOf(p.get("store_slug")));
            }
        }
        List<Map<String, Object>> stores = new ArrayList<>();
        List<Map<String, Object>> listed = query == null ? catalog.directory(type, null) : new ArrayList<>(catalog.directory(type, query));
        if (query != null) {
            java.util.Set<String> have = new java.util.HashSet<>();
            for (Map<String, Object> st : listed) have.add(String.valueOf(st.get("slug")));
            for (Map<String, Object> st : all)
                if (foundStores.contains(String.valueOf(st.get("slug"))) && !have.contains(String.valueOf(st.get("slug")))
                        && (type == null || type.equals(st.get("business_type")))) listed.add(st);
        }
        // Featured: Payport's own picks first, then the best sellers of the last 30 days (a daily order for ties).
        // Featured stores are not repeated under All stores.
        List<Map<String, Object>> featured = new ArrayList<>();
        java.util.Set<String> featuredSlugs = new java.util.HashSet<>();
        if (query == null && type == null && all.size() > 3) {
            String day = java.time.LocalDate.now(Growth.ZONE).toString();
            List<Map<String, Object>> pick = new ArrayList<>(all);
            pick.sort(java.util.Comparator.<Map<String, Object>>comparingInt(st -> Shop.bool(st.get("directory_featured")) ? 0 : 1)
                    .thenComparingInt(st -> st.get("directory_rank") == null ? 100000 : ((Number) st.get("directory_rank")).intValue())
                    .thenComparingLong(st -> -((Number) st.get("orders30")).longValue())
                    .thenComparing(st -> Shop.sha256(day + st.get("slug"))));
            for (Map<String, Object> st : pick.subList(0, 3)) {
                featured.add(storeCard(st));
                featuredSlugs.add(String.valueOf(st.get("slug")));
            }
        }
        for (Map<String, Object> st : listed) if (!featuredSlugs.contains(String.valueOf(st.get("slug")))) stores.add(storeCard(st));
        // Top rated: the stores customers rate best (verified buyers' reviews), so good sellers are easy to find.
        List<Map<String, Object>> topRated = new ArrayList<>();
        if (query == null && type == null) {
            Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
            for (Map<String, Object> st : all) if (st.get("id") != null) byId.put(((Number) st.get("id")).longValue(), st);
            for (Map<String, Object> r : shop.jdbc().queryForList("SELECT x.store_id, AVG(x.r) AS a, COUNT(*) AS n FROM (SELECT store_id, rating AS r FROM reviews WHERE hidden = FALSE "
                    + "UNION ALL SELECT store_id, stars FROM store_ratings) x GROUP BY x.store_id HAVING AVG(x.r) >= 4 ORDER BY a DESC, n DESC LIMIT 8")) {
                Map<String, Object> st = byId.get(((Number) r.get("store_id")).longValue());
                if (st != null) topRated.add(storeCard(st));
            }
        }
        model.addAttribute("topRated", topRated);
        List<Map<String, Object>> tiles = new ArrayList<>();
        tiles.add(Map.of("code", "", "name", "All", "count", all.size(), "on", type == null, "icon", "ALL", "url", "/"));
        for (String code : List.of("FOOD", "DRINKS", "CLOTHING", "SERVICES", "BEAUTY", "ELECTRONICS", "SHOP", "HOME", "CRAFTS", "OTHER")) {
            if (!counts.containsKey(code)) continue;
            tiles.add(Map.of("code", code, "name", SHORT.get(code), "count", counts.get(code), "on", code.equals(type), "icon", code, "url", "/?t=" + code));
        }
        // Picked for a signed-in customer from what they and similar customers buy (home page, no search).
        List<Map<String, Object>> picked = new ArrayList<>();
        if (query == null && type == null) shopper().ifPresent(s -> {
            for (Map<String, Object> p : insights.forShopper(s.id(), null, 8)) {
                Map<String, Object> m = productModel(p, (String) p.get("store_slug"));
                m.put("store", p.get("store_name"));
                picked.add(m);
            }
        });
        model.addAttribute("picked", picked);
        model.addAttribute("announcement", directoryAnnouncement.isBlank() ? null : directoryAnnouncement);
        model.addAttribute("signIn", "/signin");
        model.addAttribute("me", meModel());
        model.addAttribute("tiles", tiles);
        model.addAttribute("featured", featured);
        model.addAttribute("stores", stores);
        model.addAttribute("openCount", stores.size() + featured.size());
        model.addAttribute("type", type == null ? null : SHORT.get(type));
        model.addAttribute("typeCode", type);
        model.addAttribute("q", query);
        model.addAttribute("found", found);
        model.addAttribute("answer", aq == null ? null : ai.answer(aq, found, stores.size()));
        model.addAttribute("aiOn", ai.modelOn());
        model.addAttribute("title", "Payport Store");
        return "home";
    }

    /** Short names on the category tiles and store cards. */
    static final Map<String, String> SHORT = Map.of("SHOP", "Shops", "CLOTHING", "Clothing", "FOOD", "Food", "DRINKS", "Drinks", "BEAUTY", "Beauty",
            "ELECTRONICS", "Electronics", "HOME", "Home", "CRAFTS", "Crafts", "SERVICES", "Services", "OTHER", "Other");

    Map<String, Object> storeCard(Map<String, Object> st) {
        String code = String.valueOf(st.get("business_type"));
        long n = ((Number) st.get("products")).longValue();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", st.get("name"));
        m.put("url", "/s/" + st.get("slug"));
        m.put("typeCode", Catalog.TYPES.containsKey(code) ? code : "OTHER");
        m.put("meta", SHORT.getOrDefault(code, "Other") + " · " + n + (n == 1 ? " item" : " items"));
        m.put("logo", st.get("logo_image") == null ? null : "/img/" + st.get("logo_image"));
        m.put("banner", st.get("banner_image") == null ? null : "/img/" + st.get("banner_image"));
        m.put("cod", Shop.bool(st.get("cod_enabled")));
        m.put("sale", Shop.bool(st.get("on_sale")));
        m.put("rating", st.get("id") == null ? null : growth.storeRating(((Number) st.get("id")).longValue()));
        return m;
    }

    /** The cart button on the front page: the cart the customer last used. */
    @GetMapping("/cart")
    public String lastCart(@org.springframework.web.bind.annotation.CookieValue(name = Carts.COOKIE, required = false) String cart) {
        if (cart == null || cart.isBlank()) return "redirect:/";
        return shop.jdbc().queryForList("SELECT s.slug FROM carts c JOIN stores s ON s.id = c.store_id WHERE c.token_hash = ? "
                        + "AND EXISTS (SELECT 1 FROM cart_items i WHERE i.cart_id = c.id) ORDER BY c.updated_at DESC LIMIT 1", String.class, Shop.sha256(cart))
                .stream().findFirst().map(sl -> "redirect:/s/" + sl + "/cart").orElse("redirect:/");
    }

    /** Caddy asks before it requests a certificate: only verified custom domains get one (local calls only). */
    @GetMapping("/internal/tls/allowed")
    public ResponseEntity<String> tlsAllowed(@RequestParam String domain, jakarta.servlet.http.HttpServletRequest req) {
        String ip = req.getRemoteAddr();
        boolean local = "127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip);
        return local && domains.allowed(domain) ? ResponseEntity.ok("yes") : ResponseEntity.status(404).body("no");
    }

    @GetMapping("/s/{slug}")
    public String store(@PathVariable String slug, @org.springframework.web.bind.annotation.CookieValue(name = Carts.COOKIE, required = false) String cart,
                        @RequestParam(name = "c", required = false) String category, Model model, HttpServletResponse res) {
        return store(slug, cart, category, null, model, res);
    }

    String store(String slug, String cart, String category, String q, Model model, HttpServletResponse res) {
        Optional<Map<String, Object>> s = shop.publicStore(slug);
        if (s.isEmpty()) return missing(model, res, "This store does not exist");
        Map<String, Object> st = s.get();
        long merchant = ((Number) st.get("merchant_id")).longValue();
        List<Map<String, Object>> items = new ArrayList<>();
        java.util.TreeSet<String> categories = new java.util.TreeSet<>();
        long storeId = ((Number) st.get("id")).longValue();
        List<Map<String, Object>> cols = catalog.publicCollections(storeId);
        boolean byCollection = category != null && cols.stream().anyMatch(c -> category.equals(c.get("slug")));
        List<Long> inCol = byCollection ? catalog.productsIn(storeId, category) : List.of();
        List<Map<String, Object>> featured = new ArrayList<>();
        Map<Long, String> ratings = growth.productRatings(storeId);
        for (Map<String, Object> p : shopProducts(merchant)) {
            if (p.get("category") != null) categories.add((String) p.get("category"));
            boolean pick = byCollection ? inCol.contains(((Number) p.get("id")).longValue())
                    : category == null || category.isBlank() || category.equals(p.get("category"));
            Map<String, Object> pm = productModel(p, (String) st.get("slug"));
            pm.put("rating", ratings.get(((Number) p.get("id")).longValue()));
            if (pick && matches(p, q)) items.add(pm);
            if (Shop.bool(p.get("featured"))) featured.add(pm);
        }
        model.addAttribute("collections", cols);
        model.addAttribute("featured", (category == null || category.isBlank()) && (q == null || q.isBlank()) ? featured : List.of());
        model.addAttribute("q", q == null || q.isBlank() ? null : q.trim());
        model.addAttribute("categories", categories);
        model.addAttribute("category", category == null || category.isBlank() ? null : category);
        Map<String, Object> sm = storeModel(st);
        sm.put("cartCount", carts.count(((Number) st.get("id")).longValue(), cart));
        sm.put("rating", growth.storeRating(storeId));
        Optional<Shoppers.Shopper> who = shopper();
        sm.put("favourite", who.map(x -> shoppers.favourite(x, storeId)).orElse(false));
        List<Map<String, Object>> forYou = new ArrayList<>();
        if ((category == null || category.isBlank()) && (q == null || q.isBlank())) who.ifPresent(x -> {
            for (Map<String, Object> p : insights.forShopper(x.id(), merchant, 4)) forYou.add(productModel(p, (String) st.get("slug")));
        });
        model.addAttribute("forYou", forYou);
        model.addAttribute("me", meModel());
        model.addAttribute("store", sm);
        model.addAttribute("products", items);
        model.addAttribute("summary", growth.summary(true, storeId));
        model.addAttribute("reviews", growth.storeReviews(storeId));
        model.addAttribute("canRate", who.map(x -> growth.canRateStore(x.id(), storeId)).orElse(false));
        model.addAttribute("myRating", who.map(x -> growth.myStoreRating(x.id(), storeId)).orElse(null));
        model.addAttribute("share", share("/s/" + st.get("slug"), st.get("name") + " on Payport"));
        model.addAttribute("title", st.get("name"));
        model.addAttribute("description", st.get("about") == null ? "Shop " + st.get("name") + " and pay securely with Payport." : st.get("about"));
        return "store";
    }

    /** Search: every word must appear in the name, description or category (any case). */
    static boolean matches(Map<String, Object> p, String q) {
        if (q == null || q.isBlank()) return true;
        String hay = (p.get("name") + " " + (p.get("description") == null ? "" : p.get("description")) + " " + (p.get("category") == null ? "" : p.get("category"))).toLowerCase(Locale.ROOT);
        for (String w : q.toLowerCase(Locale.ROOT).trim().split("\\s+")) if (!w.isEmpty() && !hay.contains(w)) return false;
        return true;
    }

    @GetMapping("/s/{slug}/search")
    public String search(@PathVariable String slug, @RequestParam(name = "q", required = false) String q,
                         @org.springframework.web.bind.annotation.CookieValue(name = Carts.COOKIE, required = false) String cart, Model model, HttpServletResponse res) {
        return store(slug, cart, null, q == null ? null : q.length() > 80 ? q.substring(0, 80) : q, model, res);
    }

    List<Map<String, Object>> shopProducts(long merchantId) {
        return shop.publicProducts(merchantId);
    }

    @GetMapping("/s/{slug}/{product}")
    public String product(@PathVariable String slug, @PathVariable String product, @org.springframework.web.bind.annotation.CookieValue(name = Carts.COOKIE, required = false) String cart,
                          @RequestParam(name = "code", required = false) String code, @RequestParam(name = "added", required = false) String added,
                          Model model, HttpServletResponse res) {
        model.addAttribute("added", added != null);
        if ("cart".equals(product)) return cartPage(slug, cart, null, model, res, code);
        Optional<Map<String, Object>> s = shop.publicStore(slug);
        if (s.isEmpty()) return missing(model, res, "This store does not exist");
        Optional<Map<String, Object>> p = shop.publicProduct(((Number) s.get().get("id")).longValue(), product).filter(x -> "ACTIVE".equals(x.get("status")));
        if (p.isEmpty()) return missing(model, res, "This product is not available");
        return productPage(s.get(), p.get(), model, null, cart);
    }

    String productPage(Map<String, Object> st, Map<String, Object> p, Model model, String problem) {
        return productPage(st, p, model, problem, null);
    }

    String productPage(Map<String, Object> st, Map<String, Object> p, Model model, String problem, String cart) {
        Map<String, Object> pm = productModel(p, (String) st.get("slug"));
        long pid = ((Number) p.get("id")).longValue();
        pm.put("rating", growth.ratingOf(pid));
        pm.put("images", shop.images(pid, (String) p.get("image_id")).stream().map(i -> "/img/" + i).toList());
        model.addAttribute("summary", growth.summary(false, pid));
        model.addAttribute("saved", shopper().map(x -> shoppers.saved(x, pid)).orElse(false));
        model.addAttribute("canReview", shopper().flatMap(x -> growth.reviewableOrder(x.id(), pid)).isPresent());
        if (!model.containsAttribute("reviewProblem")) model.addAttribute("reviewProblem", null);
        if (!model.containsAttribute("added")) model.addAttribute("added", false);
        model.addAttribute("me", meModel());
        model.addAttribute("chatText", enc("Hi, I am interested in " + p.get("name") + " (" + settings.publicUrl + pm.get("url") + ")"));
        model.addAttribute("reviews", growth.reviewsOf(((Number) p.get("id")).longValue()));
        Map<String, Object> sm = storeModel(st);
        sm.put("cartCount", carts.count(((Number) st.get("id")).longValue(), cart));
        model.addAttribute("store", sm);
        model.addAttribute("product", pm);
        model.addAttribute("key", Shop.token() + Shop.token());
        model.addAttribute("problem", problem);
        model.addAttribute("share", share((String) pm.get("url"), p.get("name") + " - " + pm.get("price") + " at " + st.get("name")));
        model.addAttribute("title", p.get("name") + " | " + st.get("name"));
        model.addAttribute("description", p.get("description") == null ? pm.get("price") + " at " + st.get("name") : p.get("description"));
        model.addAttribute("image", pm.get("image"));
        List<Map<String, Object>> also = new ArrayList<>();
        for (Map<String, Object> a : insights.alsoBought(((Number) st.get("merchant_id")).longValue(), ((Number) p.get("id")).longValue(), 4)) also.add(productModel(a, (String) st.get("slug")));
        model.addAttribute("also", also);
        return "product";
    }

    /** "Add to cart" (or "Buy now", which goes straight to the cart to choose pickup or delivery). */
    @PostMapping("/s/{slug}/{product}/buy")
    public String buy(@PathVariable String slug, @PathVariable String product, @RequestParam(defaultValue = "1") String qty,
                      @RequestParam(name = "go", required = false) String go,
                      @org.springframework.web.bind.annotation.CookieValue(name = Carts.COOKIE, required = false) String cart,
                      Model model, HttpServletResponse res) {
        Optional<Map<String, Object>> s = shop.publicStore(slug);
        if (s.isEmpty()) return missing(model, res, "This store does not exist");
        Map<String, Object> st = s.get();
        Optional<Map<String, Object>> p = shop.publicProduct(((Number) st.get("id")).longValue(), product).filter(x -> "ACTIVE".equals(x.get("status")));
        if (p.isEmpty()) return missing(model, res, "This product is not available");
        if (!Boolean.TRUE.equals(storeModel(st).get("open"))) return productPage(st, p.get(), model, "This store is not taking orders at the moment.", cart);
        int q;
        try {
            q = Integer.parseInt(qty.trim());
        } catch (NumberFormatException e) {
            q = 0;
        }
        if (q < 1 || q > 20) return productPage(st, p.get(), model, "Choose a quantity from 1 to 20.", cart);
        String token = cartToken(cart, res);
        try {
            carts.set(((Number) st.get("id")).longValue(), token, ((Number) p.get().get("id")).longValue(), q, true);
        } catch (IllegalArgumentException e) {
            return productPage(st, p.get(), model, e.getMessage(), token);
        }
        // A signed-in shopper's cart can be reminded about later (only with their agreement, see Marketing).
        final String ct = token;
        shopper().ifPresent(x -> shop.jdbc().update("UPDATE carts SET shopper_id = ?, reminded_at = NULL WHERE token_hash = ? AND store_id = ?",
                x.id(), Shop.sha256(ct), st.get("id")));
        if ("stay".equals(go)) return "redirect:/s/" + st.get("slug") + "/" + p.get().get("slug") + "?added=1";
        return "redirect:/s/" + st.get("slug") + "/cart";
    }

    /** Change a quantity in the cart (0 removes the item). */
    @PostMapping("/s/{slug}/cart/{productId}")
    public String cartSet(@PathVariable String slug, @PathVariable long productId, @RequestParam(defaultValue = "0") String qty,
                          @org.springframework.web.bind.annotation.CookieValue(name = Carts.COOKIE, required = false) String cart,
                          Model model, HttpServletResponse res) {
        Optional<Map<String, Object>> s = shop.publicStore(slug);
        if (s.isEmpty() || !Carts.valid(cart)) return "redirect:/s/" + slug;
        int q;
        try { q = Integer.parseInt(qty.trim()); } catch (NumberFormatException e) { q = 0; }
        try {
            carts.set(((Number) s.get().get("id")).longValue(), cart, productId, q, false);
        } catch (IllegalArgumentException e) {
            return cartPage(slug, cart, e.getMessage(), model, res);
        }
        return "redirect:/s/" + s.get().get("slug") + "/cart";
    }

    /** The delivery fee for a cart: the store's fee, or nothing above the free-delivery amount. */
    long deliveryFee(Map<String, Object> st, long itemsMinor) {
        return delivery.of(st.get("delivery_provider")).quote(st, itemsMinor, null).feeMinor();
    }

    String cartPage(String slug, String cart, String problem, Model model, HttpServletResponse res) {
        return cartPage(slug, cart, problem, model, res, null);
    }

    String cartPage(String slug, String cart, String problem, Model model, HttpServletResponse res, String code) {
        Optional<Map<String, Object>> s = shop.publicStore(slug);
        if (s.isEmpty()) return missing(model, res, "This store does not exist");
        Map<String, Object> st = s.get();
        long storeId = ((Number) st.get("id")).longValue();
        Optional<Carts.View> v = carts.view(storeId, cart);
        Map<String, Object> sm = storeModel(st);
        sm.put("cartCount", v.map(Carts.View::count).orElse(0));
        Map<String, Object> cm = new LinkedHashMap<>();
        String ccy = v.map(Carts.View::currency).orElse(null);
        long items = v.map(Carts.View::itemsMinor).orElse(0L);
        cm.put("lines", v.map(x -> x.lines().stream().map(l -> Carts.line(l, ccy)).toList()).orElse(List.of()));
        cm.put("empty", v.map(x -> x.lines().isEmpty()).orElse(true));
        cm.put("ok", v.map(Carts.View::ok).orElse(false));
        cm.put("items", ccy == null ? null : price(items, ccy));
        boolean pickup = Shop.bool(st.get("pickup_enabled")), delivery = Shop.bool(st.get("delivery_enabled"));
        cm.put("pickup", pickup);
        cm.put("pickupNote", st.get("pickup_note"));
        cm.put("delivery", delivery);
        cm.put("deliveryNote", st.get("delivery_note"));
        long off = 0;
        if (code != null && !code.isBlank() && ccy != null) {
            try {
                Catalog.Applied a = catalog.apply(storeId, code, items);
                off = a.discountMinor();
                cm.put("code", a.code());
                cm.put("discount", price(off, ccy));
            } catch (IllegalArgumentException e) {
                if (problem == null) problem = e.getMessage();
            }
        }
        cm.putIfAbsent("code", null);
        cm.putIfAbsent("discount", null);
        items -= off;
        boolean servicesOnly = v.map(x -> !x.lines().isEmpty() && x.lines().stream().allMatch(Carts.Line::service)).orElse(false);
        cm.put("servicesOnly", servicesOnly);
        cm.put("cod", Shop.bool(st.get("cod_enabled")));
        long merchantId = ((Number) st.get("merchant_id")).longValue();
        cm.put("methods", options.choices(merchantId));
        List<Map<String, Object>> zl = new ArrayList<>();
        if (delivery && ccy != null) for (Map<String, Object> z : growth.publicZones(storeId)) {
            long zf = Growth.zoneFee(z, items);
            zl.add(Map.of("id", z.get("id"), "label", z.get("name") + " · " + (zf == 0 ? "free delivery" : price(zf, ccy) + " delivery") + " · total " + price(items + zf, ccy)));
        }
        cm.put("zones", zl);
        int minutes = v.map(this::bookingMinutes).orElse(0);
        boolean booking = minutes > 0 && growth.takesBookings(storeId);
        cm.put("booking", booking);
        List<Map<String, Object>> days = new ArrayList<>();
        if (booking) {
            Map<String, List<Map<String, Object>>> byDay = new LinkedHashMap<>();
            for (Growth.Slot sl : growth.slots(storeId, minutes))
                byDay.computeIfAbsent(sl.day(), d -> new ArrayList<>()).add(Map.of("value", sl.value(), "label", sl.start().toLocalTime().toString()));
            byDay.forEach((d, t) -> days.add(Map.of("day", d, "times", t)));
        }
        cm.put("days", days);
        cm.put("total", ccy == null ? null : price(items, ccy));
        long fee = deliveryFee(st, items);
        cm.put("fee", ccy == null ? null : fee == 0 ? "Free" : price(fee, ccy));
        cm.put("totalPickup", ccy == null ? null : price(items, ccy));
        cm.put("totalDelivery", ccy == null ? null : price(items + fee, ccy));
        model.addAttribute("store", sm);
        model.addAttribute("cart", cm);
        model.addAttribute("key", Shop.token() + Shop.token());
        model.addAttribute("problem", problem);
        Optional<Shoppers.Shopper> who = shopper();
        model.addAttribute("me", meModel());
        model.addAttribute("addresses", who.map(x -> shoppers.addresses(x)).orElse(List.of()));
        long mid = ((Number) st.get("merchant_id")).longValue();
        String offer = null;
        if (who.isPresent() && ccy != null && marketing.loyaltyOn(mid)) {
            int bal = marketing.points(mid, Marketing.holder(who.get()));
            int use = Marketing.usable(bal, v.map(Carts.View::itemsMinor).orElse(0L));
            if (use > 0) offer = "Use " + use + " of your " + bal + " points (−" + price(use, ccy) + ")";
            else if (bal > 0) cm.put("pointsNote", "You have " + bal + " points here");
        }
        cm.put("pointsOffer", offer);
        cm.putIfAbsent("pointsNote", null);
        cm.put("askOffers", who.map(x -> !marketing.consented(mid, x.phone())).orElse(true));
        model.addAttribute("title", "Cart | " + st.get("name"));
        return "cart";
    }

    /** Minutes of appointment the cart's services need (60 each unless the merchant set the length). */
    int bookingMinutes(Carts.View v) {
        int total = 0;
        for (Carts.Line l : v.lines()) {
            if (!l.service()) continue;
            Integer d = jdbc().queryForList("SELECT duration_min FROM products WHERE id = ?", Integer.class, l.productId()).stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
            total += (d == null || d <= 0 ? Growth.DEFAULT_DURATION : d) * l.quantity();
        }
        return Math.min(total, 12 * 60);
    }

    org.springframework.jdbc.core.JdbcTemplate jdbc() {
        return shop.jdbc();
    }

    /** Pay for the cart: one order with every item, the delivery choice and the fee; then the PSP's checkout. */
    @PostMapping("/s/{slug}/checkout")
    public String checkout(@PathVariable String slug, @RequestParam(required = false) String name, @RequestParam(required = false) String phone,
                           @RequestParam(required = false) String method, @RequestParam(required = false) String address,
                           @RequestParam(required = false) String note, @RequestParam String k,
                           @RequestParam(required = false) String code, @RequestParam(required = false) String pay,
                           @RequestParam(required = false) String zone, @RequestParam(required = false) String slot,
                           @RequestParam(required = false) String points, @RequestParam(required = false) String offers,
                           @org.springframework.web.bind.annotation.CookieValue(name = Carts.COOKIE, required = false) String cart,
                           Model model, HttpServletResponse res) {
        Optional<Map<String, Object>> s = shop.publicStore(slug);
        if (s.isEmpty()) return missing(model, res, "This store does not exist");
        Map<String, Object> st = s.get();
        long storeId = ((Number) st.get("id")).longValue();
        if (!Boolean.TRUE.equals(storeModel(st).get("open"))) return cartPage(slug, cart, "This store is not taking orders at the moment.", model, res);
        Optional<Carts.View> v = carts.view(storeId, cart);
        if (v.isEmpty() || v.get().lines().isEmpty()) return cartPage(slug, cart, "Your cart is empty.", model, res);
        if (!v.get().ok()) return cartPage(slug, cart, "Change the items marked in your cart first.", model, res);
        boolean servicesOnly = v.get().lines().stream().allMatch(Carts.Line::service);
        String how = servicesOnly ? "NONE" : "DELIVERY".equals(method) ? "DELIVERY" : "PICKUP".equals(method) ? "PICKUP" : null;
        if (how == null || ("DELIVERY".equals(how) && !Shop.bool(st.get("delivery_enabled"))) || ("PICKUP".equals(how) && !Shop.bool(st.get("pickup_enabled"))))
            return cartPage(slug, cart, "Choose pickup or delivery.", model, res);
        Customer c;
        Optional<Shoppers.Shopper> signedIn = shopper();
        if ((name == null || name.isBlank()) && signedIn.isPresent()) name = signedIn.get().name();
        if ((phone == null || phone.isBlank()) && signedIn.isPresent()) phone = signedIn.get().phone();
        try {
            c = customer(name, phone, note);
        } catch (IllegalArgumentException e) {
            return cartPage(slug, cart, e.getMessage(), model, res);
        }
        if (c.name() == null || c.phone() == null) return cartPage(slug, cart, "Enter your name and phone number so the seller can reach you.", model, res);
        String addr = Shop.text(address, 300, false, "address");
        if ("DELIVERY".equals(how) && (addr == null || addr.length() < 6)) return cartPage(slug, cart, "Enter the delivery address.", model, res);
        long items = v.get().itemsMinor();
        Catalog.Applied discount = null;
        if (code != null && !code.isBlank()) {
            try {
                discount = catalog.apply(storeId, code, items);
            } catch (IllegalArgumentException e) {
                return cartPage(slug, cart, e.getMessage(), model, res, null);
            }
            items -= discount.discountMinor();
        }
        boolean cash = "CASH".equals(pay) && Shop.bool(st.get("cod_enabled"));
        long merchantId = ((Number) st.get("merchant_id")).longValue();
        // Loyalty points: a signed-in shopper may use theirs (whole hundreds, at most half the items).
        int usePoints = 0;
        if ("on".equals(points) && signedIn.isPresent() && marketing.loyaltyOn(merchantId)) {
            usePoints = Marketing.usable(marketing.points(merchantId, Marketing.holder(signedIn.get())), items);
            items -= usePoints;
        }
        String payChoice = pay != null && options.accepted(merchantId).contains(pay) ? pay : null;
        long fee;
        String zoneName = null;
        List<Map<String, Object>> zones = growth.publicZones(storeId);
        if ("DELIVERY".equals(how) && !zones.isEmpty()) {
            Optional<Map<String, Object>> z = growth.zone(storeId, zone);
            if (z.isEmpty()) return cartPage(slug, cart, "Choose your delivery area.", model, res, code);
            fee = Growth.zoneFee(z.get(), items);
            zoneName = (String) z.get().get("name");
        } else {
            DeliveryProvider.Quote quote = delivery.of(st.get("delivery_provider")).quote(st, items, addr);
            if ("DELIVERY".equals(how) && quote.problem() != null) return cartPage(slug, cart, quote.problem(), model, res);
            fee = "DELIVERY".equals(how) ? quote.feeMinor() : 0;
        }
        int minutes = bookingMinutes(v.get());
        Payments.Booking booking = null;
        if (minutes > 0 && growth.takesBookings(storeId)) {
            Optional<java.time.LocalDateTime> at = growth.validSlot(storeId, minutes, slot);
            if (at.isEmpty()) return cartPage(slug, cart, slot == null || slot.isBlank() ? "Choose a time for your appointment." : "That time is no longer free. Please choose another time.", model, res, code);
            booking = new Payments.Booking(at.get(), minutes);
        }
        List<Payments.Item> lines = v.get().lines().stream().map(l -> new Payments.Item(l.productId(), l.name(), l.unitMinor(), l.quantity())).toList();
        String summary = lines.size() == 1 ? lines.get(0).name() : lines.size() + " items from " + st.get("name");
        Payments.Placed placed;
        try {
            placed = payments.place(new Payments.NewOrder(((Number) st.get("merchant_id")).longValue(), storeId, null, lines.size() == 1 ? lines.get(0).productId() : null,
                    summary, v.get().count(), items + fee, v.get().currency(), c.name(), c.phone(), c.note(), k, lines, how, fee, "DELIVERY".equals(how) ? addr : null), booking);
        } catch (IllegalArgumentException e) {
            return cartPage(slug, cart, e.getMessage(), model, res);
        }
        carts.empty(v.get().cartId());
        final String claimed = "DELIVERY".equals(how) ? addr : null;
        final String placedRef = placed.orderRef();
        signedIn.ifPresent(x -> shoppers.claim(x, placedRef, claimed));
        if (!placed.existing() && usePoints > 0) marketing.redeem(merchantId, Marketing.holder(signedIn.get()), placedRef, usePoints);
        if ("on".equals(offers)) marketing.consent(merchantId, c.phone(), c.name());
        if (!placed.existing()) {
            if (discount != null) catalog.use(discount.id());
            payments.finish(placed.orderRef(), cash, discount == null ? null : discount.code(), discount == null ? 0 : discount.discountMinor(), cash ? null : payChoice, zoneName);
        }
        if (cash) return orderPage(placed.orderRef(), placed.viewToken(), null, model, res);
        return startPayment(placed, model, res);
    }

    record Customer(String name, String phone, String note) {}

    static Customer customer(String name, String phone, String note) {
        String n = Shop.text(name, 120, false, "name");
        String p = phone == null ? null : phone.replaceAll("[\\s()-]", "");
        if (p != null && p.isEmpty()) p = null;
        if (p != null && !p.matches("\\+?[0-9]{9,15}")) throw new IllegalArgumentException("Enter a valid phone number, or leave it empty.");
        return new Customer(n, p, Shop.text(note, 300, false, "note"));
    }

    /** Sends the customer to the PSP's checkout, or shows their saved order with what went wrong. */
    String startPayment(Payments.Placed placed, Model model, HttpServletResponse res) {
        Payments.Start start;
        try {
            start = payments.pay(placed.orderRef(), placed.viewToken());
        } catch (Shop.NotFound e) {
            return missing(model, res, "This order does not exist");
        }
        if (start.checkoutUrl() != null) return "redirect:" + start.checkoutUrl();
        return orderPage(placed.orderRef(), placed.viewToken(), start.problem(), model, res);
    }

    // ─── payment links ───────────────────────────────────────────────────────

    @GetMapping("/l/{code}")
    public String link(@PathVariable String code, Model model, HttpServletResponse res) {
        Optional<Map<String, Object>> l = shop.publicLink(code);
        if (l.isEmpty()) return missing(model, res, "This payment link does not exist");
        return linkPage(l.get(), model, null);
    }

    String linkPage(Map<String, Object> l, Model model, String problem) {
        long merchant = ((Number) l.get("merchant_id")).longValue();
        Map<String, Object> st = shop.publicStoreOf(merchant).orElse(Map.of());
        Map<String, Object> lm = new LinkedHashMap<>();
        lm.put("code", l.get("code"));
        lm.put("title", l.get("title"));
        lm.put("description", l.get("description"));
        lm.put("currency", l.get("currency"));
        Long amount = l.get("amount_minor") == null ? null : ((Number) l.get("amount_minor")).longValue();
        lm.put("fixed", amount != null);
        lm.put("price", amount == null ? null : price(amount, (String) l.get("currency")));
        lm.put("min", l.get("min_minor") == null ? null : Shop.major(((Number) l.get("min_minor")).longValue()));
        lm.put("max", l.get("max_minor") == null ? null : Shop.major(((Number) l.get("max_minor")).longValue()));
        lm.put("reference", l.get("reference"));
        lm.put("image", null);
        String status = Shop.effectiveStatus(l);
        boolean merchantOpen = !st.isEmpty() && Boolean.TRUE.equals(storeModel(st).get("open"));
        lm.put("open", "ACTIVE".equals(status) && merchantOpen);
        lm.put("closedReason", switch (status) {
            case "EXPIRED" -> "This payment link has expired.";
            case "USED" -> "This payment link has already been paid.";
            case "DISABLED" -> "This payment link has been switched off.";
            default -> merchantOpen ? null : "This merchant is not taking payments at the moment.";
        });
        if (l.get("product_id") != null) {
            shop.publicProductById(((Number) l.get("product_id")).longValue()).ifPresent(p -> lm.put("image", p.get("image_id") == null ? null : "/img/" + p.get("image_id")));
        }
        model.addAttribute("store", st.isEmpty() ? defaultStore() : storeModel(st));
        model.addAttribute("link", lm);
        model.addAttribute("key", Shop.token() + Shop.token());
        model.addAttribute("problem", problem);
        model.addAttribute("share", share("/l/" + l.get("code"), l.get("title") + (amount == null ? "" : " - " + lm.get("price"))));
        model.addAttribute("title", l.get("title") + (st.isEmpty() ? "" : " | " + st.get("name")));
        model.addAttribute("description", l.get("description") == null ? "Pay securely with Payport." : l.get("description"));
        model.addAttribute("image", lm.get("image"));
        return "link";
    }

    @PostMapping("/l/{code}/pay")
    public String payLink(@PathVariable String code, @RequestParam(required = false) String amount, @RequestParam(required = false) String name,
                          @RequestParam(required = false) String phone, @RequestParam(required = false) String note, @RequestParam String k,
                          Model model, HttpServletResponse res) {
        Optional<Map<String, Object>> ol = shop.publicLink(code);
        if (ol.isEmpty()) return missing(model, res, "This payment link does not exist");
        Map<String, Object> l = ol.get();
        long merchant = ((Number) l.get("merchant_id")).longValue();
        Map<String, Object> st = shop.publicStoreOf(merchant).orElse(Map.of());
        if (!"ACTIVE".equals(Shop.effectiveStatus(l)) || st.isEmpty() || !Boolean.TRUE.equals(storeModel(st).get("open"))) return linkPage(l, model, null);
        long value;
        if (l.get("amount_minor") != null) {
            value = ((Number) l.get("amount_minor")).longValue();
        } else {
            try {
                value = new java.math.BigDecimal(amount == null ? "" : amount.trim()).movePointRight(2).longValueExact();
            } catch (ArithmeticException | NumberFormatException e) {
                return linkPage(l, model, "Enter the amount, for example 12.50.");
            }
            if (value <= 0) return linkPage(l, model, "Enter an amount above zero.");
            Long min = l.get("min_minor") == null ? null : ((Number) l.get("min_minor")).longValue();
            Long max = l.get("max_minor") == null ? null : ((Number) l.get("max_minor")).longValue();
            if (min != null && value < min) return linkPage(l, model, "The smallest amount is " + price(min, (String) l.get("currency")) + ".");
            if (max != null && value > max) return linkPage(l, model, "The largest amount is " + price(max, (String) l.get("currency")) + ".");
        }
        Customer c;
        try {
            c = customer(name, phone, note);
        } catch (IllegalArgumentException e) {
            return linkPage(l, model, e.getMessage());
        }
        Payments.Placed placed;
        try {
            placed = payments.place(new Payments.NewOrder(merchant, ((Number) st.get("id")).longValue(), ((Number) l.get("id")).longValue(),
                    l.get("product_id") == null ? null : ((Number) l.get("product_id")).longValue(), (String) l.get("title"), 1, value, (String) l.get("currency"),
                    c.name(), c.phone(), c.note(), k));
        } catch (IllegalArgumentException e) {
            return linkPage(l, model, e.getMessage());
        }
        return startPayment(placed, model, res);
    }

    // ─── the customer's order ────────────────────────────────────────────────

    @GetMapping("/o/{ref}")
    public String order(@PathVariable String ref, @RequestParam(name = "t", required = false) String token, Model model, HttpServletResponse res) {
        payments.refresh(ref);
        return orderPage(ref, token, null, model, res);
    }

    /** A buyer's review, from their own order page. */
    @PostMapping("/o/{ref}/review")
    public String review(@PathVariable String ref, @RequestParam(name = "t") String token, @RequestParam(required = false) String product,
                         @RequestParam(required = false) String rating, @RequestParam(required = false) String comment, @RequestParam(required = false) String name,
                         Model model, HttpServletResponse res) {
        Optional<Map<String, Object>> o = payments.orderForCustomer(ref, token);
        if (o.isEmpty()) return missing(model, res, "This order does not exist");
        try {
            long pid = Long.parseLong(String.valueOf(product));
            growth.review(o.get(), pid, rating, comment, name);
        } catch (NumberFormatException e) {
            return orderPage(ref, token, "Choose the item to review", model, res);
        } catch (IllegalArgumentException e) {
            return orderPage(ref, token, e.getMessage(), model, res);
        }
        model.addAttribute("thanks", "Thank you for your review.");
        return orderPage(ref, token, null, model, res);
    }

    @PostMapping("/o/{ref}/pay")
    public String retry(@PathVariable String ref, @RequestParam(name = "t") String token, Model model, HttpServletResponse res) {
        if (payments.orderForCustomer(ref, token).isEmpty()) return missing(model, res, "This order does not exist");
        return startPayment(new Payments.Placed(ref, token, true), model, res);
    }

    String orderPage(String ref, String token, String problem, Model model, HttpServletResponse res) {
        Optional<Map<String, Object>> oo = payments.orderForCustomer(ref, token);
        if (oo.isEmpty()) return missing(model, res, "This order does not exist");
        Map<String, Object> o = oo.get();
        long merchant = ((Number) o.get("merchant_id")).longValue();
        Map<String, Object> st = shop.publicStoreOf(merchant).orElse(Map.of());
        String ps = String.valueOf(o.get("payment_status"));
        Map<String, Object> om = new LinkedHashMap<>();
        om.put("ref", ref);
        om.put("token", token);
        om.put("item", o.get("item_name"));
        om.put("quantity", o.get("quantity"));
        om.put("total", price(((Number) o.get("amount_minor")).longValue(), (String) o.get("currency")));
        om.put("state", switch (ps) {
            case "ON_DELIVERY" -> "cash";
            case "PAID" -> "paid";
            case "PENDING", "CONFIRMING" -> "confirming";
            case "FAILED" -> "failed";
            case "EXPIRED" -> "expired";
            default -> "unpaid";
        });
        om.put("refund", switch (String.valueOf(o.get("refund_status"))) {
            case "REQUESTED" -> "A refund has been requested. You will receive it once it has been paid out.";
            case "PARTIAL" -> "Part of this payment has been refunded.";
            case "REFUNDED" -> "This payment has been refunded.";
            default -> null;
        });
        om.put("lines", payments.items(((Number) o.get("id")).longValue(), (String) o.get("currency")));
        om.put("deliveryFee", ((Number) o.get("delivery_fee_minor")).longValue() > 0 ? price(((Number) o.get("delivery_fee_minor")).longValue(), (String) o.get("currency")) : null);
        om.put("delivery", "PAID".equals(ps) || "ON_DELIVERY".equals(ps) ? switch (String.valueOf(o.get("delivery_status"))) {
            case "PREPARING" -> "The seller is preparing your order.";
            case "READY" -> "Your order is ready to collect.";
            case "OUT" -> "Your order is on its way.";
            case "DELIVERED" -> "Your order has been delivered.";
            case "COLLECTED" -> "Your order has been collected.";
            default -> null;
        } : null);
        om.put("address", o.get("delivery_address"));
        om.put("zone", o.get("delivery_zone"));
        om.put("booking", growth.bookingOf(((Number) o.get("id")).longValue()).orElse(null));
        om.put("payChoice", o.get("pay_choice") == null ? null : Options.LABELS.get(String.valueOf(o.get("pay_choice"))));
        om.put("discount", ((Number) o.get("discount_minor")).longValue() > 0 ? o.get("discount_code") + " −" + price(((Number) o.get("discount_minor")).longValue(), (String) o.get("currency")) : null);
        om.put("canPay", !"PAID".equals(ps) && !"PENDING".equals(ps) && !"CONFIRMING".equals(ps) && !"ON_DELIVERY".equals(ps) && !"CANCELLED".equals(o.get("order_status")));
        model.addAttribute("store", st.isEmpty() ? defaultStore() : storeModel(st));
        model.addAttribute("order", om);
        model.addAttribute("reviewable", growth.reviewable(o));
        if (!model.containsAttribute("thanks")) model.addAttribute("thanks", null);
        model.addAttribute("problem", problem);
        model.addAttribute("refresh", "confirming".equals(om.get("state")));
        model.addAttribute("title", "Order " + ref);
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("Referrer-Policy", "no-referrer");
        return "order";
    }

    // ─── pictures and QR codes ───────────────────────────────────────────────

    @GetMapping("/img/{id}")
    public ResponseEntity<byte[]> image(@PathVariable String id) {
        return shop.image(id).map(i -> ResponseEntity.ok().contentType(MediaType.parseMediaType((String) i.get("content_type")))
                        .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic()).eTag((String) i.get("sha256")).body((byte[]) i.get("bytes")))
                .orElse(ResponseEntity.notFound().build());
    }

    /** QR codes for this site's own pages only (never an address someone else chooses). */
    @GetMapping(value = "/qr.svg", produces = "image/svg+xml")
    public ResponseEntity<String> qr(@RequestParam String path) {
        if (path == null || !path.matches("/(l/[" + Shop.ALPHABET + "]{8}|s/[a-z0-9-]{2,60}(/[a-z0-9-]{1,80})?)")) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic()).body(Qr.svg(settings.publicUrl + path));
    }

    static Timestamp ts(LocalDateTime t) {
        return Timestamp.valueOf(t);
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
