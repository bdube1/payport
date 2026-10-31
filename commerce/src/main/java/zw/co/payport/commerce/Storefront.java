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
        m.put("name", "PayPort");
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
        m.put("few", stock != null && stock > 0 && stock <= 5 ? "Only " + stock + " left" : null);
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
        model.addAttribute("title", "PayPort Store");
        return "home";
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
        for (Map<String, Object> p : shopProducts(merchant)) {
            if (p.get("category") != null) categories.add((String) p.get("category"));
            if ((category == null || category.isBlank() || category.equals(p.get("category"))) && matches(p, q)) items.add(productModel(p, (String) st.get("slug")));
        }
        model.addAttribute("q", q == null || q.isBlank() ? null : q.trim());
        model.addAttribute("categories", categories);
        model.addAttribute("category", category == null || category.isBlank() ? null : category);
        Map<String, Object> sm = storeModel(st);
        sm.put("cartCount", carts.count(((Number) st.get("id")).longValue(), cart));
        model.addAttribute("store", sm);
        model.addAttribute("products", items);
        model.addAttribute("share", share("/s/" + st.get("slug"), st.get("name") + " on PayPort"));
        model.addAttribute("title", st.get("name"));
        model.addAttribute("description", st.get("about") == null ? "Shop " + st.get("name") + " and pay securely with PayPort." : st.get("about"));
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
                          Model model, HttpServletResponse res) {
        if ("cart".equals(product)) return cartPage(slug, cart, null, model, res);
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
        long fee = deliveryFee(st, items);
        cm.put("fee", ccy == null ? null : fee == 0 ? "Free" : price(fee, ccy));
        cm.put("totalPickup", ccy == null ? null : price(items, ccy));
        cm.put("totalDelivery", ccy == null ? null : price(items + fee, ccy));
        model.addAttribute("store", sm);
        model.addAttribute("cart", cm);
        model.addAttribute("key", Shop.token() + Shop.token());
        model.addAttribute("problem", problem);
        model.addAttribute("title", "Cart | " + st.get("name"));
        return "cart";
    }

    /** Pay for the cart: one order with every item, the delivery choice and the fee; then the PSP's checkout. */
    @PostMapping("/s/{slug}/checkout")
    public String checkout(@PathVariable String slug, @RequestParam(required = false) String name, @RequestParam(required = false) String phone,
                           @RequestParam(required = false) String method, @RequestParam(required = false) String address,
                           @RequestParam(required = false) String note, @RequestParam String k,
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
        String how = "DELIVERY".equals(method) ? "DELIVERY" : "PICKUP".equals(method) ? "PICKUP" : null;
        if (how == null || ("DELIVERY".equals(how) && !Shop.bool(st.get("delivery_enabled"))) || ("PICKUP".equals(how) && !Shop.bool(st.get("pickup_enabled"))))
            return cartPage(slug, cart, "Choose pickup or delivery.", model, res);
        Customer c;
        try {
            c = customer(name, phone, note);
        } catch (IllegalArgumentException e) {
            return cartPage(slug, cart, e.getMessage(), model, res);
        }
        if (c.name() == null || c.phone() == null) return cartPage(slug, cart, "Enter your name and phone number so the seller can reach you.", model, res);
        String addr = Shop.text(address, 300, false, "address");
        if ("DELIVERY".equals(how) && (addr == null || addr.length() < 6)) return cartPage(slug, cart, "Enter the delivery address.", model, res);
        long items = v.get().itemsMinor();
        DeliveryProvider.Quote quote = delivery.of(st.get("delivery_provider")).quote(st, items, addr);
        if ("DELIVERY".equals(how) && quote.problem() != null) return cartPage(slug, cart, quote.problem(), model, res);
        long fee = "DELIVERY".equals(how) ? quote.feeMinor() : 0;
        List<Payments.Item> lines = v.get().lines().stream().map(l -> new Payments.Item(l.productId(), l.name(), l.unitMinor(), l.quantity())).toList();
        String summary = lines.size() == 1 ? lines.get(0).name() : lines.size() + " items from " + st.get("name");
        Payments.Placed placed;
        try {
            placed = payments.place(new Payments.NewOrder(((Number) st.get("merchant_id")).longValue(), storeId, null, lines.size() == 1 ? lines.get(0).productId() : null,
                    summary, v.get().count(), items + fee, v.get().currency(), c.name(), c.phone(), c.note(), k, lines, how, fee, "DELIVERY".equals(how) ? addr : null));
        } catch (IllegalArgumentException e) {
            return cartPage(slug, cart, e.getMessage(), model, res);
        }
        carts.empty(v.get().cartId());
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
        model.addAttribute("description", l.get("description") == null ? "Pay securely with PayPort." : l.get("description"));
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
        om.put("delivery", "PAID".equals(ps) ? switch (String.valueOf(o.get("delivery_status"))) {
            case "PREPARING" -> "The seller is preparing your order.";
            case "READY" -> "Your order is ready to collect.";
            case "OUT" -> "Your order is on its way.";
            case "DELIVERED" -> "Your order has been delivered.";
            case "COLLECTED" -> "Your order has been collected.";
            default -> null;
        } : null);
        om.put("address", o.get("delivery_address"));
        om.put("canPay", !"PAID".equals(ps) && !"PENDING".equals(ps) && !"CONFIRMING".equals(ps) && !"CANCELLED".equals(o.get("order_status")));
        model.addAttribute("store", st.isEmpty() ? defaultStore() : storeModel(st));
        model.addAttribute("order", om);
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
