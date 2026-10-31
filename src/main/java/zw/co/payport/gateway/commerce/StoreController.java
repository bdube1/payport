package zw.co.payport.gateway.commerce;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.co.payport.gateway.entity.User;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.Web;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The PayPort Store in the mobile app (the same stores and orders as the website, WhatsApp and USSD). Browsing is open;
 * ordering is for a signed-in customer, whose own name and phone number go on the order. The order is then paid with the
 * hosted checkout it returns (mobile money, card) or from the wallet (/api/v1/wallet/pay-checkout).
 */
@RestController
public class StoreController {

    private final StoreClient store;
    private final zw.co.payport.gateway.channels.ChannelService channels;
    private final zw.co.payport.gateway.repository.UserRepository users;

    public StoreController(StoreClient store, zw.co.payport.gateway.channels.ChannelService channels, zw.co.payport.gateway.repository.UserRepository users) {
        this.store = store;
        this.channels = channels;
        this.users = users;
    }

    private User me() {
        return users.findByPhone(Web.principal()).orElseThrow(() -> new SecurityException("not authenticated"));
    }

    @GetMapping("/api/v1/store/stores")
    public ResponseEntity<ApiResponse<JsonNode>> stores(@RequestParam(required = false) String type, @RequestParam(required = false) String q, HttpServletRequest req) {
        return Web.run(() -> store.stores(type, q), req);
    }

    @GetMapping("/api/v1/store/stores/{slug}")
    public ResponseEntity<ApiResponse<JsonNode>> one(@PathVariable String slug, @RequestParam(required = false) String c, @RequestParam(required = false) String q,
                                                   HttpServletRequest req) {
        return Web.run(() -> store.store(slug, c, q), req);
    }

    @PostMapping("/api/v1/store/stores/{slug}/slots")
    public ResponseEntity<ApiResponse<JsonNode>> slots(@PathVariable String slug, @RequestBody Map<String, Object> b, HttpServletRequest req) {
        return Web.run(() -> store.slots(slug, b.get("items")), req);
    }

    @PostMapping("/api/v1/store/quote")
    public ResponseEntity<ApiResponse<JsonNode>> quote(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        return Web.run(() -> store.quote(clean(b, null)), req);
    }

    @PostMapping("/api/v1/store/orders")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<JsonNode>> order(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        return Web.run(() -> {
            channels.require("MOB");
            return store.order(clean(b, me()));
        }, req);
    }

    @GetMapping("/api/v1/store/orders")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<JsonNode>> mine(HttpServletRequest req) {
        return Web.run(() -> store.orders(me().getPhone()), req);
    }

    @GetMapping("/api/v1/store/orders/{ref}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<JsonNode>> get(@PathVariable String ref, HttpServletRequest req) {
        return Web.run(() -> store.order(ref, me().getPhone()), req);
    }

    /** Only the order's own fields; the customer's name and phone come from the signed-in account, never the request. */
    static Map<String, Object> clean(Map<String, Object> b, User u) {
        Map<String, Object> o = new LinkedHashMap<>();
        for (String k : new String[]{"store", "items", "method", "address", "zone", "slot", "note", "code", "pay", "key"}) if (b.get(k) != null) o.put(k, b.get(k));
        o.put("channel", "APP");
        if (u != null) {
            o.put("name", u.getFullName());
            o.put("phone", u.getPhone());
        }
        return o;
    }
}
