package zw.co.payport.gateway.wallets;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.agents.AgentService;
import zw.co.payport.gateway.entity.User;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.Web;
import zw.co.payport.gateway.repository.UserRepository;

import java.util.List;
import java.util.Map;

/** Wallets: the holder's endpoints (signed-in customer) and the ops console's. */
@RestController
public class WalletController {

    private final WalletService wallets;
    private final WalletPaymentService payments;
    private final AgentService agents;
    private final UserRepository users;

    public WalletController(WalletService wallets, WalletPaymentService payments, AgentService agents, UserRepository users) {
        this.wallets = wallets;
        this.payments = payments;
        this.agents = agents;
        this.users = users;
    }

    private User me() {
        return users.findByPhone(Web.principal()).orElseThrow(() -> new SecurityException("not authenticated"));
    }

    private WalletService.Wallet mine(String ref) {
        WalletService.Wallet w = wallets.byRef(ref).orElseThrow(() -> new IllegalArgumentException("No such wallet"));
        if (w.userId() != me().getId()) throw new IllegalArgumentException("No such wallet");
        return w;
    }

    // ─── holder ──────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/wallet")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(HttpServletRequest req) {
        return Web.run(() -> {
            User u = me();
            return Map.<String, Object>of("enabled", isOn(), "wallets", wallets.forUser(u.getId()).stream().map(wallets::view).toList());
        }, req);
    }

    private boolean isOn() {
        try {
            wallets.requireOn();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public record OpenBody(String currency) {}

    @PostMapping("/api/v1/wallet/open")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> open(@RequestBody OpenBody b, HttpServletRequest req) {
        return Web.run(() -> {
            User u = me();
            return wallets.view(wallets.open(u.getId(), u.getPhone(), b.currency(), "customer:" + u.getPhone()));
        }, req);
    }

    @GetMapping("/api/v1/wallet/{ref}/statement")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> statement(@PathVariable String ref, @RequestParam(defaultValue = "100") int limit, HttpServletRequest req) {
        return Web.run(() -> wallets.statement(mine(ref), limit), req);
    }

    public record TransferBody(String to, String currency, long amountMinor, String pin, String clientRef) {}

    @PostMapping("/api/v1/wallet/transfers")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> transfer(@RequestBody TransferBody b, HttpServletRequest req) {
        return Web.run(() -> {
            User u = payments.verifyPin(Web.principal(), b.pin());
            return wallets.transfer(u.getId(), b.to(), b.currency(), b.amountMinor(), b.clientRef(), "customer:" + u.getPhone());
        }, req);
    }

    public record PayBody(String reference, String pin) {}

    @PostMapping("/api/v1/wallet/pay")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pay(@RequestBody PayBody b, HttpServletRequest req) {
        return Web.call(() -> payments.payTransaction(Web.principal(), b.reference(), b.pin()), req);
    }

    public record PayCheckoutBody(long sessionId, String pin) {}

    @PostMapping("/api/v1/wallet/pay-checkout")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> payCheckout(@RequestBody PayCheckoutBody b, HttpServletRequest req) {
        return Web.call(() -> payments.payCheckout(Web.principal(), b.sessionId(), b.pin(), req.getRemoteAddr()), req);
    }

    public record QuoteBody(String from, String to, long amountMinor) {}

    @PostMapping("/api/v1/wallet/fx/quotes")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> quote(@RequestBody QuoteBody b, HttpServletRequest req) {
        return Web.run(() -> wallets.quoteConversion(me().getId(), b.from(), b.to(), b.amountMinor()), req);
    }

    public record ConvertBody(String quoteRef, String pin) {}

    @PostMapping("/api/v1/wallet/fx/conversions")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> convert(@RequestBody ConvertBody b, HttpServletRequest req) {
        return Web.run(() -> {
            User u = payments.verifyPin(Web.principal(), b.pin());
            return wallets.convert(u.getId(), b.quoteRef(), "customer:" + u.getPhone());
        }, req);
    }

    public record CashOutBody(String currency, long amountMinor, String pin) {}

    @PostMapping("/api/v1/wallet/cash-out-codes")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cashOutCode(@RequestBody CashOutBody b, HttpServletRequest req) {
        return Web.run(() -> agents.issueCashOutCode(Web.principal(), b.pin(), b.currency(), b.amountMinor()), req);
    }

    @GetMapping("/api/v1/wallet/cash-out-codes")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> cashOutCodes(HttpServletRequest req) {
        return Web.run(() -> agents.cashOutCodes(me().getPhone()), req);
    }

    // ─── ops console ─────────────────────────────────────────────────────────

    @GetMapping("/api/v1/admin/wallets")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> search(@RequestParam(required = false) String q, HttpServletRequest req) {
        return Web.run(() -> wallets.search(q, 100), req);
    }

    @GetMapping("/api/v1/admin/wallets/totals")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> totals(HttpServletRequest req) {
        return Web.run(wallets::totals, req);
    }

    @GetMapping("/api/v1/admin/wallets/{ref}/statement")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> adminStatement(@PathVariable String ref, HttpServletRequest req) {
        return Web.run(() -> wallets.statement(wallets.byRef(ref).orElseThrow(() -> new IllegalArgumentException("No such wallet")), 500), req);
    }

    public record StatusBody(String status, String reason) {}

    /** Freezing is immediate for ops; unfreezing needs compliance or an administrator. */
    @PostMapping("/api/v1/admin/wallets/{ref}/status")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(@PathVariable String ref, @RequestBody StatusBody b, HttpServletRequest req) {
        return Web.run(() -> {
            if ("ACTIVE".equalsIgnoreCase(b.status()) && !Web.roles().contains("ADMIN") && !Web.roles().contains("COMPLIANCE")) {
                throw new SecurityException("Only compliance or an administrator can unfreeze a wallet");
            }
            return wallets.setStatus(ref, b.status(), b.reason(), Web.staff());
        }, req);
    }

    public record TierBody(String tier, String reason) {}

    @PostMapping("/api/v1/admin/wallets/{ref}/tier")
    @PreAuthorize("hasRole('COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> tier(@PathVariable String ref, @RequestBody TierBody b, HttpServletRequest req) {
        return Web.run(() -> wallets.setTier(ref, b.tier(), b.reason(), Web.staff()), req);
    }

    public record ReasonBody(String reason) {}

    @PostMapping("/api/v1/admin/wallets/refunds/{reference}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refund(@PathVariable String reference, @RequestBody ReasonBody b, HttpServletRequest req) {
        return Web.run(() -> payments.refundToWallet(reference, b.reason(), Web.staff()), req);
    }
}
