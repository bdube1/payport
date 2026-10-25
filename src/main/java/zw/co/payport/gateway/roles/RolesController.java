package zw.co.payport.gateway.roles;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.Web;
import zw.co.payport.gateway.repository.UserRepository;
import zw.co.payport.gateway.risk.ComplianceAudit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Roles & permissions: the full permission list, roles (built-in and new) and what each may do. */
@RestController
@RequestMapping("/api/v1/admin/roles")
public class RolesController {

    private final StaffRoles roles;
    private final UserRepository users;
    private final ComplianceAudit audit;

    public RolesController(StaffRoles roles, UserRepository users, ComplianceAudit audit) {
        this.roles = roles;
        this.users = users;
        this.audit = audit;
    }

    public record RoleBody(String code, String name, String description, List<String> permissions) {}

    /** The signed-in staff member's role, permissions and the pages they may open (drives the console menu). */
    @GetMapping("/me")
    @PreAuthorize("hasRole('STAFF')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(HttpServletRequest req) {
        return Web.run(() -> {
            var u = users.findByPhone(Web.principal()).orElseThrow(() -> new SecurityException("not staff"));
            StaffRoles.Role r = roles.of(u);
            if (r == null) throw new SecurityException("not staff");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", r.code());
            m.put("roleName", r.name());
            m.put("permissions", r.permissions());
            m.put("routes", StaffRoles.routes(r));
            return m;
        }, req);
    }

    @GetMapping("/catalog")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> catalog(HttpServletRequest req) {
        return Web.run(PermissionCatalog::describe, req);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest req) {
        return Web.run(roles::list, req);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody RoleBody b, HttpServletRequest req) {
        return Web.run(() -> {
            Map<String, Object> out = roles.save(b.code(), b.name(), b.description(), b.permissions(), Web.staff(), true);
            audit.record(Web.staff(), "ROLE_CREATED", "staff_role:" + (String) out.get("code"), String.valueOf(out.get("permissions")));
            return out;
        }, req);
    }

    @PutMapping("/{code}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(@PathVariable String code, @RequestBody RoleBody b, HttpServletRequest req) {
        return Web.run(() -> {
            Map<String, Object> out = roles.save(code, b.name(), b.description(), b.permissions(), Web.staff(), false);
            audit.record(Web.staff(), "ROLE_UPDATED", "staff_role:" + code, String.valueOf(out.get("permissions")));
            return out;
        }, req);
    }

    @PostMapping("/{code}/reset")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reset(@PathVariable String code, HttpServletRequest req) {
        return Web.run(() -> {
            Map<String, Object> out = roles.reset(code, Web.staff());
            audit.record(Web.staff(), "ROLE_RESET", "staff_role:" + code, "defaults");
            return out;
        }, req);
    }

    @DeleteMapping("/{code}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> delete(@PathVariable String code, HttpServletRequest req) {
        return Web.run(() -> {
            roles.delete(code);
            audit.record(Web.staff(), "ROLE_DELETED", "staff_role:" + code, "");
            return Map.of("deleted", code);
        }, req);
    }
}
