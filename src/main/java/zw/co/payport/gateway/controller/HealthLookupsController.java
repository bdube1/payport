package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.HealthLookups;
import zw.co.payport.gateway.platform.Web;
import zw.co.payport.gateway.scheduler.ProviderHealthScheduler;

import java.util.Map;

/** Provider Tools → Health checks: the account each biller check looks up, and running every check now. */
@RestController
public class HealthLookupsController {

    private final HealthLookups lookups;
    private final ProviderHealthScheduler health;

    public HealthLookupsController(HealthLookups lookups, ProviderHealthScheduler health) {
        this.lookups = lookups;
        this.health = health;
    }

    @GetMapping("/api/v1/admin/health-lookups")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE')")
    public ResponseEntity<ApiResponse<Map<String, String>>> get(HttpServletRequest req) {
        return Web.run(lookups::all, req);
    }

    @PutMapping("/api/v1/admin/health-lookups")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ResponseEntity<ApiResponse<Map<String, String>>> put(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        return Web.run(() -> lookups.save(body, Web.staff()), req);
    }

    @PostMapping("/api/v1/admin/health-lookups/check")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ResponseEntity<ApiResponse<Map<String, ProviderHealthScheduler.Result>>> check(HttpServletRequest req) {
        return Web.run(health::runAll, req);
    }
}
