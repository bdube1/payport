package zw.co.payport.gateway.products;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.PlatformException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Phase 8: product lines and their licences, FX rates and quotes. */
@RestController
public class ProductsController {

    private final ServiceLineService lines;
    private final FxService fx;

    public ProductsController(ServiceLineService lines, FxService fx) {
        this.lines = lines;
        this.fx = fx;
    }

    @GetMapping("/api/v1/admin/service-lines")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest req) {
        return ok(lines.list(), req);
    }

    public record DecisionBody(boolean approve, String note) {}

    public record SwitchBody(boolean enabled, String reason) {}

    @PostMapping("/api/v1/admin/service-lines/{code}")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> set(@PathVariable String code, @RequestBody SwitchBody b, HttpServletRequest req) {
        if (b.enabled() && !SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
            return ResponseEntity.status(403).body(ApiResponse.error("FORBIDDEN", "Only an administrator can switch a product line on", rid(req)));
        }
        return run(() -> lines.setEnabled(code, b.enabled(), b.reason(), staff()), req);
    }

    @GetMapping("/api/v1/admin/fx/rates")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> rates(HttpServletRequest req) {
        return ok(fx.rates(), req);
    }

    public record RateBody(String base, String quote, BigDecimal rate, Integer spreadBps, String source) {}

    @PostMapping("/api/v1/admin/fx/rates")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> propose(@RequestBody RateBody b, HttpServletRequest req) {
        return run(() -> fx.propose(b.base(), b.quote(), b.rate(), b.spreadBps() == null ? 0 : b.spreadBps(), b.source(), staff()), req);
    }

    @PostMapping("/api/v1/admin/fx/rates/{id}/decide")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> approveRate(@PathVariable long id, @RequestBody DecisionBody b, HttpServletRequest req) {
        return run(() -> fx.approve(id, b.approve(), staff()), req);
    }

    @GetMapping("/api/v1/fx/quote")
    public ResponseEntity<ApiResponse<Map<String, Object>>> quote(@RequestParam String from, @RequestParam String to, @RequestParam long amountMinor,
                                                                  HttpServletRequest req) {
        return run(() -> fx.quote(from, to, amountMinor), req);
    }

    private static String staff() {
        return "staff:" + SecurityContextHolder.getContext().getAuthentication().getName();
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
        } catch (PlatformException e) {
            return ResponseEntity.status(409).body(ApiResponse.error(e.code(), e.getMessage(), rid(req)));
        } catch (IllegalArgumentException | org.springframework.dao.EmptyResultDataAccessException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("REFUSED", e.getMessage(), rid(req)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(ApiResponse.error("CONFLICT", e.getMessage(), rid(req)));
        }
    }
}
