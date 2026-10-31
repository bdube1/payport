package zw.co.payport.commerce;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The store for PayPort's other channels (owner, 31 Oct 2026: "all the 4 channels must be identical"): the mobile app,
 * WhatsApp and USSD reach the same stores, items, delivery choices, discount codes, bookings and pay-on-delivery as
 * the website, through PayPort Payments (server to server, the shared service key). Orders are placed here exactly
 * like web orders; paying is then done by the channel on the PSP checkout this returns.
 */
@RestController
public class ChannelApi {

    static final String KEY = "X-PayPort-Commerce-Key";
    static final List<String> CHANNELS = List.of("APP", "WHATSAPP", "USSD");

    private final Settings settings;
    private final Shop shop;
    private final Catalog catalog;
    private final Carts carts;
    private final Payments payments;
    private final Growth growth;
    private final Options options;
    private final DeliveryProvider.Registry delivery;

    public ChannelApi(Settings settings, Shop shop, Catalog catalog, Carts carts, Payments payments, Growth growth, Options options, DeliveryProvider.Registry delivery) {
        this.settings = settings;
        this.shop = shop;
        this.catalog = catalog;
        this.carts = carts;
        this.payments = payments;
        this.growth = growth;
        this.options = options;
        this.delivery = delivery;
    }

    void requireKey(String presented) {
        if (settings.pspKey.isEmpty() || presented == null
                || !MessageDigest.isEqual(settings.pspKey.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8)))
            throw new SecurityException("Wrong service key");
    }

    // ─── browse ──────────────────────────────────────────────────────────────

    @GetMapping("/api/channel/stores")
    public Map<String, Object> stores(@RequestHeader(value = KEY, required = false) String key, @RequestParam(required = false) String type,
                                      @RequestParam(required = false) String q) {
        requireKey(key);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> st : catalog.directory(type, q == null || q.isBlank() ? null : q.trim())) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("slug", st.get("slug"));
            v.put("name", st.get("name"));
            v.put("type", st.get("business_type"));
            v.put("typeName", Storefront.SHORT.getOrDefault(String.valueOf(st.get("business_type")), "Other"));
            v.put("items", st.get("products"));
            v.put("tagline", st.get("tagline"));
            v.put("payOnDelivery", Shop.bool(st.get("cod_enabled")));
            v.put("rating", growth.storeRating(((Number) st.get("id")).longValue()));
            v.put("logo", st.get("logo_image") == null ? null : settings.publicUrl + "/img/" + st.get("logo_image"));
            v.put("banner", st.get("banner_image") == null ? null : settings.publicUrl + "/img/" + st.get("banner_image"));
            v.put("url", settings.publicUrl + "/s/" + st.get("slug"));
            out.add(v);
        }
        List<Map<String, Object>> types = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> st : catalog.directory(null, null)) counts.merge(String.valueOf(st.get("business_type")), 1, Integer::sum);
        counts.forEach((k, n) -> types.add(Map.of("code", k, "name", Storefront.SHORT.getOrDefault(k, "Other"), "stores", n)));
        return MerchantApi.ok(Map.of("stores", out, "types", types));
    }

    /** One store: what it sells (optionally one collection or a search), how it delivers, how customers can pay. */
    @GetMapping("/api/channel/stores/{slug}")
    public Map<String, Object> store(@RequestHeader(value = KEY, required = false) String key, @PathVariable String slug,
                                     @RequestParam(required = false) String c, @RequestParam(required = false) String q) {
        requireKey(key);
        Map<String, Object> st = shop.publicStore(slug).orElseThrow(() -> new Shop.NotFound("No such store"));
        long storeId = ((Number) st.get("id")).longValue(), merchantId = ((Number) st.get("merchant_id")).longValue();
        List<Long> inCol = c == null || c.isBlank() ? null : catalog.productsIn(storeId, c);
        Map<Long, String> ratings = growth.productRatings(storeId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> p : shop.publicProducts(merchantId)) {
            long id = ((Number) p.get("id")).longValue();
            if (inCol != null && !inCol.contains(id)) continue;
            if (!Storefront.matches(p, q)) continue;
            Integer stock = p.get("stock") == null ? null : ((Number) p.get("stock")).intValue();
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", id);
            v.put("name", p.get("name"));
            v.put("description", p.get("description"));
            v.put("priceMinor", ((Number) p.get("price_minor")).longValue());
            v.put("wasMinor", p.get("compare_at_minor"));
            v.put("currency", p.get("currency"));
            v.put("price", Storefront.price(((Number) p.get("price_minor")).longValue(), (String) p.get("currency")));
            v.put("service", "SERVICE".equals(p.get("kind")));
            v.put("soldOut", stock != null && stock == 0);
            v.put("featured", Shop.bool(p.get("featured")));
            v.put("rating", ratings.get(id));
            v.put("image", p.get("image_id") == null ? null : settings.publicUrl + "/img/" + p.get("image_id"));
            items.add(v);
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("slug", slug);
        v.put("name", st.get("name"));
        v.put("about", st.get("about"));
        v.put("announcement", st.get("announcement"));
        v.put("open", "OPEN".equals(st.get("status")));
        v.put("pickup", Shop.bool(st.get("pickup_enabled")));
        v.put("pickupNote", st.get("pickup_note"));
        v.put("delivery", Shop.bool(st.get("delivery_enabled")));
        v.put("deliveryNote", st.get("delivery_note"));
        v.put("deliveryFeeMinor", st.get("delivery_fee_minor"));
        v.put("zones", growth.publicZones(storeId).stream().map(z -> Map.of("id", z.get("id"), "name", z.get("name"), "feeMinor", z.get("fee_minor"))).toList());
        v.put("payOnDelivery", Shop.bool(st.get("cod_enabled")));
        v.put("methods", options.accepted(merchantId));
        v.put("bookings", growth.takesBookings(storeId));
        v.put("collections", catalog.publicCollections(storeId));
        v.put("whatsapp", st.get("whatsapp"));
        v.put("rating", growth.storeRating(storeId));
        v.put("url", settings.publicUrl + "/s/" + slug);
        v.put("items", items);
        return MerchantApi.ok(v);
    }

    /** Free appointment times for these services (for a booking made in a channel). */
    @PostMapping("/api/channel/stores/{slug}/slots")
    public Map<String, Object> slots(@RequestHeader(value = KEY, required = false) String key, @PathVariable String slug, @RequestBody Map<String, Object> b) {
        requireKey(key);
        Map<String, Object> st = shop.publicStore(slug).orElseThrow(() -> new Shop.NotFound("No such store"));
        long storeId = ((Number) st.get("id")).longValue();
        Carts.View v = carts.of(storeId, quantities(b.get("items")));
        int minutes = minutes(v);
        List<Map<String, Object>> out = new ArrayList<>();
        if (minutes > 0) for (Growth.Slot s : growth.slots(storeId, minutes)) {
            out.add(Map.of("value", s.value(), "label", s.label()));
            if (out.size() >= 60) break;
        }
        return MerchantApi.ok(out);
    }

    // ─── order ───────────────────────────────────────────────────────────────

    /**
     * Places an order the same way as the website (same checks, reservations, discount, delivery, booking). Body: store,
     * items [{productId, quantity}], method PICKUP|DELIVERY, address, zone, slot, name, phone, note, code, pay (CASH or a
     * method code), channel APP|WHATSAPP|USSD, key (the channel's one-time key: a repeat returns the same order).
     */
    @PostMapping("/api/channel/orders")
    public Map<String, Object> order(@RequestHeader(value = KEY, required = false) String key, @RequestBody Map<String, Object> b) {
        requireKey(key);
        return MerchantApi.ok(plan(b, true));
    }

    /** The same checks and sums without placing anything: what the order would cost (for the confirm screen). */
    @PostMapping("/api/channel/quote")
    public Map<String, Object> quote(@RequestHeader(value = KEY, required = false) String key, @RequestBody Map<String, Object> b) {
        requireKey(key);
        return MerchantApi.ok(plan(b, false));
    }

    Map<String, Object> plan(Map<String, Object> b, boolean place) {
        String slug = text(b.get("store"));
        Map<String, Object> st = shop.publicStore(slug == null ? "" : slug).orElseThrow(() -> new Shop.NotFound("No such store"));
        long storeId = ((Number) st.get("id")).longValue(), merchantId = ((Number) st.get("merchant_id")).longValue();
        if (!"OPEN".equals(st.get("status"))) throw new IllegalStateException("This store is not taking orders at the moment");
        String channel = String.valueOf(b.getOrDefault("channel", "APP")).toUpperCase(Locale.ROOT);
        if (!CHANNELS.contains(channel)) throw new IllegalArgumentException("Unknown channel");
        String k = text(b.get("key"));
        if (place && (k == null || !k.matches("[A-Za-z0-9_-]{16,64}"))) throw new IllegalArgumentException("A one-time key is needed");
        if (place) {
            // The same key again (a retry, a repeated USSD hop): the order already placed, nothing new.
            Optional<Map<String, Object>> had = shop.jdbc().queryForList("SELECT order_ref, idempotency_key FROM orders WHERE idempotency_key = ?", k).stream().findFirst();
            if (had.isPresent()) {
                Map<String, Object> out = view(String.valueOf(had.get().get("order_ref")), payments.viewToken(k));
                out.put("checkoutSessionId", payments.lastSessionId(String.valueOf(had.get().get("order_ref"))));
                return out;
            }
        }
        Carts.View v = carts.of(storeId, quantities(b.get("items")));
        if (!v.ok()) {
            String why = v.lines().stream().filter(l -> l.problem() != null).map(l -> l.name() + ": " + l.problem()).findFirst().orElse("Choose what to order");
            throw new IllegalArgumentException(why);
        }
        boolean servicesOnly = v.lines().stream().allMatch(Carts.Line::service);
        String method = text(b.get("method"));
        String how = servicesOnly ? "NONE" : "DELIVERY".equals(method) ? "DELIVERY" : "PICKUP".equals(method) ? "PICKUP" : null;
        if (how == null || ("DELIVERY".equals(how) && !Shop.bool(st.get("delivery_enabled"))) || ("PICKUP".equals(how) && !Shop.bool(st.get("pickup_enabled"))))
            throw new IllegalArgumentException("Choose pickup or delivery");
        Storefront.Customer c = Storefront.customer(text(b.get("name")), text(b.get("phone")), text(b.get("note")));
        if (place && c.phone() == null) throw new IllegalArgumentException("Enter your phone number");
        String name = c.name() == null ? "Customer" : c.name();
        String addr = Shop.text(b.get("address"), 300, false, "address");
        if (place && "DELIVERY".equals(how) && (addr == null || addr.length() < 6)) throw new IllegalArgumentException("Enter the delivery address");
        long items = v.itemsMinor();
        Catalog.Applied discount = null;
        String code = text(b.get("code"));
        if (code != null) {
            discount = catalog.apply(storeId, code, items);
            items -= discount.discountMinor();
        }
        String pay = text(b.get("pay"));
        boolean cash = "CASH".equals(pay);
        if (cash && !Shop.bool(st.get("cod_enabled"))) throw new IllegalArgumentException("This store does not take pay on delivery");
        String payChoice = pay != null && options.accepted(merchantId).contains(pay) ? pay : null;
        long fee;
        String zoneName = null;
        if ("DELIVERY".equals(how) && !growth.publicZones(storeId).isEmpty()) {
            Map<String, Object> z = growth.zone(storeId, text(b.get("zone"))).orElseThrow(() -> new IllegalArgumentException("Choose your delivery area"));
            fee = Growth.zoneFee(z, items);
            zoneName = (String) z.get("name");
        } else {
            DeliveryProvider.Quote quote = delivery.of(st.get("delivery_provider")).quote(st, items, addr);
            if ("DELIVERY".equals(how) && quote.problem() != null) throw new IllegalArgumentException(quote.problem());
            fee = "DELIVERY".equals(how) ? quote.feeMinor() : 0;
        }
        int minutes = minutes(v);
        Payments.Booking booking = null;
        if (minutes > 0 && growth.takesBookings(storeId)) {
            Optional<java.time.LocalDateTime> at = growth.validSlot(storeId, minutes, text(b.get("slot")));
            if (place && at.isEmpty()) throw new IllegalArgumentException("Choose a free time for the appointment");
            if (at.isPresent()) booking = new Payments.Booking(at.get(), minutes);
        }
        if (!place) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("store", st.get("name"));
            q.put("currency", v.currency());
            q.put("itemsMinor", v.itemsMinor());
            q.put("discountMinor", discount == null ? 0 : discount.discountMinor());
            q.put("deliveryFeeMinor", fee);
            q.put("totalMinor", items + fee);
            q.put("total", Storefront.price(items + fee, v.currency()));
            q.put("needsBooking", minutes > 0 && growth.takesBookings(storeId));
            q.put("lines", v.lines().stream().map(l -> Map.of("name", l.name(), "quantity", l.quantity(), "line", Storefront.price(l.unitMinor() * l.quantity(), v.currency()))).toList());
            return q;
        }
        List<Payments.Item> lines = v.lines().stream().map(l -> new Payments.Item(l.productId(), l.name(), l.unitMinor(), l.quantity())).toList();
        String summary = lines.size() == 1 ? lines.get(0).name() : lines.size() + " items from " + st.get("name");
        Payments.Placed placed = payments.place(new Payments.NewOrder(merchantId, storeId, null, lines.size() == 1 ? lines.get(0).productId() : null,
                summary, v.count(), items + fee, v.currency(), name, c.phone(), c.note(), k, lines, how, fee, "DELIVERY".equals(how) ? addr : null), booking);
        if (!placed.existing()) {
            if (discount != null) catalog.use(discount.id());
            payments.channel(placed.orderRef(), channel);
            payments.finish(placed.orderRef(), cash, discount == null ? null : discount.code(), discount == null ? 0 : discount.discountMinor(), cash ? null : payChoice, zoneName);
        }
        Map<String, Object> out = view(placed.orderRef(), placed.viewToken());
        if (!cash) {
            Payments.Start s = payments.pay(placed.orderRef(), placed.viewToken());
            if (s.checkoutUrl() == null && s.problem() != null && !String.valueOf(out.get("paymentStatus")).equals("PAID")) {
                out.put("problem", s.problem());
            }
            out.put("checkoutSessionId", payments.lastSessionId(placed.orderRef()));
            out.put("checkoutUrl", s.checkoutUrl());
        }
        return out;
    }

    /** An order as the customer may see it (the phone number it was placed with must match). */
    @GetMapping("/api/channel/orders/{ref}")
    public Map<String, Object> get(@RequestHeader(value = KEY, required = false) String key, @PathVariable String ref, @RequestParam String phone) {
        requireKey(key);
        payments.refresh(ref);
        Map<String, Object> o = payments.order(ref).filter(x -> samePhone(x.get("customer_phone"), phone))
                .orElseThrow(() -> new Shop.NotFound("No such order"));
        return MerchantApi.ok(view(ref, payments.viewToken(String.valueOf(o.get("idempotency_key")))));
    }

    /** The customer's recent store orders by phone number (newest first), for "My orders" in a channel. */
    @GetMapping("/api/channel/orders")
    public Map<String, Object> mine(@RequestHeader(value = KEY, required = false) String key, @RequestParam String phone) {
        requireKey(key);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> o : payments.ordersByPhone(phone, 10)) out.add(view(String.valueOf(o.get("order_ref")), payments.viewToken(String.valueOf(o.get("idempotency_key")))));
        return MerchantApi.ok(out);
    }

    Map<String, Object> view(String ref, String token) {
        Map<String, Object> o = payments.order(ref).orElseThrow(() -> new Shop.NotFound("No such order"));
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("orderRef", ref);
        v.put("store", shop.jdbc().queryForList("SELECT name FROM stores WHERE id = ?", String.class, o.get("store_id")).stream().findFirst().orElse(null));
        v.put("summary", o.get("item_name"));
        v.put("amountMinor", ((Number) o.get("amount_minor")).longValue());
        v.put("currency", o.get("currency"));
        v.put("total", Storefront.price(((Number) o.get("amount_minor")).longValue(), (String) o.get("currency")));
        v.put("paymentStatus", o.get("payment_status"));
        v.put("orderStatus", o.get("order_status"));
        v.put("deliveryMethod", o.get("delivery_method"));
        v.put("deliveryStatus", o.get("delivery_status"));
        v.put("payMethod", o.get("pay_method"));
        v.put("booking", growth.bookingOf(((Number) o.get("id")).longValue()).orElse(null));
        v.put("orderUrl", payments.orderUrl(ref, token));
        v.put("placedAt", String.valueOf(o.get("created_at")));
        return v;
    }

    static boolean samePhone(Object a, String b) {
        String x = a == null ? "" : String.valueOf(a).replaceAll("\\D", ""), y = b == null ? "" : b.replaceAll("\\D", "");
        if (x.isEmpty() || y.isEmpty()) return false;
        String tx = x.length() > 9 ? x.substring(x.length() - 9) : x, ty = y.length() > 9 ? y.substring(y.length() - 9) : y;
        return tx.equals(ty);
    }

    int minutes(Carts.View v) {
        int total = 0;
        for (Carts.Line l : v.lines()) {
            if (!l.service()) continue;
            Integer d = shop.jdbc().queryForList("SELECT duration_min FROM products WHERE id = ?", Integer.class, l.productId()).stream()
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            total += (d == null || d <= 0 ? Growth.DEFAULT_DURATION : d) * l.quantity();
        }
        return Math.min(total, 12 * 60);
    }

    static Map<Long, Integer> quantities(Object items) {
        Map<Long, Integer> q = new LinkedHashMap<>();
        if (!(items instanceof List<?> list) || list.isEmpty()) throw new IllegalArgumentException("Choose what to order");
        if (list.size() > 30) throw new IllegalArgumentException("At most 30 different items in one order");
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) throw new IllegalArgumentException("Choose what to order");
            try {
                q.merge(Long.parseLong(String.valueOf(m.get("productId"))), Integer.parseInt(String.valueOf(m.get("quantity") == null ? "1" : m.get("quantity"))), Integer::sum);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Choose what to order");
            }
        }
        return q;
    }

    static String text(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Map<String, Object>> forbidden(SecurityException e) { return MerchantApi.error(403, "FORBIDDEN", e.getMessage()); }

    @ExceptionHandler(Shop.NotFound.class)
    ResponseEntity<Map<String, Object>> notFound(Shop.NotFound e) { return MerchantApi.error(404, "NOT_FOUND", e.getMessage()); }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e) { return MerchantApi.error(400, "INVALID_REQUEST", e.getMessage()); }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) { return MerchantApi.error(409, "CONFLICT", e.getMessage()); }
}
