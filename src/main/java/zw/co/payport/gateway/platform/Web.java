package zw.co.payport.gateway.platform;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import zw.co.payport.gateway.channels.ChannelUnavailableException;
import zw.co.payport.gateway.ledger.LedgerException;
import zw.co.payport.gateway.model.response.ApiResponse;

import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Response and identity helpers shared by the Phase 8 controllers (one error mapping for all of them). */
public final class Web {

    private Web() {}

    public static String rid(HttpServletRequest req) {
        String id = req == null ? null : req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }

    public static <T> ResponseEntity<ApiResponse<T>> ok(T body, HttpServletRequest req) {
        return ResponseEntity.ok(ApiResponse.success(body, rid(req)));
    }

    /**
     * 400 invalid input · 402 not enough money · 409 state conflict or four-eyes refusal · 503 switched off.
     * Messages are written for the person reading them; nothing internal is exposed.
     */
    public static <T> ResponseEntity<ApiResponse<T>> run(Supplier<T> s, HttpServletRequest req) {
        try {
            return ok(s.get(), req);
        } catch (ChannelUnavailableException e) {
            return ResponseEntity.status(503).header("Retry-After", "300").body(ApiResponse.error("CHANNEL_UNAVAILABLE", e.getMessage(), rid(req)));
        } catch (InsufficientFundsException e) {
            return ResponseEntity.status(402).body(ApiResponse.error("INSUFFICIENT_FUNDS", e.getMessage(), rid(req)));
        } catch (PinRejectedException e) {
            return ResponseEntity.status(400).body(ApiResponse.error("PIN_REJECTED", e.getMessage(), rid(req)));
        } catch (PlatformException e) {
            return ResponseEntity.status(409).body(ApiResponse.error(e.code(), e.getMessage(), rid(req)));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(ApiResponse.error("FORBIDDEN", e.getMessage(), rid(req)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_REQUEST", e.getMessage(), rid(req)));
        } catch (LedgerException e) {
            return ResponseEntity.status(409).body(ApiResponse.error("LEDGER_REFUSED", e.getMessage(), rid(req)));
        } catch (IllegalStateException | org.springframework.dao.EmptyResultDataAccessException e) {
            return ResponseEntity.status(409).body(ApiResponse.error("CONFLICT", e.getMessage() == null ? "Not found" : e.getMessage(), rid(req)));
        }
    }

    /** A call that may throw checked exceptions (orchestrator, providers). */
    public interface Call<T> {
        T get() throws Exception;
    }

    public static <T> ResponseEntity<ApiResponse<T>> call(Call<T> c, HttpServletRequest req) {
        return run(() -> {
            try {
                return c.get();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }, req);
    }

    /** The authenticated user's login name (a phone for customers and staff). */
    public static String principal() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || a.getName() == null || "anonymousUser".equals(a.getPrincipal())) throw new SecurityException("not authenticated");
        return a.getName();
    }

    public static String staff() {
        return "staff:" + principal();
    }

    public static Set<String> roles() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null) return Set.of();
        return a.getAuthorities().stream().map(GrantedAuthority::getAuthority).map(s -> s.startsWith("ROLE_") ? s.substring(5) : s).collect(Collectors.toSet());
    }

    /** Wrong PIN, or the account is locked after too many wrong PINs. */
    public static class PinRejectedException extends RuntimeException {
        public PinRejectedException(String message) { super(message); }
    }

    /** A wallet, ledger or float balance would go below zero. */
    public static class InsufficientFundsException extends RuntimeException {
        public InsufficientFundsException(String message) { super(message); }
    }
}
