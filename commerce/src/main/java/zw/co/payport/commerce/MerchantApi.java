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

    public MerchantApi(Shop shop, Payments payments, Psp psp, Tokens tokens, EventReader reader) {
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
            return error(503, "PSP_UNAVAILABLE", "Signing in to the store needs PayPort Payments, which cannot be reached just now. Please try again in a few minutes.");
        } catch (Psp.Refused e) {
            return error(401, "SIGN_IN", "Please sign in to the merchant portal again");
        }
        long merchantId = who.path("merchantId").asLong(0);
        if (merchantId == 0) return error(401, "SIGN_IN", "Please sign in to the merchant portal again");
        List<String> ccy = new ArrayList<>();
        who.path("currencies").forEach(c -> ccy.add(c.asText()));
        shop.rememberMerchant(merchantId, who.path("businessName").asText("Merchant " + merchantId), ccy, who.path("status").asText("ACTIVE"));
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
    public Map<String, Object> products(HttpServletRequest req) { return ok(shop.products(me(req).merchantId(), false)); }

    @PostMapping("/api/commerce/products")
    public Map<String, Object> newProduct(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        return ok(shop.saveProduct(manager(req).merchantId(), null, b));
    }

    @GetMapping("/api/commerce/products/{id}")
    public Map<String, Object> product(@PathVariable long id, HttpServletRequest req) { return ok(shop.product(me(req).merchantId(), id)); }

    @PutMapping("/api/commerce/products/{id}")
    public Map<String, Object> saveProduct(@PathVariable long id, @RequestBody Map<String, Object> b, HttpServletRequest req) {
        return ok(shop.saveProduct(manager(req).merchantId(), id, b));
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

    @PostMapping("/api/commerce/orders/{ref}/refunds")
    public Map<String, Object> refund(@PathVariable String ref, @RequestBody Map<String, Object> b, HttpServletRequest req) {
        return ok(payments.refund(manager(req), ref, b));
    }
}
