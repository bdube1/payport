package zw.co.payport.gateway.psp.platform;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.Api;
import zw.co.payport.gateway.psp.ledger.LedgerIntegrityService;
import zw.co.payport.gateway.psp.platform.approval.ApprovalRequest;
import zw.co.payport.gateway.psp.platform.approval.ApprovalService;
import zw.co.payport.gateway.psp.platform.audit.AuditEvent;
import zw.co.payport.gateway.psp.platform.audit.AuditService;
import zw.co.payport.gateway.psp.platform.audit.ChainSealService;
import zw.co.payport.gateway.psp.platform.outbox.OutboxDispatcher;
import zw.co.payport.gateway.psp.platform.outbox.OutboxEvent;
import zw.co.payport.gateway.psp.platform.outbox.OutboxEventRepository;

import java.util.List;
import java.util.Map;

/** Approvals inbox, event bus health, audit trail and integrity checks. */
@RestController
@RequestMapping("/api/v1/psp/admin/platform")
@PreAuthorize("hasAnyRole('OPS','ADMIN','FINANCE','COMPLIANCE')")
public class PlatformAdminController {

    public record Decision(String note) {}

    private final ApprovalService approvals;
    private final AuditService audit;
    private final ChainSealService seals;
    private final OutboxEventRepository events;
    private final OutboxDispatcher dispatcher;
    private final LedgerIntegrityService integrity;

    public PlatformAdminController(ApprovalService approvals, AuditService audit, ChainSealService seals,
                                   OutboxEventRepository events, OutboxDispatcher dispatcher, LedgerIntegrityService integrity) {
        this.approvals = approvals;
        this.audit = audit;
        this.seals = seals;
        this.events = events;
        this.dispatcher = dispatcher;
        this.integrity = integrity;
    }

    @GetMapping("/approvals/pending")
    public ResponseEntity<ApiResponse<List<ApprovalRequest>>> pending(HttpServletRequest req) {
        return Api.ok(approvals.pending(), req);
    }

    @GetMapping("/approvals")
    public ResponseEntity<ApiResponse<List<ApprovalRequest>>> recent(HttpServletRequest req) {
        return Api.ok(approvals.recent(), req);
    }

    @PostMapping("/approvals/{ref}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<ApprovalRequest>> approve(@PathVariable String ref, @RequestBody(required = false) Decision d, HttpServletRequest req) {
        return Api.ok(approvals.approve(ref, Actor.current(), d == null ? null : d.note()), req);
    }

    @PostMapping("/approvals/{ref}/reject")
    public ResponseEntity<ApiResponse<ApprovalRequest>> reject(@PathVariable String ref, @RequestBody(required = false) Decision d, HttpServletRequest req) {
        return Api.ok(approvals.reject(ref, Actor.current(), d == null ? null : d.note()), req);
    }

    @GetMapping("/audit/{objectType}/{objectRef}")
    public ResponseEntity<ApiResponse<List<AuditEvent>>> audit(@PathVariable String objectType, @PathVariable String objectRef, HttpServletRequest req) {
        return Api.ok(audit.forObject(objectType, objectRef), req);
    }

    @GetMapping("/chains/{chain}/verify")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<ChainSealService.ChainReport>> verify(@PathVariable String chain, HttpServletRequest req) {
        return Api.ok(seals.verify(chain.toUpperCase()), req);
    }

    @PostMapping("/integrity/run")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public ResponseEntity<ApiResponse<LedgerIntegrityService.IntegrityReport>> integrity(HttpServletRequest req) {
        return Api.ok(integrity.run(), req);
    }

    @GetMapping("/integrity/last")
    public ResponseEntity<ApiResponse<LedgerIntegrityService.IntegrityReport>> lastIntegrity(HttpServletRequest req) {
        return Api.ok(integrity.lastReport(), req);
    }

    @GetMapping("/events/health")
    public ResponseEntity<ApiResponse<Map<String, Long>>> eventHealth(HttpServletRequest req) {
        return Api.ok(Map.of("new", events.countByStatus("NEW"), "retry", events.countByStatus("RETRY"), "dead", events.countByStatus("DEAD")), req);
    }

    @GetMapping("/events/dead")
    public ResponseEntity<ApiResponse<List<OutboxEvent>>> dead(HttpServletRequest req) {
        return Api.ok(events.findTop100ByStatusOrderByIdDesc("DEAD"), req);
    }

    @PostMapping("/events/{id}/requeue")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ResponseEntity<ApiResponse<String>> requeue(@PathVariable Long id, HttpServletRequest req) {
        dispatcher.requeue(id);
        audit.record(Actor.current(), "EVENT_REQUEUED", "OUTBOX_EVENT", String.valueOf(id), Map.of());
        return Api.ok("requeued", req);
    }
}
