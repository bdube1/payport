package zw.co.payport.gateway.psp.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who is doing something. Always taken from the authenticated security
 * context, never from a request body: the legacy ops endpoints accepted an
 * "operator" field from the client, which made the audit trail spoofable.
 */
public record Actor(String id, Set<String> roles) {

    public static final String SYSTEM_PREFIX = "SYSTEM:";

    public static Actor system(String job) {
        return new Actor(SYSTEM_PREFIX + job, Set.of("SYSTEM"));
    }

    /** The authenticated caller, or an error if there is none. */
    public static Actor current() {
        Authentication auth = SecurityContextHolder.getContext() != null
                ? SecurityContextHolder.getContext().getAuthentication() : null;
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || "anonymousUser".equals(auth.getName())) {
            throw PspException.forbidden("UNAUTHENTICATED", "An authenticated user is required");
        }
        Set<String> roles = auth.getAuthorities() == null ? Collections.emptySet()
                : auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .map(r -> r.startsWith("ROLE_") ? r.substring(5) : r)
                    .collect(Collectors.toUnmodifiableSet());
        return new Actor(auth.getName(), roles);
    }

    public boolean hasAnyRole(String... wanted) {
        return Arrays.stream(wanted).anyMatch(roles::contains);
    }

    public boolean isSystem() {
        return id.startsWith(SYSTEM_PREFIX);
    }

    public String rolesCsv() {
        return String.join(",", roles.stream().sorted().toList());
    }
}
