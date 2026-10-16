package zw.co.payport.gateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Phase 9: the Prometheus scrape endpoint is reachable only with the scrape token
 * (Authorization: Bearer PAYPORT_METRICS_TOKEN). With no token configured the endpoint does not
 * exist (404), so metrics are never exposed by accident.
 */
@Component
public class MetricsTokenFilter extends OncePerRequestFilter {

    private final byte[] token;

    public MetricsTokenFilter(@Value("${payport.metrics.token:}") String token) {
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/actuator/prometheus");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
            throws ServletException, IOException {
        if (token.length == 0) {
            response.sendError(404);
            return;
        }
        String h = request.getHeader("Authorization");
        byte[] given = h != null && h.startsWith("Bearer ") ? h.substring(7).trim().getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (!MessageDigest.isEqual(token, given)) {
            response.setStatus(401);
            response.setHeader("WWW-Authenticate", "Bearer");
            return;
        }
        chain.doFilter(request, response);
    }
}
