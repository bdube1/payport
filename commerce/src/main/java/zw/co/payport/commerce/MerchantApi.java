package zw.co.payport.commerce;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The merchant portal's Store section: store settings, products, payment links, orders, refunds and the overview.
 * Answers use the PSP's shape ({success, data} or {success:false, error:{code, message}}) so the portal reads both the
 * same way. Viewing needs any merchant role; changes need an owner, developer or manager.
 */
@RestController
public class MerchantApi {

    private final Shop shop;
    private final Payments payments;
    private final Psp psp;
    private final Tokens tokens;
    private final EventReader reader;
    private final Domains domains;
    private final Insights insights;
    private final Catalog catalog;
    @org.springframework.beans.factory.annotation.Autowired Options options;
    @org.springframework.beans.factory.annotation.Autowired Growth growth;
    @org.springframework.beans.factory.annotation.Autowired Ai ai;
    @org.springframework.beans.factory.annotation.Autowired Marketing marketing;

    public MerchantApi(Shop shop, Payments payments, Psp psp, Tokens tokens, EventReader reader, Domains domains, Insights insights, Catalog catalog) {
        this.catalog = catalog;
        this.domains = domains;
        this.insights = insights;
        this.shop = shop;
        this.payments = payments;
        this.psp = psp;
        this.tokens = tokens;
        this.reader = reader;
    }

    static Map<String, Object> ok(Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("data", data);
        return m;
    }

    static ResponseEntity<Map<String, Object>> error(int status, String code, String message) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("code", code);
        e.put("message", message == null ? "Something went wrong" : message);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("error", e);
        return ResponseEntity.status(status).body(m);
    }

    static Tokens.Merchant me(HttpServletRequest req) {
        return (Tokens.Merchant) req.getAttribute(WebConfig.MERCHANT);
    }

    static Tokens.Merchant manager(HttpServletRequest req) {
        Tokens.Merchant m = me(req);
        if (!m.manages()) throw new SecurityException("Your role can view the store but not change it");
        return m;
    }

    @ExceptionHandler(Shop.NotFound.class)
    ResponseEntity<Map<String, Object>> notFound(Shop.NotFound e) { return error(404, "NOT_FOUND", e.getMessage()); }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e) { return error(400, "INVALID_REQUEST", e.getMessage()); }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) { return error(409, "CONFLICT", e.getMessage()); }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Map<String, Object>> forbidden(SecurityException e) { return error(403, "FORBIDDEN", e.getMessage()); }

    // ─── sign-in ─────────────────────────────────────────────────────────────

    /**
     * The merchant portal sends its own login token once; the PSP says who it is, and Commerce gives its own session
     * (12 hours). Signing in needs the PSP; staying signed in does not.
     */
    @PostMapping("/api/commerce/session")
    public ResponseEntity<Map<String, Object>> session(@RequestBody Map<String, Object> b) {
        Object t = b == null ? null : b.get("portalToken");
        if (t == null || String.valueOf(t).isBlank()) return error(400, "INVALID_REQUEST", "Sign in to the merchant portal first");
        JsonNode who;
        try {
            who = psp.identity(String.valueOf(t));
        } catch (Psp.Unavailable e) {
            return error(503, "PSP_UNAVAILABLE", "Signing in to the store needs Payport Payments, which cannot be reached just now. Please try again in a few minutes.");
        } catch (Psp.Refused e) {
            return error(401, "SIGN_IN", "Please sign in to the merchant portal again");
        }
        long merchantId = who.path("merchantId").asLong(0);
        if (merchantId == 0) return error(401, "SIGN_IN", "Please sign in to the merchant portal again");
        List<String> ccy = new ArrayList<>();
        who.path("currencies").forEach(c -> ccy.add(c.asText()));
        shop.rememberMerchant(merchantId, who.path("businessName").asText("Merchant " + merchantId), ccy, who.path("status").asText("ACTIVE"));
        options.remember(merchantId, who.path("methods"));
        Tokens.Merchant m = new Tokens.Merchant(merchantId, who.path("userId").asLong(0), who.path("role").asText("OWNER"), who.path("userName").asText(""));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("token", tokens.issue(m));
        d.put("expiresInHours", Tokens.HOURS);
        d.put("canManage", m.manages());
        d.put("store", shop.store(merchantId));
        d.put("currencies", shop.currencies(merchantId));
        return ResponseEntity.ok(ok(d));
    }

    @GetMapping("/api/commerce/overview")
    public Map<String, Object> overview(HttpServletRequest req) {
        Map<String, Object> v = payments.overview(me(req).merchantId());
        v.put("store", shop.store(me(req).merchantId()));
        v.put("paymentsOnline", reader.pspUp());
        v.put("currencies", shop.currencies(me(req).merchantId()));
        v.put("methods", options.choices(me(req).merchantId()));
        return ok(v);
    }

    // ─── store ───────────────────────────────────────────────────────────────

    @GetMapping("/api/commerce/store")
    public Map<String, Object> store(HttpServletRequest req) { return ok(shop.store(me(req).merchantId())); }

    @PutMapping("/api/commerce/store")
    public Map<String, Object> saveStore(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        return ok(shop.updateStore(manager(req).merchantId(), b));
    }

    @PostMapping("/api/commerce/images")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file, HttpServletRequest req) throws java.io.IOException {
        Tokens.Merchant m = manager(req);
        Images.Clean img = Images.clean(file.getBytes());
        return ok(Map.of("imageId", shop.saveImage(m.merchantId(), img), "width", img.width(), "height", img.height()));
    }

    // ─── products ────────────────────────────────────────────────────────────

    @GetMapping("/api/commerce/products")
    public Map<String, Object> products(HttpServletRequest req) {
        List<Map<String, Object>> list = shop.products(me(req).merchantId(), false);
        list.forEach(p -> p.put("collectionIds", catalog.collectionsOf(((Number) p.get("id")).longValue())));
        return ok(list);
    }

    @PostMapping("/api/commerce/products")
    public Map<String, Object> newProduct(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        long m = manager(req).merchantId();
        if (b.get("collectionIds") instanceof List<?> ids) catalog.own(m, ids);
        return ok(withCollections(m, shop.saveProduct(m, null, b), b));
    }

    @GetMapping("/api/commerce/products/{id}")
    public Map<String, Object> product(@PathVariable long id, HttpServletRequest req) {
        Map<String, Object> p = shop.product(me(req).merchantId(), id);
        p.put("collectionIds", catalog.collectionsOf(id));
        return ok(p);
    }

    @PutMapping("/api/commerce/products/{id}")
    public Map<String, Object> saveProduct(@PathVariable long id, @RequestBody Map<String, Object> b, HttpServletRequest req) {
        long m = manager(req).merchantId();
        if (b.get("collectionIds") instanceof List<?> ids) catalog.own(m, ids);
        return ok(withCollections(m, shop.saveProduct(m, id, b), b));
    }

    private Map<String, Object> withCollections(long merchantId, Map<String, Object> p, Map<String, Object> b) {
        long id = ((Number) p.get("id")).longValue();
        if (b.get("collectionIds") instanceof List<?> ids) catalog.setProductCollections(merchantId, id, ids);
        p.put("collectionIds", catalog.collectionsOf(id));
        return p;
    }

    // ─── collections and discount codes ──────────────────────────────────────

    @GetMapping("/api/commerce/collections")
    public Map<String, Object> collections(HttpServletRequest req) { return ok(catalog.collections(me(req).merchantId())); }

    @PostMapping("/api/commerce/collections")
    public Map<String, Object> addCollection(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        return ok(catalog.addCollection(manager(req).merchantId(), b));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/api/commerce/collections/{id}")
    public Map<String, Object> removeCollection(@PathVariable long id, HttpServletRequest req) {
        catalog.removeCollection(manager(req).merchantId(), id);
        return ok(Map.of("removed", true));
    }

    @PostMapping("/api/commerce/collections/{id}/position")
    public Map<String, Object> moveCollection(@PathVariable long id, @RequestBody Map<String, Object> b, HttpServletRequest req) {
        int pos;
        try {
            pos = Integer.parseInt(String.valueOf(b.get("position")));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Enter the position");
        }
        catalog.moveCollection(manager(req).merchantId(), id, pos);
        return ok(catalog.collections(me(req).merchantId()));
    }

    @GetMapping("/api/commerce/discounts")
    public Map<String, Object> discounts(HttpServletRequest req) { return ok(catalog.discounts(me(req).merchantId())); }

    @PostMapping("/api/commerce/discounts")
    public Map<String, Object> addDiscount(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        return ok(catalog.addDiscount(manager(req).merchantId(), b));
    }

    @PostMapping("/api/commerce/discounts/{id}/status")
    public Map<String, Object> discountStatus(@PathVariable long id, @RequestBody Map<String, Object> b, HttpServletRequest req) {
        catalog.setDiscountActive(manager(req).merchantId(), id, Boolean.TRUE.equals(b.get("active")));
        return ok(catalog.discounts(me(req).merchantId()));
    }

    // ─── payment options, reviews, delivery areas, hours and bookings ─────────

    /** After the merchant changed its payment methods in the portal: ask Payport Payments again. */
    @PostMapping("/api/commerce/methods/refresh")
    public Map<String, Object> refreshMethods(HttpServletRequest req) {
        long m = me(req).merchantId();
        options.refresh(m);
        return ok(options.choices(m));
    }

    @GetMapping("/api/commerce/reviews")
    public Map<String, Object> reviews(HttpServletRequest req) { return ok(growth.merchantReviews(me(req).merchantId())); }

    @PostMapping("/api/commerce/reviews/{id}")
    public Map<String, Object> review(@PathVariable long id, @RequestBody Map<String, Object> b, HttpServletRequest req) {
        return ok(growth.updateReview(manager(req).merchantId(), id, b));
    }

    @GetMapping("/api/commerce/zones")
    public Map<String, Object> zones(HttpServletRequest req) { return ok(growth.zones(me(req).merchantId())); }

    @PostMapping("/api/commerce/zones")
    public Map<String, Object> addZone(@RequestBody Map<String, Object> b, HttpServletRequest req) { return ok(growth.addZone(manager(req).merchantId(), b)); }

    @org.springframework.web.bind.annotation.DeleteMapping("/api/commerce/zones/{id}")
    public Map<String, Object> removeZone(@PathVariable long id, HttpServletRequest req) {
        growth.removeZone(manager(req).merchantId(), id);
        return ok(growth.zones(me(req).merchantId()));
    }

    @GetMapping("/api/commerce/hours")
    public Map<String, Object> hours(HttpServletRequest req) { return ok(growth.hours(me(req).merchantId())); }

    @PutMapping("/api/commerce/hours")
    public Map<String, Object> setHours(@RequestBody Map<String, Object> b, HttpServletRequest req) { return ok(growth.setHours(manager(req).merchantId(), b)); }

    @GetMapping("/api/commerce/bookings")
    public Map<String, Object> bookings(HttpServletRequest req) { return ok(growth.merchantBookings(me(req).merchantId())); }

    /** Payport AI writes a product description for the merchant to edit (nothing is saved until the product is). */
    @PostMapping("/api/commerce/ai/describe")
    public Map<String, Object> describe(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        long m = manager(req).merchantId();
        Object store = shop.store(m).get("name");
        return ok(Map.of("description", ai.describe(b.get("name") == null ? null : String.valueOf(b.get("name")),
                b.get("category") == null ? null : String.valueOf(b.get("category")), String.valueOf(b.get("kind")),
                b.get("notes") == null ? null : String.valueOf(b.get("notes")), store == null ? null : String.valueOf(store)),
                "byModel", ai.modelOn()));
    }

    // ─── growth: reports, offers by text, loyalty ────────────────────────────

    @GetMapping("/api/commerce/reports")
    public Map<String, Object> reports(@RequestParam(defaultValue = "30") int days, HttpServletRequest req) {
        return ok(marketing.report(me(req).merchantId(), days));
    }

    @GetMapping("/api/commerce/campaigns")
    public Map<String, Object> campaigns(HttpServletRequest req) {
        long m = me(req).merchantId();
        return ok(Map.of("audience", marketing.audience(m), "campaigns", marketing.campaigns(m)));
    }

    @PostMapping("/api/commerce/campaigns")
    public Map<String, Object> sendCampaign(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Tokens.Merchant m = manager(req);
        return ok(marketing.send(m.merchantId(), b.get("text") == null ? null : String.valueOf(b.get("text")), "merchant-user:" + m.userId()));
    }

    @GetMapping("/api/commerce/business-types")
    public Map<String, Object> businessTypes() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        Catalog.TYPES.forEach((k, v) -> out.add(Map.of("code", k, "name", v)));
        return ok(out);
    }

    // ─── links ───────────────────────────────────────────────────────────────

    @GetMapping("/api/commerce/links")
    public Map<String, Object> links(HttpServletRequest req) { return ok(shop.links(me(req).merchantId())); }

    @PostMapping("/api/commerce/links")
    public Map<String, Object> newLink(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        return ok(shop.createLink(manager(req).merchantId(), b));
    }

    @GetMapping("/api/commerce/links/{id}")
    public Map<String, Object> link(@PathVariable long id, HttpServletRequest req) { return ok(shop.link(me(req).merchantId(), id)); }

    @PostMapping("/api/commerce/links/{id}/status")
    public Map<String, Object> linkStatus(@PathVariable long id, @RequestBody Map<String, Object> b, HttpServletRequest req) {
        return ok(shop.setLinkStatus(manager(req).merchantId(), id, Boolean.TRUE.equals(b.get("active"))));
    }

    // ─── orders ──────────────────────────────────────────────────────────────

    @GetMapping("/api/commerce/orders")
    public Map<String, Object> orders(@RequestParam(required = false) String payment, @RequestParam(required = false) String q, HttpServletRequest req) {
        return ok(payments.merchantOrders(me(req).merchantId(), payment, q));
    }

    @GetMapping("/api/commerce/orders/{ref}")
    public Map<String, Object> order(@PathVariable String ref, HttpServletRequest req) { return ok(payments.merchantOrder(me(req).merchantId(), ref)); }

    @PostMapping("/api/commerce/orders/{ref}/status")
    public Map<String, Object> orderStatus(@PathVariable String ref, @RequestBody Map<String, Object> b, HttpServletRequest req) {
        return ok(payments.setOrderStatus(manager(req).merchantId(), ref, String.valueOf(b.get("status"))));
    }

    @PostMapping("/api/commerce/orders/{ref}/cash-received")
    public Map<String, Object> cashReceived(@PathVariable String ref, HttpServletRequest req) {
        return ok(payments.cashReceived(manager(req).merchantId(), ref));
    }

    @PostMapping("/api/commerce/orders/{ref}/delivery")
    public Map<String, Object> delivery(@PathVariable String ref, @RequestBody Map<String, Object> b, HttpServletRequest req) {
        return ok(payments.setDelivery(manager(req).merchantId(), ref, String.valueOf(b.get("status"))));
    }

    @GetMapping("/api/commerce/domains")
    public Map<String, Object> domains(HttpServletRequest req) { return ok(domains.list(me(req).merchantId())); }

    @PostMapping("/api/commerce/domains")
    public Map<String, Object> addDomain(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        return ok(domains.add(manager(req).merchantId(), String.valueOf(b.get("domain"))));
    }

    @PostMapping("/api/commerce/domains/{id}/verify")
    public Map<String, Object> verifyDomain(@PathVariable long id, HttpServletRequest req) { return ok(domains.verify(manager(req).merchantId(), id)); }

    @org.springframework.web.bind.annotation.DeleteMapping("/api/commerce/domains/{id}")
    public Map<String, Object> removeDomain(@PathVariable long id, HttpServletRequest req) {
        domains.remove(manager(req).merchantId(), id);
        return ok(Map.of("removed", true));
    }

    @GetMapping("/api/commerce/insights")
    public Map<String, Object> insights(HttpServletRequest req) { return ok(insights.merchant(me(req).merchantId())); }

    @GetMapping("/api/commerce/customers")
    public Map<String, Object> customers(@RequestParam(required = false) String q, HttpServletRequest req) {
        return ok(shop.customers(me(req).merchantId(), q));
    }

    @PostMapping("/api/commerce/orders/{ref}/refunds")
    public Map<String, Object> refund(@PathVariable String ref, @RequestBody Map<String, Object> b, HttpServletRequest req) {
        return ok(payments.refund(manager(req), ref, b));
    }
}
