package zw.co.payport.gateway.rules;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.Web;

import java.util.List;
import java.util.Map;

/** Ops: limits, charges and tax for wallet customers, agents and merchants (each configured on its own screen). */
@RestController
public class PartyRulesController {

    private final PartyRules rules;

    public PartyRulesController(PartyRules rules) {
        this.rules = rules;
    }

    @GetMapping("/api/v1/admin/rules/{party}")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(@PathVariable String party, HttpServletRequest req) {
        return Web.run(() -> rules.list(party), req);
    }

    @GetMapping("/api/v1/admin/rules/{party}/options")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> options(@PathVariable String party, HttpServletRequest req) {
        return Web.run(() -> rules.options(party), req);
    }

    @PostMapping("/api/v1/admin/rules/{party}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> save(@PathVariable String party, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        return Web.run(() -> rules.save(party, body, Web.staff()), req);
    }

    @DeleteMapping("/api/v1/admin/rules/{party}/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> delete(@PathVariable String party, @PathVariable long id, HttpServletRequest req) {
        return Web.run(() -> {
            rules.delete(party, id, Web.staff());
            return Map.<String, Object>of("deleted", id);
        }, req);
    }
}
