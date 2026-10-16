package zw.co.payport.gateway.iso20022;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.Web;

import java.util.List;
import java.util.Map;

/** Rules that recognise payment-rail settlements on PayPort's bank statements (four-eyes). */
@RestController
@RequestMapping("/api/v1/admin/provider-settlement-rules")
public class ProviderSettlementController {

    private final ProviderSettlementService rules;

    public ProviderSettlementController(ProviderSettlementService rules) {
        this.rules = rules;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest req) {
        return Web.run(rules::rules, req);
    }

    public record RuleBody(String providerCode, String currency, String counterpartyPattern, String remittancePattern) {}

    @PostMapping
    @PreAuthorize("hasRole('FINANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> propose(@RequestBody RuleBody b, HttpServletRequest req) {
        return Web.run(() -> rules.propose(b.providerCode(), b.currency(), b.counterpartyPattern(), b.remittancePattern(), Web.staff()), req);
    }

    public record DecisionBody(boolean approve) {}

    @PostMapping("/{id}/decide")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> decide(@PathVariable long id, @RequestBody DecisionBody b, HttpServletRequest req) {
        return Web.run(() -> rules.decide(id, b.approve(), Web.staff()), req);
    }

    @PostMapping("/{id}/retire")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> retire(@PathVariable long id, HttpServletRequest req) {
        return Web.run(() -> rules.retire(id, Web.staff()), req);
    }
}
