package zw.co.payport.gateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import zw.co.payport.gateway.entity.ApiKey;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.repository.MerchantRepository;
import zw.co.payport.gateway.service.ApiKeyService;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates requests to the merchant developer API
 * (/api/v1/merchant/**) by secret API key, entirely separate from the
 * JwtAuthenticationFilter used by customers and staff. Only activates for
 * that one path prefix -- everywhere else, this filter does nothing and
 * the existing JWT filter behaves exactly as before.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeyService;
    private final MerchantRepository merchantRepository;

    /** What downstream controllers pull out of the SecurityContext to know which merchant is calling. */
    public record MerchantPrincipal(Merchant merchant, ApiKey apiKey) {}

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String path = request.getRequestURI();
        if (!path.startsWith("/api/v1/merchant/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String rawSecret = authHeader.substring(7).trim();

        try {
            Optional<ApiKey> keyOpt = apiKeyService.authenticateBySecret(rawSecret);
            if (keyOpt.isEmpty()) {
                log.warn("[API_KEY_AUTH] Invalid or revoked API key used against {}", path);
                filterChain.doFilter(request, response);
                return;
            }

            ApiKey apiKey = keyOpt.get();
            if (apiKey.getExpiresAt() != null && apiKey.getExpiresAt().isBefore(LocalDateTime.now())) {
                log.warn("[API_KEY_AUTH] Expired API key {} used against {}", apiKey.getPublicKey(), path);
                deny(response, 401, "API_KEY_EXPIRED", "This API key has expired. Use its replacement.");
                return;
            }
            String required = zw.co.payport.gateway.merchant.ApiKeyScopes.required(request.getMethod(), path);
            if (!zw.co.payport.gateway.merchant.ApiKeyScopes.allows(zw.co.payport.gateway.merchant.ApiKeyScopes.parse(apiKey.getScopes()), required)) {
                log.warn("[API_KEY_AUTH] Key {} lacks scope {} for {} {}", apiKey.getPublicKey(), required, request.getMethod(), path);
                deny(response, 403, "INSUFFICIENT_SCOPE", zw.co.payport.gateway.merchant.ApiKeyScopes.DASHBOARD_ONLY.equals(required)
                        ? "This endpoint is only available from the dashboard, not with an API key."
                        : "This API key does not have the '" + required + "' scope.");
                return;
            }
            Merchant merchant = merchantRepository.findById(apiKey.getMerchantId()).orElse(null);
            if (merchant == null) {
                log.warn("[API_KEY_AUTH] API key {} references a merchant that no longer exists", apiKey.getPublicKey());
                filterChain.doFilter(request, response);
                return;
            }
            if (!"ACTIVE".equals(merchant.getStatus())) {
                log.warn("[API_KEY_AUTH] API key used for non-active merchant {} (status: {})", merchant.getId(), merchant.getStatus());
                filterChain.doFilter(request, response);
                return;
            }

            apiKey.setLastUsedAt(LocalDateTime.now());
            // Deliberately not saved synchronously on every request -- see
            // note in CheckoutSessionController if this needs to become
            // exact; last-used tracking doesn't need per-request precision.

            MerchantPrincipal principal = new MerchantPrincipal(merchant, apiKey);
            String envRole = "LIVE".equals(apiKey.getEnvironment()) ? "ROLE_MERCHANT_LIVE" : "ROLE_MERCHANT_TEST";

            UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(
                            new SimpleGrantedAuthority(envRole),
                            new SimpleGrantedAuthority("ROLE_MERCHANT"),
                            // A secret key represents the merchant's own server -- never more
                            // restricted than what an OWNER can see on the dashboard.
                            new SimpleGrantedAuthority("ROLE_MERCHANT_OWNER")
                    )
            );
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);

            log.debug("[API_KEY_AUTH] Authenticated merchant {} ({}) for {}", merchant.getId(), apiKey.getEnvironment(), path);
        } catch (Exception e) {
            log.warn("[API_KEY_AUTH] Error validating API key: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private static void deny(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}");
    }
}
