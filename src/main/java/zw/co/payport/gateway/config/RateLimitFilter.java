package zw.co.payport.gateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rate Limiting Filter — register/login, and the assistant chat endpoint.
 *
 * There was no rate limiting anywhere in the application before this.
 * Login already locks an account after 5 wrong PINs, but that's
 * per-account — nothing stopped one IP from hammering many different
 * phone numbers, or spamming /register.
 *
 * This is a plain in-memory, per-IP, fixed-window limiter — no new
 * dependency. Deliberately simple: it resets every window rather than
 * doing a proper sliding window, and state is lost on restart or lost in
 * a multi-instance deployment (each instance counts independently). Both
 * are real limitations, not oversights — a proper distributed limiter
 * (e.g. backed by Redis) is the right long-term answer if this runs on
 * more than one instance, but that's a bigger, riskier change than
 * belongs in this pass. This is a meaningful improvement over "none at
 * all", not a claim of being airtight.
 *
 * The assistant chat endpoint is included here too, but for a different
 * reason than login/register: it isn't primarily about brute-force
 * protection, it's about not letting one visitor's rapid-fire messages
 * single-handedly burn through Gemini's free-tier daily quota, which is
 * shared across every visitor to the site (see AssistantService for the
 * separate global daily-quota guard, which protects the aggregate
 * across ALL visitors — this filter only protects against any one IP).
 */
@Slf4j
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int DEFAULT_MAX_REQUESTS_PER_WINDOW = 10;
    private static final int ASSISTANT_MAX_REQUESTS_PER_WINDOW = 20; // a real back-and-forth chat sends more messages/min than a login attempt ever should
    private static final long WINDOW_MILLIS = 60_000; // 1 minute

    private record Bucket(AtomicInteger count, long windowStart) {}

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String path = request.getRequestURI();
        boolean isAuthPath = path.equals("/api/v1/auth/login")
                || path.equals("/api/v1/auth/register")
                || path.equals("/api/v1/auth/ops/login");
        boolean isAssistantPath = path.equals("/api/v1/assistant/chat");
        // Phase 5: public bill-account lookups return a customer's name, so they are limited per IP
        // against enumeration; merchant portal login/signup likewise.
        boolean isLookupPath = path.equals("/api/v1/bills/customer-info") || path.matches("/api/v1/billers/[^/]+/lookup")
                || path.startsWith("/api/v1/merchants/by-code/") || path.startsWith("/api/v1/wallet/agents/") || path.startsWith("/api/v1/wallet/merchants/") || path.startsWith("/api/v1/codes/scan")
                // one-time codes and tap tokens: guessing is limited per address too (each payment also allows only 3 tries)
                || path.matches("/api/v1/checkout/[^/]+/omari-otp") || path.matches("/api/v1/transactions/[^/]+/confirm-omari-otp")
                || path.equals("/api/v1/wallet/tap") || path.equals("/api/v1/merchant/tap-charges");
        if (path.equals("/api/v1/merchant-portal/login") || path.equals("/api/v1/merchant-portal/signup")
                || path.equals("/api/v1/merchant-portal/forgot-password") || path.equals("/api/v1/merchant-portal/reset-password")
                || path.startsWith("/api/v1/agent-portal/")) isAuthPath = true;

        if (!isAuthPath && !isAssistantPath && !isLookupPath) {
            filterChain.doFilter(request, response);
            return;
        }

        int maxRequests = isAssistantPath || isLookupPath ? ASSISTANT_MAX_REQUESTS_PER_WINDOW : DEFAULT_MAX_REQUESTS_PER_WINDOW;
        // One bucket per kind of call, not per exact address: otherwise /merchants/by-code/000001, /000002, ... would
        // each get their own allowance and codes could be enumerated at full speed.
        String key = clientIp(request) + ":" + bucketOf(path);
        long now = Instant.now().toEpochMilli();

        Bucket bucket = buckets.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStart() > WINDOW_MILLIS) {
                return new Bucket(new AtomicInteger(1), now);
            }
            existing.count().incrementAndGet();
            return existing;
        });

        if (bucket.count().get() > maxRequests) {
            log.warn("[RATE_LIMIT] Blocked {} for {} ({} requests in window)",
                    clientIp(request), path, bucket.count().get());
            zw.co.payport.gateway.observability.SecurityEvents.count(zw.co.payport.gateway.observability.SecurityEvents.RATE_LIMITED, isAuthPath ? "login" : isLookupPath ? "lookup" : "assistant");
            response.setStatus(429); // Too Many Requests
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"success\":false,\"error\":{\"code\":\"RATE_LIMITED\",\"message\":\"Too many attempts. Please wait a moment and try again.\"}}"
            );
            return;
        }

        // Opportunistic cleanup so this map doesn't grow forever — cheap check,
        // only runs occasionally.
        if (buckets.size() > 10_000) {
            buckets.entrySet().removeIf(e -> now - e.getValue().windowStart() > WINDOW_MILLIS * 5);
        }

        filterChain.doFilter(request, response);
    }

    /** The kind of call: the path with its variable parts (codes, ids, references) taken out. */
    static String bucketOf(String path) {
        if (path.startsWith("/api/v1/merchants/by-code/")) return "/api/v1/merchants/by-code/*";
        if (path.startsWith("/api/v1/wallet/agents/")) return "/api/v1/wallet/agents/*";
        if (path.startsWith("/api/v1/wallet/merchants/")) return "/api/v1/wallet/merchants/*";
        if (path.startsWith("/api/v1/codes/scan")) return "/api/v1/codes/scan";
        if (path.matches("/api/v1/billers/[^/]+/lookup")) return "/api/v1/billers/*/lookup";
        if (path.endsWith("/omari-otp") || path.endsWith("/confirm-omari-otp")) return "omari-otp";
        if (path.startsWith("/api/v1/agent-portal/")) return "/api/v1/agent-portal/*";
        return path;
    }

    /**
     * The caller's address. X-Forwarded-For is believed only when the request comes from our own proxy (loopback or
     * a private address: Caddy or the load balancer), and then only its last entry, which that proxy wrote; the
     * earlier entries are whatever the caller sent and could be anything. A caller on the internet cannot pick its
     * own address to escape the limits.
     */
    static String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank() || !internal(remote)) return remote;
        String[] hops = forwarded.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String h = hops[i].trim();
            if (!h.isEmpty() && !internal(h)) return h;
        }
        return remote;
    }

    static boolean internal(String ip) {
        if (ip == null) return false;
        return ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1") || ip.startsWith("10.") || ip.startsWith("192.168.")
                || ip.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*") || ip.startsWith("fd") || ip.startsWith("fc");
    }
}
