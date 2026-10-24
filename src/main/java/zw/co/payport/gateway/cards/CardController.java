package zw.co.payport.gateway.cards;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.entity.User;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.Web;
import zw.co.payport.gateway.repository.UserRepository;
import zw.co.payport.gateway.wallets.WalletPaymentService;
import zw.co.payport.gateway.wallets.WalletService;

import java.util.List;
import java.util.Map;

@RestController
public class CardController {

    private final CardService cards;
    private final WalletPaymentService payments;
    private final WalletService wallets;
    private final UserRepository users;

    public CardController(CardService cards, WalletPaymentService payments, WalletService wallets, UserRepository users) {
        this.cards = cards;
        this.payments = payments;
        this.wallets = wallets;
        this.users = users;
    }

    private User me() {
        return users.findByPhone(Web.principal()).orElseThrow(() -> new SecurityException("not authenticated"));
    }

    @GetMapping("/api/v1/wallet/cards")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> mine(HttpServletRequest req) {
        return Web.run(() -> {
            User u = me();
            return Map.of("cards", cards.forUser(u.getId()), "options", cards.options());
        }, req);
    }

    public record RequestBody(String walletRef, String currency, String scheme, String form, String nameOnCard, String nickname, String deliveryAddress, String pin) {}

    @PostMapping("/api/v1/wallet/cards")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> request(@org.springframework.web.bind.annotation.RequestBody RequestBody b, HttpServletRequest req) {
        return Web.run(() -> {
            wallets.requireOn();
            User u = payments.verifyPin(Web.principal(), b.pin());
            String name = b.nameOnCard() == null || b.nameOnCard().isBlank() ? u.getFullName() : b.nameOnCard();
            return cards.request(u.getId(), b.walletRef(), b.currency(), b.scheme(), b.form(), name, b.nickname(), b.deliveryAddress(), u.getPhone());
        }, req);
    }

    public record ActionBody(String action, String pin) {}

    @PostMapping("/api/v1/wallet/cards/{ref}/action")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> action(@PathVariable String ref, @org.springframework.web.bind.annotation.RequestBody ActionBody b, HttpServletRequest req) {
        return Web.run(() -> {
            User u = payments.verifyPin(Web.principal(), b.pin());
            return cards.customerAction(u.getId(), ref, b.action(), u.getPhone());
        }, req);
    }

    public record PinBody(String pin) {}

    @PostMapping("/api/v1/wallet/cards/{ref}/reveal")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reveal(@PathVariable String ref, @org.springframework.web.bind.annotation.RequestBody PinBody b, HttpServletRequest req) {
        return Web.run(() -> cards.reveal(payments.verifyPin(Web.principal(), b.pin()).getId(), ref), req);
    }

    @PostMapping("/api/v1/wallet/cards/{ref}/refresh")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refresh(@PathVariable String ref, HttpServletRequest req) {
        return Web.run(() -> cards.refreshFor(me().getId(), ref), req);
    }

    @GetMapping("/api/v1/admin/cards")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> search(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
                                                                         @RequestParam(required = false) String scheme, HttpServletRequest req) {
        return Web.run(() -> cards.search(q, status, scheme), req);
    }

    public record StatusBody(String status, String note) {}

    @PostMapping("/api/v1/admin/cards/{ref}/status")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(@PathVariable String ref, @org.springframework.web.bind.annotation.RequestBody StatusBody b, HttpServletRequest req) {
        return Web.run(() -> cards.staffStatus(ref, b.status(), b.note(), Web.staff()), req);
    }

    @PostMapping("/api/v1/admin/cards/{ref}/refresh")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adminRefresh(@PathVariable String ref, HttpServletRequest req) {
        return Web.run(() -> cards.refresh(ref), req);
    }
}
