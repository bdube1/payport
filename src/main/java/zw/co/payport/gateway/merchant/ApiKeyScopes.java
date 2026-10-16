package zw.co.payport.gateway.merchant;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * API key scopes (Phase 4). A key carries a set of scopes, or "*" (every scope; keys created before
 * Phase 4). Some endpoints are dashboard-only: no API key, whatever its scopes, may manage keys,
 * team members, payout destinations or KYB documents -- a leaked server key must not be able to
 * mint new keys or redirect payouts.
 */
public final class ApiKeyScopes {

    public static final List<String> ALL = List.of(
            "checkout:read", "checkout:write", "refunds:write", "disputes:read", "disputes:write",
            "payment_links:write", "balances:read", "payouts:read", "webhooks:manage");

    /** Marker for dashboard-only endpoints. */
    public static final String DASHBOARD_ONLY = "dashboard-only";

    private static final List<String> DASHBOARD_ONLY_PREFIXES = List.of(
            "/api/v1/merchant/api-keys", "/api/v1/merchant/team", "/api/v1/merchant/settlement-destinations",
            "/api/v1/merchant/documents", "/api/v1/merchant/application");

    private ApiKeyScopes() {}

    /** Normalises and validates a requested scope list; "*" or empty means all. */
    public static String normalise(List<String> requested) {
        if (requested == null || requested.isEmpty() || requested.contains("*")) return "*";
        Set<String> out = new LinkedHashSet<>();
        for (String s : requested) {
            String v = s == null ? "" : s.trim().toLowerCase();
            if (!ALL.contains(v)) throw new IllegalArgumentException("unknown scope: " + s + " (allowed: " + ALL + ")");
            out.add(v);
        }
        return String.join(",", out);
    }

    public static Set<String> parse(String stored) {
        if (stored == null || stored.isBlank() || "*".equals(stored.trim())) return Set.of("*");
        return Arrays.stream(stored.split(",")).map(String::trim).filter(x -> !x.isEmpty()).collect(Collectors.toSet());
    }

    /** The scope a request needs, DASHBOARD_ONLY, or "*" for endpoints without a specific scope. */
    public static String required(String method, String path) {
        String m = method == null ? "GET" : method.toUpperCase();
        boolean read = m.equals("GET") || m.equals("HEAD");
        for (String p : DASHBOARD_ONLY_PREFIXES) if (path.startsWith(p)) return DASHBOARD_ONLY;
        if (path.matches("/api/v1/merchant/checkout-sessions/[^/]+/refunds.*")) return read ? "checkout:read" : "refunds:write";
        if (path.matches("/api/v1/merchant/checkout-sessions/[^/]+/disputes.*")) return read ? "disputes:read" : "disputes:write";
        if (path.startsWith("/api/v1/merchant/checkout-sessions")) return read ? "checkout:read" : "checkout:write";
        if (path.startsWith("/api/v1/merchant/payment-links")) return read ? "checkout:read" : "payment_links:write";
        if (path.startsWith("/api/v1/merchant/balance") || path.startsWith("/api/v1/merchant/statement")
                || path.startsWith("/api/v1/merchant/collections-by-method") || path.startsWith("/api/v1/merchant/reports")) return "balances:read";
        if (path.startsWith("/api/v1/merchant/settlements") || path.startsWith("/api/v1/merchant/payouts")) return "payouts:read";
        if (path.startsWith("/api/v1/merchant/webhooks")) return "webhooks:manage";
        return "*";
    }

    /** True if a key with these scopes may make this request. */
    public static boolean allows(Set<String> scopes, String required) {
        if (DASHBOARD_ONLY.equals(required)) return false;
        if (scopes.contains("*")) return true;
        return !"*".equals(required) && scopes.contains(required);
    }
}
