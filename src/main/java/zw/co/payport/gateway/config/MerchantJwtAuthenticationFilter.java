package zw.co.payport.gateway.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.entity.MerchantUser;
import zw.co.payport.gateway.repository.MerchantRepository;
import zw.co.payport.gateway.repository.MerchantUserRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Authenticates merchant DASHBOARD users (a person, logged in via
 * MerchantAuthService.login(), in a browser) against /api/v1/merchant/**
 * -- entirely separate from ApiKeyAuthenticationFilter, which
 * authenticates a merchant's SERVER via secret key. Both filters run on
 * the same path prefix; each one silently no-ops if the bearer token
 * isn't the kind it's looking for; whichever one actually matches sets
 * the authentication, so a single set of @PreAuthorize("hasRole('MERCHANT')")
 * checks work correctly for both a script calling the API and a human
 * using the dashboard.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MerchantJwtAuthenticationFilter extends OncePerRequestFilter {

    private final MerchantUserRepository merchantUserRepository;
    private final MerchantRepository merchantRepository;

    @Value("${payport.security.jwt-secret}")
    private String jwtSecret;

    /** What downstream controllers pull out of the SecurityContext for a dashboard user. */
    public record MerchantUserPrincipal(Merchant merchant, MerchantUser merchantUser) {}

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

        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7).trim();
        if (token.startsWith("sk_")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
            var key = Keys.hmacShaKeyFor(keyBytes);
            Claims claims = Jwts.parserBuilder().setSigningKey(key).build()
                    .parseClaimsJws(token).getBody();

            if (!"merchant_user".equals(claims.get("typ", String.class))) {
                filterChain.doFilter(request, response);
                return;
            }

            Long merchantUserId = claims.get("merchantUserId", Long.class);
            MerchantUser user = merchantUserRepository.findById(merchantUserId).orElse(null);
            if (user == null || !"ACTIVE".equals(user.getStatus()) || Boolean.TRUE.equals(user.getAccountLocked())) {
                filterChain.doFilter(request, response);
                return;
            }

            Merchant merchant = merchantRepository.findById(user.getMerchantId()).orElse(null);
            if (merchant == null) {
                filterChain.doFilter(request, response);
                return;
            }
            // SUSPENDED/CLOSED/REJECTED get no access at all, same as
            // before. PENDING/UNDER_REVIEW/UPDATE_REQUIRED now DO
            // authenticate, but only with a narrower role -- see below --
            // so every existing endpoint gated on hasRole('MERCHANT') or
            // more specific roles keeps rejecting them automatically,
            // without needing to touch a single one of those endpoints.
            // Only the new application-status-tracking endpoint accepts
            // this narrower role.
            if (Set.of("SUSPENDED", "CLOSED", "REJECTED").contains(merchant.getStatus())) {
                filterChain.doFilter(request, response);
                return;
            }

            boolean isActive = "ACTIVE".equals(merchant.getStatus());
            List<SimpleGrantedAuthority> authorities = isActive
                    ? List.of(
                            new SimpleGrantedAuthority("ROLE_MERCHANT"),
                            new SimpleGrantedAuthority("ROLE_MERCHANT_" + user.getRole()))
                    : List.of(new SimpleGrantedAuthority("ROLE_MERCHANT_PENDING"));

            MerchantUserPrincipal principal = new MerchantUserPrincipal(merchant, user);
            UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                    principal, null, authorities
            );
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);

        } catch (Exception e) {
            log.debug("[MERCHANT_JWT_AUTH] Token rejected for {}: {}", path, e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}
