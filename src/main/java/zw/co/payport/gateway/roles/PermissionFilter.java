package zw.co.payport.gateway.roles;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.filter.OncePerRequestFilter;
import zw.co.payport.gateway.entity.User;
import zw.co.payport.gateway.repository.UserRepository;

import java.io.IOException;
import java.util.Optional;

/**
 * Enforces configurable roles on staff APIs: reading needs the feature's view (or manage) permission, any change
 * needs manage. Applies to configured roles and to built-in roles once they have been edited; untouched built-in
 * roles keep the existing role checks. Not a Spring bean on purpose, so it only runs inside the security chain.
 */
public class PermissionFilter extends OncePerRequestFilter {

    private final StaffRoles roles;
    private final UserRepository users;

    public PermissionFilter(StaffRoles roles, UserRepository users) {
        this.roles = roles;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String path = req.getRequestURI().substring(req.getContextPath().length());
        if (!PermissionCatalog.staffPath(path) || path.startsWith("/api/v1/admin/roles/me") || "OPTIONS".equals(req.getMethod())) {
            chain.doFilter(req, res);
            return;
        }
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !(a.getPrincipal() instanceof UserDetails ud)) {
            chain.doFilter(req, res);
            return;
        }
        Optional<User> u = users.findByPhone(ud.getUsername());
        StaffRoles.Role role = u.map(roles::of).orElse(null);
        if (role == null || !role.enforced()) {
            chain.doFilter(req, res);
            return;
        }
        Optional<PermissionCatalog.Feature> f = PermissionCatalog.forPath(path);
        boolean read = "GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod());
        boolean allowed = f.map(x -> role.permissions().contains(x.manage()) || (read && role.permissions().contains(x.view())))
                .orElse(false);
        if (!allowed) {
            res.setStatus(403);
            res.setContentType("application/json");
            res.getWriter().write("{\"success\":false,\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"Your role does not allow this\"}}");
            return;
        }
        chain.doFilter(req, res);
    }
}
