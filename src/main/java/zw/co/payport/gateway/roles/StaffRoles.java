package zw.co.payport.gateway.roles;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.entity.User;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Configurable staff roles. Built-in roles (Administrator, Operations, Finance, Compliance) start with exactly what
 * they could reach before and behave as before until someone edits them; new roles hold only what is ticked for them.
 */
@Service
public class StaffRoles {

    public static final String SYSTEM = "system";
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");

    /** A role as the security layer sees it. {@code enforced} = its permissions decide access, not its built-in name. */
    public record Role(String code, String name, String description, String baseRole, boolean builtIn, boolean edited, Set<String> permissions) {
        public boolean enforced() { return !builtIn || edited; }
    }

    private final JdbcTemplate jdbc;
    private final Map<String, Role> cache = new ConcurrentHashMap<>();

    public StaffRoles(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedBuiltIns() {
        try {
            for (String r : PermissionCatalog.BUILT_IN) {
                Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM staff_roles WHERE code = ?", Integer.class, r);
                if (n != null && n > 0) {
                    // An untouched built-in role picks up permissions for features added since it was seeded.
                    if (Integer.valueOf(1).equals(jdbc.queryForObject("SELECT COUNT(*) FROM staff_roles WHERE code = ? AND updated_by = ?", Integer.class, r, SYSTEM))) {
                        for (String p : PermissionCatalog.defaults(r)) {
                            jdbc.update("INSERT IGNORE INTO staff_role_permissions (role_code, permission) VALUES (?, ?)", r, p);
                        }
                    }
                    continue;
                }
                jdbc.update("INSERT INTO staff_roles (code, name, description, base_role, built_in, updated_by, updated_at) VALUES (?,?,?,?,TRUE,?,?)",
                        r, PermissionCatalog.BUILT_IN_NAMES.get(r), null, r, SYSTEM, LocalDateTime.now());
                for (String p : PermissionCatalog.defaults(r)) {
                    jdbc.update("INSERT INTO staff_role_permissions (role_code, permission) VALUES (?, ?)", r, p);
                }
            }
        } catch (RuntimeException ignored) {
            // the table may not exist in a slice test; built-in defaults still apply
        }
        cache.clear();
    }

    /** The role a staff user acts under; null for customers. */
    public Role of(User u) {
        if (u == null || u.getRole() == null || "CUSTOMER".equals(u.getRole())) return null;
        String code = u.getCustomRole() != null && !u.getCustomRole().isBlank() ? u.getCustomRole() : u.getRole();
        Role r = role(code);
        if (r == null && PermissionCatalog.BUILT_IN.contains(code)) {
            return new Role(code, PermissionCatalog.BUILT_IN_NAMES.get(code), null, code, true, false, PermissionCatalog.defaults(code));
        }
        boolean custom = u.getCustomRole() != null && !u.getCustomRole().isBlank();
        if (r == null && !custom) return new Role(code, code, null, code, true, false, Set.of());   // any other system role: as before
        if (r == null) return new Role(code, code, null, u.getRole(), false, true, Set.of());      // configured role removed → nothing
        return r;
    }

    private volatile long cachedAt = System.currentTimeMillis();

    public Role role(String code) {
        if (code == null) return null;
        // With several servers, a change made on one reaches the others within a minute.
        if (System.currentTimeMillis() - cachedAt > 60_000) { cache.clear(); cachedAt = System.currentTimeMillis(); }
        Role hit = cache.get(code);
        if (hit != null) return hit;
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("SELECT * FROM staff_roles WHERE code = ?", code);
        } catch (RuntimeException e) {
            return null;
        }
        if (rows.isEmpty()) return null;
        Map<String, Object> m = rows.get(0);
        Set<String> perms = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT permission FROM staff_role_permissions WHERE role_code = ? ORDER BY permission", String.class, code));
        boolean builtIn = Boolean.TRUE.equals(m.get("built_in")) || Integer.valueOf(1).equals(m.get("built_in"));
        Role r = new Role(code, (String) m.get("name"), (String) m.get("description"), (String) m.get("base_role"), builtIn,
                !SYSTEM.equals(m.get("updated_by")), Set.copyOf(perms));
        cache.put(code, r);
        return r;
    }

    /** Authorities for a staff user: an untouched built-in role keeps its role; otherwise the permissions decide. */
    public Collection<GrantedAuthority> authorities(User u) {
        Set<String> out = new LinkedHashSet<>();
        Role r = of(u);
        if (r == null) {
            out.add("ROLE_" + u.getRole());
        } else if (!r.enforced()) {
            out.add("ROLE_" + r.code());
        } else {
            if (r.builtIn()) out.add("ROLE_" + r.code());
            for (PermissionCatalog.Feature f : PermissionCatalog.FEATURES) {
                if (r.permissions().contains(f.view()) || r.permissions().contains(f.manage())) {
                    for (String role : f.roles()) out.add("ROLE_" + role);
                }
            }
        }
        if (r != null) {
            out.add("ROLE_STAFF");
            for (String p : r.permissions()) out.add("PERM_" + p);
        }
        List<GrantedAuthority> list = new ArrayList<>();
        for (String s : out) list.add(new SimpleGrantedAuthority(s));
        return list;
    }

    /** The console pages a role may open (any feature it can view or manage). */
    public static List<String> routes(Role r) {
        List<String> out = new ArrayList<>();
        if (r == null) return out;
        for (PermissionCatalog.Feature f : PermissionCatalog.FEATURES) {
            if (r.permissions().contains(f.view()) || r.permissions().contains(f.manage())) out.addAll(f.routes());
        }
        out.add("/account");
        return out;
    }

    // ─── management ─────────────────────────────────────────────────────────

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : jdbc.queryForList("SELECT code FROM staff_roles ORDER BY built_in DESC, name")) {
            out.add(view(role((String) m.get("code"))));
        }
        return out;
    }

    public Map<String, Object> view(Role r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", r.code());
        m.put("name", r.name());
        m.put("description", r.description());
        m.put("builtIn", r.builtIn());
        m.put("permissions", r.permissions());
        m.put("users", jdbc.queryForObject(r.builtIn()
                        ? "SELECT COUNT(*) FROM users WHERE role = ? AND (custom_role IS NULL OR custom_role = '')"
                        : "SELECT COUNT(*) FROM users WHERE custom_role = ?", Integer.class, r.code()));
        return m;
    }

    @Transactional
    public Map<String, Object> save(String code, String name, String description, Collection<String> permissions, String actor, boolean create) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Enter a role name");
        if (name.trim().length() > 80) throw new IllegalArgumentException("Role name is too long");
        String c = code == null || code.isBlank() ? toCode(name) : code.trim().toUpperCase();
        if (!CODE.matcher(c).matches()) throw new IllegalArgumentException("Role code must be letters, numbers and underscores");
        if ("CUSTOMER".equals(c)) throw new IllegalArgumentException("That role code is reserved");
        Set<String> all = PermissionCatalog.allPermissions();
        Set<String> perms = new LinkedHashSet<>();
        for (String p : permissions == null ? List.<String>of() : permissions) {
            if (!all.contains(p)) throw new IllegalArgumentException("Unknown permission " + p);
            perms.add(p);
            if (p.endsWith(".manage")) perms.add(p.substring(0, p.length() - ".manage".length()) + ".view");
        }
        Role existing = role(c);
        if (create && existing != null) throw new IllegalArgumentException("A role with this code already exists");
        if (!create && existing == null) throw new IllegalArgumentException("Role not found");
        if (PermissionCatalog.ADMIN.equals(c)) {
            // the administrator role can never lose the ability to manage users and roles (no lock-out)
            perms.addAll(List.of("users.view", "users.manage", "roles.view", "roles.manage"));
        }
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            jdbc.update("INSERT INTO staff_roles (code, name, description, base_role, built_in, updated_by, updated_at) VALUES (?,?,?,?,FALSE,?,?)",
                    c, name.trim(), blank(description), "OPS", actor, now);
        } else {
            jdbc.update("UPDATE staff_roles SET name = ?, description = ?, updated_by = ?, updated_at = ? WHERE code = ?",
                    name.trim(), blank(description), actor, now, c);
            jdbc.update("DELETE FROM staff_role_permissions WHERE role_code = ?", c);
        }
        for (String p : perms) jdbc.update("INSERT INTO staff_role_permissions (role_code, permission) VALUES (?, ?)", c, p);
        cache.remove(c);
        return view(role(c));
    }

    @Transactional
    public void delete(String code) {
        Role r = role(code);
        if (r == null) throw new IllegalArgumentException("Role not found");
        if (r.builtIn()) throw new IllegalStateException("Built-in roles cannot be deleted");
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE custom_role = ?", Integer.class, code);
        if (n != null && n > 0) throw new IllegalStateException("Move the " + n + " user(s) with this role to another role first");
        jdbc.update("DELETE FROM staff_roles WHERE code = ?", code);
        cache.remove(code);
    }

    /** Resets a built-in role to its original permissions. */
    @Transactional
    public Map<String, Object> reset(String code, String actor) {
        Role r = role(code);
        if (r == null || !r.builtIn()) throw new IllegalArgumentException("Only built-in roles can be reset");
        jdbc.update("DELETE FROM staff_role_permissions WHERE role_code = ?", code);
        for (String p : PermissionCatalog.defaults(code)) jdbc.update("INSERT INTO staff_role_permissions (role_code, permission) VALUES (?, ?)", code, p);
        jdbc.update("UPDATE staff_roles SET updated_by = ?, updated_at = ? WHERE code = ?", SYSTEM, LocalDateTime.now(), code);
        cache.remove(code);
        return view(role(code));
    }

    /**
     * Resolves a role chosen for a user: a built-in role → (role, no custom role); a configured role → (its base, code).
     * Returns null when the code is unknown.
     */
    public String[] assignment(String code) {
        if (code == null || code.isBlank()) return null;
        String c = code.trim().toUpperCase();
        if ("CUSTOMER".equals(c) || PermissionCatalog.BUILT_IN.contains(c)) return new String[]{c, null};
        Role r = role(c);
        if (r == null) return null;
        if (r.builtIn()) return new String[]{c, null};
        return new String[]{r.baseRole() == null ? "OPS" : r.baseRole(), c};
    }

    private static String toCode(String name) {
        String s = name.trim().toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (s.isEmpty() || !Character.isLetter(s.charAt(0))) s = "R_" + s;
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
