package zw.co.payport.gateway.merchant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.config.ApiKeyAuthenticationFilter;
import zw.co.payport.gateway.config.MerchantAuthResolver;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.entity.MerchantUser;
import zw.co.payport.gateway.model.response.ApiResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Merchant-facing platform API (dashboard session or secret key, scopes enforced by the key filter):
 * balances, statement, payouts, live readiness, and settlement destinations (dashboard only).
 */
@RestController
@RequestMapping("/api/v1/merchant")
@PreAuthorize("hasRole('MERCHANT')")
public class MerchantPlatformController {

    private final MerchantBalanceService balances;
    private final MerchantPlatformService platform;

    public MerchantPlatformController(MerchantBalanceService balances, MerchantPlatformService platform) {
        this.balances = balances;
        this.platform = platform;
    }

    /** A secret key sees its own environment; a dashboard user chooses (?mode=test|live, default live). */
    private static boolean livemode(String mode) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ApiKeyAuthenticationFilter.MerchantPrincipal p) {
            return "LIVE".equals(p.apiKey().getEnvironment());
        }
        return mode == null || !"test".equalsIgnoreCase(mode);
    }

    @GetMapping("/balances")
    public ResponseEntity<ApiResponse<Map<String, Object>>> balances(@RequestParam(required = false) String mode, HttpServletRequest req) {
        return ok(balances.balances(MerchantAuthResolver.currentMerchant().getId(), livemode(mode)), req);
    }

    @GetMapping("/statement")
    public ResponseEntity<ApiResponse<Map<String, Object>>> statement(@RequestParam(defaultValue = "USD") String currency,
                                                                      @RequestParam(required = false) LocalDate from,
                                                                      @RequestParam(required = false) LocalDate to,
                                                                      @RequestParam(required = false) String mode, HttpServletRequest req) {
        LocalDate t = to == null ? LocalDate.now() : to;
        LocalDate f = from == null ? t.minusDays(30) : from;
        if (f.isAfter(t) || f.isBefore(t.minusDays(366))) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_RANGE", "from must be on or before to, and at most 366 days earlier", rid(req)));
        }
        return ok(balances.statement(MerchantAuthResolver.currentMerchant().getId(), currency, f, t, livemode(mode)), req);
    }

    @GetMapping("/payouts")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> payouts(HttpServletRequest req) {
        return ok(balances.payouts(MerchantAuthResolver.currentMerchant().getId()), req);
    }

    @GetMapping("/readiness")
    public ResponseEntity<ApiResponse<Map<String, Object>>> readiness(HttpServletRequest req) {
        return ok(platform.readiness(MerchantAuthResolver.currentMerchant().getId()), req);
    }

    @GetMapping("/settlement-destinations")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> destinations(HttpServletRequest req) {
        return ok(platform.destinations(MerchantAuthResolver.currentMerchant().getId()), req);
    }

    public record DestinationBody(String currency, String type, String accountName, String accountNumber, String institution, String branch) {}

    /** The owner adds where to be paid; Payport staff verify it before any payout uses it. */
    @PostMapping("/settlement-destinations")
    @PreAuthorize("hasRole('MERCHANT_OWNER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addDestination(@RequestBody DestinationBody b, HttpServletRequest req) {
        Merchant m = MerchantAuthResolver.currentMerchant();
        MerchantUser u = MerchantAuthResolver.currentMerchantUserOrNull();
        String by = u != null ? "merchant-user:" + u.getId() : "merchant:" + m.getId();
        try {
            Map<String, Object> d = platform.addDestination(m.getId(), b.currency(), b.type(), b.accountName(), b.accountNumber(), b.institution(), b.branch(), by);
            d.remove("account_number");
            return ResponseEntity.status(201).body(ApiResponse.success(d, rid(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_DESTINATION", e.getMessage(), rid(req)));
        }
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T body, HttpServletRequest req) {
        return ResponseEntity.ok(ApiResponse.success(body, rid(req)));
    }

    private static String rid(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
