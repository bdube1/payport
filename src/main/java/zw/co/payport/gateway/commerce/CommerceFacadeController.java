package zw.co.payport.gateway.commerce;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.co.payport.gateway.config.MerchantAuthResolver;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.Web;

import java.util.List;
import java.util.Map;

/**
 * PayPort Commerce's door into the PSP (CommerceFacade). Server to server: every call carries the service key in
 * X-PayPort-Commerce-Key. The merchant identity route is the exception: the merchant portal's own login token,
 * which Commerce forwards once when the merchant opens the store.
 */
@RestController
public class CommerceFacadeController {

    static final String KEY = "X-PayPort-Commerce-Key";
    private final CommerceFacade facade;

    public CommerceFacadeController(CommerceFacade facade) {
        this.facade = facade;
    }

    @PostMapping("/api/v1/commerce/checkouts")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkout(@RequestHeader(value = KEY, required = false) String key,
                                                                    @RequestBody CommerceFacade.NewCheckout b, HttpServletRequest req) {
        return Web.run(() -> { facade.requireKey(key); return facade.checkout(b); }, req);
    }

    @GetMapping("/api/v1/commerce/checkouts/{merchantId}/{reference}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(@RequestHeader(value = KEY, required = false) String key, @PathVariable Long merchantId,
                                                                  @PathVariable String reference, HttpServletRequest req) {
        return Web.run(() -> { facade.requireKey(key); return facade.status(merchantId, reference); }, req);
    }

    @PostMapping("/api/v1/commerce/refunds")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refund(@RequestHeader(value = KEY, required = false) String key,
                                                                  @RequestBody CommerceFacade.NewRefund b, HttpServletRequest req) {
        return Web.run(() -> { facade.requireKey(key); return facade.refund(b); }, req);
    }

    @GetMapping("/api/v1/commerce/events")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> events(@RequestHeader(value = KEY, required = false) String key,
                                                                        @RequestParam(defaultValue = "0") long after,
                                                                        @RequestParam(defaultValue = "200") int limit, HttpServletRequest req) {
        return Web.run(() -> { facade.requireKey(key); return facade.events(after, limit); }, req);
    }

    @PostMapping("/api/v1/commerce/messages")
    public ResponseEntity<ApiResponse<Map<String, Object>>> message(@RequestHeader(value = KEY, required = false) String key,
                                                                   @RequestBody CommerceFacade.NewMessage b, HttpServletRequest req) {
        return Web.run(() -> { facade.requireKey(key); return facade.message(b); }, req);
    }

    @GetMapping("/api/v1/commerce/merchants/{merchantId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> merchant(@RequestHeader(value = KEY, required = false) String key, @PathVariable Long merchantId,
                                                                    HttpServletRequest req) {
        return Web.run(() -> { facade.requireKey(key); return facade.merchantInfo(merchantId); }, req);
    }

    /** Signed-in merchant users only (an approved merchant): who they are, for PayPort Commerce's own session. */
    @GetMapping("/api/v1/merchant/commerce/identity")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> identity(HttpServletRequest req) {
        return Web.run(() -> facade.identity(MerchantAuthResolver.currentMerchant(), MerchantAuthResolver.currentMerchantUserOrNull()), req);
    }
}
