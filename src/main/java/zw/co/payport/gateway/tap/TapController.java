package zw.co.payport.gateway.tap;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.co.payport.gateway.config.MerchantAuthResolver;
import zw.co.payport.gateway.entity.User;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.Web;
import zw.co.payport.gateway.wallets.WalletPaymentService;

import java.util.Map;

/** Payport Tap: the customer's one-time NFC token (after their PIN) and the merchant's charge of a tapped token. */
@RestController
public class TapController {

    private final TapToPay tap;
    private final WalletPaymentService payments;
    private final zw.co.payport.gateway.repository.UserRepository users;

    public TapController(TapToPay tap, WalletPaymentService payments, zw.co.payport.gateway.repository.UserRepository users) {
        this.tap = tap;
        this.payments = payments;
        this.users = users;
    }

    public record IssueBody(String currency, Long maxMinor, String pin) {}

    @PostMapping("/api/v1/wallet/tap")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> issue(@RequestBody IssueBody b, HttpServletRequest req) {
        return Web.run(() -> {
            User u = payments.verifyPin(Web.principal(), b.pin());
            return tap.issue(u, b.currency(), b.maxMinor());
        }, req);
    }

    @GetMapping("/api/v1/wallet/tap/status")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(@RequestParam String token, HttpServletRequest req) {
        return Web.run(() -> tap.status(users.findByPhone(Web.principal()).orElseThrow(() -> new SecurityException("Sign in again")), token), req);
    }

    public record ChargeBody(String tap, long amountMinor, String currency, String description) {}

    @PostMapping("/api/v1/merchant/tap-charges")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> charge(@RequestBody ChargeBody b, HttpServletRequest req) {
        return Web.run(() -> tap.charge(MerchantAuthResolver.currentMerchant(), b.tap(), b.amountMinor(), b.currency(), b.description(), req.getRemoteAddr()), req);
    }
}
