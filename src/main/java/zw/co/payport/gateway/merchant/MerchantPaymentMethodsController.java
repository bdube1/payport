package zw.co.payport.gateway.merchant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import zw.co.payport.gateway.config.MerchantAuthResolver;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.entity.MerchantUser;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.Web;

import java.util.List;
import java.util.Map;

/** The merchant's accepted payment methods, and (for Payport staff) live readiness of store payments. */
@RestController
public class MerchantPaymentMethodsController {

    private final MerchantPaymentMethods methods;

    public MerchantPaymentMethodsController(MerchantPaymentMethods methods) {
        this.methods = methods;
    }

    @GetMapping("/api/v1/merchant/payment-methods")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> mine(HttpServletRequest req) {
        return Web.run(() -> methods.forMerchant(MerchantAuthResolver.currentMerchant().getId()), req);
    }

    /** Body: {"ECOCASH": true, "ZIMSWITCH": false, ...} (only the methods being changed). */
    @PutMapping("/api/v1/merchant/payment-methods")
    @PreAuthorize("hasRole('MERCHANT_OWNER')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> set(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Merchant m = MerchantAuthResolver.currentMerchant();
        MerchantUser u = MerchantAuthResolver.currentMerchantUserOrNull();
        String by = u != null ? "merchant-user:" + u.getId() : "merchant:" + m.getId();
        return Web.run(() -> methods.set(m.getId(), b, by), req);
    }

    @GetMapping("/api/v1/admin/payments/live-readiness")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> readiness(HttpServletRequest req) {
        return Web.run(methods::readiness, req);
    }

    public record LiveBody(boolean live, String reason) {}

    @PostMapping("/api/v1/admin/payments/store-live")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> storeLive(@RequestBody LiveBody b, HttpServletRequest req) {
        String by = "staff:" + SecurityContextHolder.getContext().getAuthentication().getName();
        return Web.run(() -> methods.setCommerceLive(b.live(), b.reason(), by), req);
    }
}
