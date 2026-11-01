package zw.co.payport.gateway.commerce;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.Web;
import zw.co.payport.gateway.risk.ComplianceAudit;

/**
 * Payport Store stores for staff (owner, 1 Nov 2026): see every store with its reports and automatic flags, and
 * disable a store by hand (it disappears from the stores page, search and every channel) or switch it back on.
 * Every change is in the audit log.
 */
@RestController
public class StoreAdminController {

    private final StoreClient store;
    private final ComplianceAudit audit;

    public StoreAdminController(StoreClient store, ComplianceAudit audit) {
        this.store = store;
        this.audit = audit;
    }

    private static String staff() {
        return "staff:" + SecurityContextHolder.getContext().getAuthentication().getName();
    }

    @GetMapping("/api/v1/admin/store-admin/stores")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Object>> stores(@RequestParam(required = false) String q, HttpServletRequest req) {
        return Web.run(() -> StoreController.plain(store.adminStores(q)), req);
    }

    public record Disable(String reason) {}

    @PostMapping("/api/v1/admin/store-admin/stores/{id}/disable")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Object>> disable(@PathVariable long id, @RequestBody(required = false) Disable b, HttpServletRequest req) {
        return Web.run(() -> {
            String by = staff();
            Object out = StoreController.plain(store.disable(id, b == null ? null : b.reason(), by));
            audit.record(by, "STORE_DISABLED", "store:" + id, b == null || b.reason() == null ? "" : b.reason());
            return out;
        }, req);
    }

    @PostMapping("/api/v1/admin/store-admin/stores/{id}/enable")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Object>> enable(@PathVariable long id, HttpServletRequest req) {
        return Web.run(() -> {
            Object out = StoreController.plain(store.enable(id));
            audit.record(staff(), "STORE_ENABLED", "store:" + id, "");
            return out;
        }, req);
    }
}
