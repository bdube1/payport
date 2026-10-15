package zw.co.payport.gateway.psp.platform.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.common.Refs;
import zw.co.payport.gateway.psp.platform.audit.AuditService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Four-eyes control for anything that moves money or changes who gets paid:
 * ledger adjustments, payout-account changes, fee agreements, limits,
 * resolving uncertain payments, manual FX rates, lifting suspensions.
 *
 * Rules enforced here (not left to the UI):
 *  - the approver must be a different person from the requester
 *  - the approver must hold the action's required role (ADMIN always qualifies)
 *  - only one PENDING request per (action, target) at a time
 *  - requests expire after 72h if nobody decides
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    private static final int EXPIRY_HOURS = 72;

    private final ApprovalRequestRepository repository;
    private final Map<String, ApprovalHandler> handlers;
    private final AuditService audit;

    public ApprovalService(ApprovalRequestRepository repository, List<ApprovalHandler> handlers, AuditService audit) {
        this.repository = repository;
        this.handlers = handlers.stream().collect(Collectors.toMap(ApprovalHandler::actionType, Function.identity()));
        this.audit = audit;
    }

    @Transactional
    public ApprovalRequest request(String actionType, String targetRef, String payloadJson, String summary,
                                   String reason, Actor requester) {
        ApprovalHandler handler = handler(actionType);
        if (!repository.findByActionTypeAndTargetRefAndStatus(actionType, targetRef, "PENDING").isEmpty()) {
            throw PspException.conflict("APPROVAL_ALREADY_PENDING",
                    "There is already a pending " + actionType + " request for " + targetRef);
        }
        handler.validate(targetRef, payloadJson);

        ApprovalRequest r = new ApprovalRequest();
        r.setApprovalRef(Refs.newRef("apr"));
        r.setActionType(actionType);
        r.setTargetRef(targetRef);
        r.setPayload(payloadJson);
        r.setSummary(summary.length() > 500 ? summary.substring(0, 500) : summary);
        r.setReason(reason);
        r.setStatus("PENDING");
        r.setRequiredRole(handler.requiredRole());
        r.setRequestedBy(requester.id());
        r.setRequestedAt(LocalDateTime.now());
        r.setExpiresAt(LocalDateTime.now().plusHours(EXPIRY_HOURS));
        repository.save(r);

        audit.record(requester, "APPROVAL_REQUESTED", "APPROVAL", r.getApprovalRef(),
                Map.of("actionType", actionType, "targetRef", targetRef, "summary", r.getSummary()));
        return r;
    }

    @Transactional
    public ApprovalRequest approve(String approvalRef, Actor approver, String note) {
        ApprovalRequest r = pending(approvalRef);
        if (r.getRequestedBy().equalsIgnoreCase(approver.id())) {
            throw PspException.forbidden("FOUR_EYES_VIOLATION", "You cannot approve your own request");
        }
        if (!approver.hasAnyRole(r.getRequiredRole(), "ADMIN")) {
            throw PspException.forbidden("APPROVER_ROLE_REQUIRED", "Approving " + r.getActionType() + " needs role " + r.getRequiredRole());
        }
        String result = handler(r.getActionType()).execute(r.getTargetRef(), r.getPayload(), r.getRequestedBy(), approver.id());
        r.setStatus("EXECUTED");
        r.setDecidedBy(approver.id());
        r.setDecidedAt(LocalDateTime.now());
        r.setDecisionNote(note);
        r.setExecutionResult(result != null && result.length() > 2000 ? result.substring(0, 2000) : result);
        repository.save(r);

        audit.record(approver, "APPROVAL_EXECUTED", "APPROVAL", r.getApprovalRef(),
                Map.of("actionType", r.getActionType(), "targetRef", r.getTargetRef(),
                        "requestedBy", r.getRequestedBy(), "result", String.valueOf(result)));
        log.info("[APPROVAL] {} {} on {} approved by {} (requested by {})",
                r.getApprovalRef(), r.getActionType(), r.getTargetRef(), approver.id(), r.getRequestedBy());
        return r;
    }

    @Transactional
    public ApprovalRequest reject(String approvalRef, Actor decider, String note) {
        ApprovalRequest r = pending(approvalRef);
        boolean selfCancel = r.getRequestedBy().equalsIgnoreCase(decider.id());
        if (!selfCancel && !decider.hasAnyRole(r.getRequiredRole(), "ADMIN")) {
            throw PspException.forbidden("APPROVER_ROLE_REQUIRED", "Rejecting " + r.getActionType() + " needs role " + r.getRequiredRole());
        }
        r.setStatus(selfCancel ? "CANCELLED" : "REJECTED");
        r.setDecidedBy(decider.id());
        r.setDecidedAt(LocalDateTime.now());
        r.setDecisionNote(note);
        repository.save(r);
        audit.record(decider, selfCancel ? "APPROVAL_CANCELLED" : "APPROVAL_REJECTED", "APPROVAL", r.getApprovalRef(),
                Map.of("actionType", r.getActionType(), "targetRef", r.getTargetRef(), "note", String.valueOf(note)));
        return r;
    }

    public List<ApprovalRequest> pending() {
        return repository.findByStatusOrderByRequestedAtAsc("PENDING");
    }

    public List<ApprovalRequest> recent() {
        return repository.findTop200ByOrderByRequestedAtDesc();
    }

    @Scheduled(cron = "0 5 * * * *")
    @Transactional
    public void expireStale() {
        for (ApprovalRequest r : repository.findByStatusAndExpiresAtBefore("PENDING", LocalDateTime.now())) {
            r.setStatus("EXPIRED");
            r.setDecidedAt(LocalDateTime.now());
            repository.save(r);
            audit.record(Actor.system("approval-expiry"), "APPROVAL_EXPIRED", "APPROVAL", r.getApprovalRef(),
                    Map.of("actionType", r.getActionType(), "targetRef", r.getTargetRef()));
        }
    }

    private ApprovalRequest pending(String ref) {
        ApprovalRequest r = repository.findByApprovalRef(ref).orElseThrow(() -> PspException.notFound("Approval request", ref));
        if (!"PENDING".equals(r.getStatus())) {
            throw PspException.conflict("APPROVAL_NOT_PENDING", "Approval " + ref + " is " + r.getStatus());
        }
        if (r.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw PspException.conflict("APPROVAL_EXPIRED", "Approval " + ref + " has expired; raise a new request");
        }
        return r;
    }

    private ApprovalHandler handler(String actionType) {
        ApprovalHandler h = handlers.get(actionType);
        if (h == null) throw PspException.badRequest("UNKNOWN_APPROVAL_ACTION", "No handler for " + actionType);
        return h;
    }
}
