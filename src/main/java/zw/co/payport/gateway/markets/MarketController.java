package zw.co.payport.gateway.markets;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.PlatformException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Markets and currencies from the ops console. Switching off is immediate; switching on (or any
 * other change) is a request a second person approves; enabling money needs a COMPLIANCE officer.
 */
@RestController
@RequestMapping("/api/v1/admin/markets")
public class MarketController {

    private final MarketRegistry markets;
    private final MarketAdminService admin;

    public MarketController(MarketRegistry markets, MarketAdminService admin) {
        this.markets = markets;
        this.admin = admin;
    }

    /** Markets in use, as the payment paths see them. */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest req) {
        return ok(markets.summary(), req);
    }

    /** Full settings, currencies, go-live readiness and any problems. */
    @GetMapping("/settings")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> settings(HttpServletRequest req) {
        return ok(admin.view(), req);
    }

    @GetMapping("/requests")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> requests(HttpServletRequest req) {
        return ok(admin.requests(), req);
    }

    public record ChangeBody(Map<String, Object> changes, List<Map<String, Object>> currencies, String reason) {}

    @PostMapping("/{country}/change-requests")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> change(@PathVariable String country, @RequestBody ChangeBody b, HttpServletRequest req) {
        return run(() -> admin.requestChange(country, b.changes(), b.currencies(), b.reason(), staff()), req);
    }

    public record NewMarketBody(Map<String, Object> market, String reason) {}

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody NewMarketBody b, HttpServletRequest req) {
        return run(() -> admin.requestNewMarket(b.market(), b.reason(), staff()), req);
    }

    public record DecisionBody(boolean approve, String note) {}

    @PostMapping("/requests/{id}/decide")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> decide(@PathVariable long id, @RequestBody DecisionBody b, HttpServletRequest req) {
        return run(() -> admin.decide(id, b.approve(), b.note(), staff(), roles()), req);
    }

    public record ReasonBody(String reason) {}

    @PostMapping("/{country}/suspend")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> suspend(@PathVariable String country, @RequestBody ReasonBody b, HttpServletRequest req) {
        return run(() -> admin.suspend(country, b.reason(), staff()), req);
    }

    @PostMapping("/currencies/{currency}/disable")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> disable(@PathVariable String currency, @RequestBody ReasonBody b, HttpServletRequest req) {
        return run(() -> admin.switchOffCurrency(currency, false, b.reason(), staff()), req);
    }

    @PostMapping("/currencies/{currency}/live-off")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> liveOff(@PathVariable String currency, @RequestBody ReasonBody b, HttpServletRequest req) {
        return run(() -> admin.switchOffCurrency(currency, true, b.reason(), staff()), req);
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    static String staff() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || a.getName() == null) throw new IllegalStateException("not authenticated");
        return "staff:" + a.getName();
    }

    static Set<String> roles() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null) return Set.of();
        return a.getAuthorities().stream().map(GrantedAuthority::getAuthority).map(s -> s.startsWith("ROLE_") ? s.substring(5) : s).collect(Collectors.toSet());
    }

    private static String rid(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T body, HttpServletRequest req) {
        return ResponseEntity.ok(ApiResponse.success(body, rid(req)));
    }

    private static <T> ResponseEntity<ApiResponse<T>> run(Supplier<T> s, HttpServletRequest req) {
        try {
            return ok(s.get(), req);
        } catch (PlatformException e) {                  // self-approval, wrong role, expired request
            return ResponseEntity.status(409).body(ApiResponse.error(e.code(), e.getMessage(), rid(req)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("MARKET_CHANGE_REFUSED", e.getMessage(), rid(req)));
        } catch (IllegalStateException | org.springframework.dao.EmptyResultDataAccessException e) {
            return ResponseEntity.status(409).body(ApiResponse.error("MARKET_CONFLICT", e.getMessage(), rid(req)));
        }
    }
}
