package zw.co.payport.gateway.psp.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.Api;
import zw.co.payport.gateway.psp.platform.approval.ApprovalRequest;
import zw.co.payport.gateway.psp.platform.approval.ApprovalService;
import zw.co.payport.gateway.psp.routing.RoutingRule;
import zw.co.payport.gateway.psp.routing.RoutingService;

import java.util.List;
import java.util.Map;

/** Payment lifecycle operations: callback inbox, unresolved payments, evidence-based resolution, routing. */
@RestController
@RequestMapping("/api/v1/psp/admin/payments")
@PreAuthorize("hasAnyRole('OPS','ADMIN','FINANCE')")
public class PaymentsAdminController {

    public record ResolutionBody(String outcome, String evidence, String providerReference) {}

    private final CallbackInboxService inbox;
    private final UncertainPaymentResolver resolver;
    private final ApprovalService approvals;
    private final RoutingService routing;
    private final ObjectMapper om;

    public PaymentsAdminController(CallbackInboxService inbox, UncertainPaymentResolver resolver, ApprovalService approvals,
                                   RoutingService routing, ObjectMapper om) {
        this.inbox = inbox;
        this.resolver = resolver;
        this.approvals = approvals;
        this.routing = routing;
        this.om = om;
    }

    @GetMapping("/callbacks")
    public ResponseEntity<ApiResponse<List<CallbackInboxEntry>>> callbacks(@RequestParam(defaultValue = "FAILED") String status, HttpServletRequest req) {
        return Api.ok(inbox.byStatus(status.toUpperCase()), req);
    }

    @GetMapping("/{reference}/callbacks")
    public ResponseEntity<ApiResponse<List<CallbackInboxEntry>>> callbacksFor(@PathVariable String reference, HttpServletRequest req) {
        return Api.ok(inbox.forReference(reference), req);
    }

    @PostMapping("/callbacks/{id}/reprocess")
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    public ResponseEntity<ApiResponse<String>> reprocess(@PathVariable Long id, HttpServletRequest req) {
        return Api.ok(inbox.reprocess(id), req);
    }

    /** Payments whose outcome is still unknown, oldest first. */
    @GetMapping("/unresolved")
    public ResponseEntity<ApiResponse<List<PaymentCheck>>> unresolved(HttpServletRequest req) {
        return Api.ok(resolver.unresolved(), req);
    }

    @PostMapping("/{reference}/check-now")
    public ResponseEntity<ApiResponse<String>> checkNow(@PathVariable String reference, HttpServletRequest req) {
        return Api.ok(resolver.check(reference), req);
    }

    /** Ops proposes an outcome with evidence; FINANCE approves it (PAYMENT_RESOLVE). */
    @PostMapping("/{reference}/resolution-requests")
    public ResponseEntity<ApiResponse<ApprovalRequest>> resolve(@PathVariable String reference, @RequestBody ResolutionBody b,
                                                                HttpServletRequest req) throws Exception {
        String payload = om.writeValueAsString(Map.of("outcome", String.valueOf(b.outcome()).toUpperCase(),
                "evidence", String.valueOf(b.evidence()), "providerReference", String.valueOf(b.providerReference())));
        return Api.created(approvals.request(PaymentResolutionHandler.ACTION, reference, payload,
                "Resolve " + reference + " as " + b.outcome() + " (evidence: " + b.evidence() + ")", b.evidence(), Actor.current()), req);
    }

    @GetMapping("/routing/rules")
    public ResponseEntity<ApiResponse<List<RoutingRule>>> rules(HttpServletRequest req) {
        return Api.ok(routing.all(), req);
    }

    @PutMapping("/routing/rules")
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    public ResponseEntity<ApiResponse<RoutingRule>> upsert(@RequestParam(required = false) Long id, @RequestBody RoutingRule rule, HttpServletRequest req) {
        return Api.ok(routing.upsert(id, rule, Actor.current()), req);
    }

    @PostMapping("/routing/preview")
    public ResponseEntity<ApiResponse<RoutingService.RouteDecision>> preview(@RequestBody RoutingService.RouteRequest r, HttpServletRequest req) {
        return Api.ok(routing.route(r), req);
    }
}
