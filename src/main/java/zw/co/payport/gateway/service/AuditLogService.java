package zw.co.payport.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.entity.TransactionAuditLog;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.repository.TransactionAuditLogRepository;

import java.util.List;

/**
 * AuditLogService - WRITE-ONLY
 *
 * This service has NO update() or delete() methods by design.
 * The audit log is append-only. Every state change must be recorded here.
 *
 * Uses REQUIRES_NEW propagation so audit log writes succeed
 * even if the main transaction rolls back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final TransactionAuditLogRepository auditLogRepository;

    /**
     * Write an audit log entry.
     * Called on EVERY transaction state change.
     * actor: "SYSTEM", "ECOCASH_CALLBACK", "ONEMONEY_CALLBACK", "OPS:username"
     *
     * Joins the caller's transaction (was REQUIRES_NEW): the audit row now commits or rolls
     * back together with the status change it describes, and writing it no longer needs a
     * second pooled connection while the caller holds the payment's row lock (with
     * REQUIRES_NEW, enough requests waiting on one payment could exhaust the pool and stall
     * the lock holder).
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void log(String transactionReference,
                    TransactionStatus fromStatus,
                    TransactionStatus toStatus,
                    String actor,
                    String notes,
                    String payloadSnapshot) {

        TransactionAuditLog entry = TransactionAuditLog.builder()
                .transactionReference(transactionReference)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .actor(actor)
                .notes(notes)
                .payloadSnapshot(payloadSnapshot)
                .build();

        auditLogRepository.save(entry);
        log.debug("[AUDIT] {} {} → {} by {}", transactionReference, fromStatus, toStatus, actor);
    }

    @Transactional(readOnly = true)
    public List<TransactionAuditLog> getTimeline(String reference) {
        return auditLogRepository.findByTransactionReferenceOrderByCreatedAtAsc(reference);
    }
}
