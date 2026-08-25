package zw.co.payport.gateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import zw.co.payport.gateway.entity.User;
import zw.co.payport.gateway.repository.UserRepository;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        // Skip logging for health check and public endpoints
        String path = request.getRequestURI();
        boolean isPublicEndpoint = path.startsWith("/actuator/health") ||
                path.startsWith("/h2-console") ||
                path.equals("/") ||
                path.startsWith("/error") ||
                path.startsWith("/api/v1/auth");

        final String authHeader = request.getHeader("Authorization");
        final String jwt;
        final String userPhone;

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        jwt = authHeader.substring(7);

        if (!isPublicEndpoint && log.isDebugEnabled()) {
            log.debug("JWT token received for path: {}", path);
        }

        try {
            userPhone = jwtService.extractUsername(jwt);

            if (userPhone != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = this.userDetailsService.loadUserByUsername(userPhone);

                if (jwtService.isTokenValid(jwt, userDetails) && isDeviceAllowed(jwt, userPhone) && isAccountCurrentlyValid(jwt, userPhone)) {
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,
                            userDetails.getAuthorities()
                    );
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);

                    if (log.isDebugEnabled()) {
                        log.debug("User authenticated: {}", userPhone);
                    }

                    // Actually forces the change, rather than just
                    // exposing a flag and hoping every client (including
                    // the mobile app, which this backend doesn't control)
                    // checks it correctly. Authentication itself still
                    // succeeds -- this blocks everything past it except
                    // the one endpoint that lets them complete the change.
                    if (mustChangeCredentialsAndNotOnAllowlist(userPhone, path)) {
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType("application/json");
                        response.getWriter().write(
                                "{\"success\":false,\"error\":{\"code\":\"MUST_CHANGE_CREDENTIALS\"," +
                                "\"message\":\"Your PIN was reset -- please set a new one before continuing.\"}}");
                        return;
                    }
                } else if (log.isDebugEnabled()) {
                    log.debug("Invalid JWT token for user: {}", userPhone);
                }
            }
        } catch (Exception e) {
            // Only log at DEBUG level for expired tokens (normal occurrence)
            if (e.getMessage() != null && e.getMessage().contains("expired")) {
                log.debug("JWT token expired for path: {}", path);
            } else if (log.isDebugEnabled()) {
                log.debug("JWT authentication error: {}", e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Single-device login enforcement for customers.
     *
     * Deliberately permissive by default — only rejects when there's real
     * evidence of a conflict: the token has a deviceId claim, the user has
     * a currently-active device on record, and the two don't match (meaning
     * a newer login happened elsewhere since this token was issued). Any
     * other case — old tokens with no deviceId claim, users who've never
     * logged in from a device-aware client, OPS/ADMIN/FINANCE accounts —
     * passes through unaffected. This is what makes the rollout safe: it
     * can't retroactively lock anyone out.
     */
    // Package-private (not private) specifically so this can be unit-tested
    // directly without needing a full Spring context or live provider/VPN
    // access — see JwtAuthenticationFilterDeviceCheckTest.
    /**
     * Closes the gap where JWTs are stateless: without this, deactivating
     * a customer or resetting their PIN had no effect on a token already
     * issued -- it stayed valid until its own expiry regardless. Checked
     * fresh from the database on every request (not cached in the token),
     * so a block or reset takes effect on the very next API call.
     */
    /** The one path a customer with mustChangePassword=true is still allowed to hit, so they can actually complete the change. Everything else is blocked at this filter. */
    boolean mustChangeCredentialsAndNotOnAllowlist(String userPhone, String path) {
        if (path.equals("/api/v1/account/change-pin") || path.equals("/api/v1/account/change-password")) {
            return false;
        }
        try {
            User user = userRepository.findByPhone(userPhone).orElse(null);
            return user != null && Boolean.TRUE.equals(user.getMustChangePassword());
        } catch (Exception e) {
            log.warn("[AUTH] mustChangePassword check could not be completed, allowing request: {}", e.getMessage());
            return false;
        }
    }

    boolean isAccountCurrentlyValid(String jwt, String userPhone) {
        try {
            User user = userRepository.findByPhone(userPhone).orElse(null);
            if (user == null) return false;

            if (!Boolean.TRUE.equals(user.getIsActive())) {
                log.info("[AUTH] Rejected request from a deactivated account: {}",
                        userPhone.substring(0, Math.min(5, userPhone.length())) + "****");
                return false;
            }

            if (user.getCredentialsChangedAt() != null) {
                java.util.Date issuedAt = jwtService.extractIssuedAt(jwt);
                java.time.LocalDateTime issuedAtLdt = issuedAt.toInstant()
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
                if (issuedAtLdt.isBefore(user.getCredentialsChangedAt())) {
                    log.info("[AUTH] Rejected a token issued before the most recent PIN/password reset for: {}",
                            userPhone.substring(0, Math.min(5, userPhone.length())) + "****");
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            // Same fail-open posture as isDeviceAllowed -- a problem in
            // this check shouldn't take down authentication entirely.
            log.warn("[AUTH] Account validity check could not be completed, allowing request: {}", e.getMessage());
            return true;
        }
    }

    boolean isDeviceAllowed(String jwt, String userPhone) {
        try {
            String tokenDeviceId = jwtService.extractDeviceId(jwt);
            if (tokenDeviceId == null || tokenDeviceId.isBlank()) return true;

            User user = userRepository.findByPhone(userPhone).orElse(null);
            if (user == null || user.getActiveDeviceId() == null) return true;

            boolean matches = user.getActiveDeviceId().equals(tokenDeviceId);
            if (!matches) {
                log.info("[AUTH] Rejected request from a device that is no longer the active session for {}",
                        userPhone.substring(0, Math.min(5, userPhone.length())) + "****");
            }
            return matches;
        } catch (Exception e) {
            // Never let a problem in this check break authentication entirely —
            // fail open, same as any other unexpected token-parsing issue.
            log.warn("[AUTH] Device check could not be completed, allowing request: {}", e.getMessage());
            return true;
        }
    }
}