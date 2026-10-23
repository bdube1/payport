package zw.co.payport.gateway.agents;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Authenticates agent portal users on /api/v1/agent/** only, from their own "agent_user" token.
 * Until the temporary password is changed, only the profile and change-password routes are open.
 */
@Slf4j
@Component
public class AgentJwtAuthenticationFilter extends OncePerRequestFilter {

    public record AgentPrincipal(long agentId, long userId, String email) {
        @Override
        public String toString() {
            return "agent:" + agentId + ":user:" + userId;
        }
    }

    private static final Set<String> BEFORE_CHANGE = Set.of("/api/v1/agent/me", "/api/v1/agent/change-password");

    private final JdbcTemplate jdbc;
    private final String jwtSecret;

    public AgentJwtAuthenticationFilter(JdbcTemplate jdbc, @Value("${payport.security.jwt-secret}") String jwtSecret) {
        this.jdbc = jdbc;
        this.jwtSecret = jwtSecret;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String auth = request.getHeader("Authorization");
        if (!path.startsWith("/api/v1/agent/") || auth == null || !auth.startsWith("Bearer ")
                || SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }
        try {
            Claims c = Jwts.parserBuilder().setSigningKey(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8))).build()
                    .parseClaimsJws(auth.substring(7).trim()).getBody();
            if (!"agent_user".equals(c.get("typ", String.class))) {
                chain.doFilter(request, response);
                return;
            }
            long userId = c.get("agentUserId", Long.class);
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT u.agent_id, u.email, u.status, u.account_locked, u.must_change_password, a.status AS agent_status "
                    + "FROM agent_users u JOIN agents a ON a.id = u.agent_id WHERE u.id = ?", userId);
            if (rows.isEmpty()) {
                chain.doFilter(request, response);
                return;
            }
            Map<String, Object> u = rows.get(0);
            if (!"ACTIVE".equals(u.get("status")) || AgentAuthService.truthy(u.get("account_locked")) || "REJECTED".equals(u.get("agent_status"))) {
                chain.doFilter(request, response);
                return;
            }
            if (AgentAuthService.truthy(u.get("must_change_password")) && !BEFORE_CHANGE.contains(path)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write("{\"success\":false,\"error\":{\"code\":\"MUST_CHANGE_PASSWORD\",\"message\":\"Please choose a new password first.\"}}");
                return;
            }
            AgentPrincipal p = new AgentPrincipal(((Number) u.get("agent_id")).longValue(), userId, (String) u.get("email"));
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p, null, List.of(new SimpleGrantedAuthority("ROLE_AGENT"))));
        } catch (Exception e) {
            log.debug("[AGENT_JWT_AUTH] token rejected for {}: {}", path, e.getMessage());
        }
        chain.doFilter(request, response);
    }
}
