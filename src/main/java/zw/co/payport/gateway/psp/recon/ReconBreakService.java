package zw.co.payport.gateway.psp.recon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.BreakReporter;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.common.Refs;
import zw.co.payport.gateway.psp.ledger.domain.AccountCategory;
import zw.co.payport.gateway.psp.ledger.domain.AccountKey;
import zw.co.payport.gateway.psp.platform.approval.ApprovalRequest;
import zw.co.payport.gateway.psp.platform.approval.ApprovalService;
import zw.co.payport.gateway.psp.platform.audit.AuditService;
import zw.co.payport.gateway.psp.platform.outbox.EventTypes;
import zw.co.payport.gateway.psp.platform.outbox.OutboxService;

import java.time.LocalDateTime;
import java.util.*;

/**
 * The exceptions queue. Every module reports discrepancies here; nothing
 * in PayPort silently "fixes" a balance. A break is closed either
 *  - with no money movement (explained, e.g. timing difference), by OPS/FINANCE, audited; or
 *  - with a ledger adjustment, which needs four-eyes approval (LEDGER_ADJUSTMENT).
 */
@Service
public class ReconBreakService implements BreakReporter {

    private static final Logger log = LoggerFactory.getLogger(ReconBreakService.class);
    public static final Set<String> OPEN_STATES = Set.of("OPEN", "INVESTIGATING");
    public static final String APPROVE_ADJUSTMENT = "LEDGER_ADJUSTMENT";

    public record AdjustmentRequest(String currency, String debitAccountCode, String creditAccountCode, long amountMinor, String reason) {}

    private final ReconBreakRepository breaks;
    private final OutboxService outbox;
    private final AuditService audit;
    private final ApprovalService approvals;
    private final ObjectMapper om;
    private final TransactionTemplate newTx;

    public ReconBreakService(ReconBreakRepository breaks, OutboxService outbox, AuditService audit, ApprovalService approvals,
                             ObjectMapper om, PlatformTransactionManager txManager) {
        this.breaks = breaks;
        this.outbox = outbox;
        this.audit = audit;
        this.approvals = approvals;
        this.om = om;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Runs in its own transaction so a break is recorded even when the caller then rolls back (or is read-only). */
    @Override
    public void report(String source, String breakType, String reference, String currency,
                       Long expectedMinor, Long actualMinor, String details) {
        String ref = reference == null ? "unknown" : (reference.length() > 150 ? reference.substring(0, 150) : reference);
        try {
            newTx.executeWithoutResult(s -> upsert(source, breakType, ref, currency, expectedMinor, actualMinor, details));
        } catch (DataIntegrityViolationException raced) {
            newTx.executeWithoutResult(s -> upsert(source, breakType, ref, currency, expectedMinor, actualMinor, details));
        } catch (Exception e) {
            // Reporting must never break the caller, but losing a break is serious: shout.
            log.error("[RECON] COULD NOT RECORD BREAK {} {} {}: {} ({})", source, breakType, ref, details, e.getMessage());
        }
    }

    private void upsert(String source, String breakType, String ref, String currency, Long expected, Long actual, String details) {
        Optional<ReconBreak> existing = breaks.findBySourceAndBreakTypeAndReference(source, breakType, ref);
        LocalDateTime now = LocalDateTime.now();
        if (existing.isPresent()) {
            ReconBreak b = existing.get();
            b.setOccurrences(b.getOccurrences() + 1);
            b.setLastSeenAt(now);
            b.setDetails(trim(details, 60_000));
            if (expected != null) b.setExpectedMinor(expected);
            if (actual != null) b.setActualMinor(actual);
            if (!OPEN_STATES.contains(b.getStatus())) {
                b.setStatus("OPEN");
                b.setResolutionNote(trim("Reopened " + now + " after recurring. Previous: " + b.getResolutionNote(), 2000));
            }
            breaks.save(b);
            return;
        }
        ReconBreak b = new ReconBreak();
        b.setBreakRef(Refs.newRef("brk"));
        b.setSource(source);
        b.setBreakType(breakType);
        b.setReference(ref);
        b.setCurrency(currency == null ? null : AccountKey.normaliseCurrency(currency));
        b.setExpectedMinor(expected);
        b.setActualMinor(actual);
        b.setDetails(trim(details, 60_000));
        b.setStatus("OPEN");
        b.setOccurrences(1);
        b.setFirstSeenAt(now);
        b.setLastSeenAt(now);
        breaks.saveAndFlush(b);
        outbox.publish(EventTypes.RECON_BREAK_OPENED, "RECON_BREAK", b.getBreakRef(), Map.of(
                "breakRef", b.getBreakRef(), "source", source, "breakType", breakType, "reference", ref));
        log.warn("[RECON] Break {} opened: {} {} {} -- {}", b.getBreakRef(), source, breakType, ref, details);
    }

    public List<ReconBreak> open() {
        return breaks.findByStatusInOrderByLastSeenAtDesc(OPEN_STATES);
    }

    public ReconBreak get(String breakRef) {
        return breaks.findByBreakRef(breakRef).orElseThrow(() -> PspException.notFound("Break", breakRef));
    }

    public Map<String, Long> openCountsByType() {
        Map<String, Long> m = new TreeMap<>();
        for (Object[] r : breaks.openCountsByType()) m.put(String.valueOf(r[0]), ((Number) r[1]).longValue());
        return m;
    }

    @Transactional
    public ReconBreak assign(String breakRef, String assignee, Actor actor) {
        ReconBreak b = get(breakRef);
        b.setAssignedTo(assignee);
        b.setStatus("INVESTIGATING");
        breaks.save(b);
        audit.record(actor, "BREAK_ASSIGNED", "RECON_BREAK", breakRef, Map.of("assignee", assignee));
        return b;
    }

    /** Close a break that needs no money movement (timing difference, duplicate report, explained). */
    @Transactional
    public ReconBreak resolveWithoutAdjustment(String breakRef, String resolution, String note, Actor actor) {
        if (note == null || note.length() < 10) throw PspException.badRequest("NOTE_REQUIRED", "Explain the resolution (at least 10 characters)");
        ReconBreak b = get(breakRef);
        if (!OPEN_STATES.contains(b.getStatus())) throw PspException.conflict("NOT_OPEN", "Break is " + b.getStatus());
        b.setStatus("RESOLVED");
        b.setResolution(resolution == null ? "EXPLAINED" : resolution);
        b.setResolutionNote(trim(note, 2000));
        b.setResolvedBy(actor.id());
        b.setResolvedAt(LocalDateTime.now());
        breaks.save(b);
        audit.record(actor, "BREAK_RESOLVED", "RECON_BREAK", breakRef, Map.of("resolution", b.getResolution(), "note", note));
        return b;
    }

    /** Propose a ledger adjustment to clear a break. Executes only when a second person approves. */
    @Transactional
    public ApprovalRequest proposeAdjustment(String breakRef, AdjustmentRequest r, Actor actor) {
        ReconBreak b = get(breakRef);
        if (!OPEN_STATES.contains(b.getStatus())) throw PspException.conflict("NOT_OPEN", "Break is " + b.getStatus());
        if (r.amountMinor() <= 0) throw PspException.badRequest("INVALID_AMOUNT", "Amount must be positive");
        if (r.reason() == null || r.reason().length() < 10) throw PspException.badRequest("REASON_REQUIRED", "Explain the adjustment");
        parseAccount(r.debitAccountCode());
        parseAccount(r.creditAccountCode());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("breakRef", breakRef);
        payload.put("currency", AccountKey.normaliseCurrency(r.currency()));
        payload.put("debit", r.debitAccountCode());
        payload.put("credit", r.creditAccountCode());
        payload.put("amountMinor", r.amountMinor());
        payload.put("reason", r.reason());
        String json;
        try { json = om.writeValueAsString(payload); } catch (Exception e) { throw new IllegalStateException(e); }
        return approvals.request(APPROVE_ADJUSTMENT, breakRef, json,
                String.format("Adjust %s %d: Dr %s / Cr %s for break %s", r.currency(), r.amountMinor(),
                        r.debitAccountCode(), r.creditAccountCode(), breakRef), r.reason(), actor);
    }

    /** Parses "CATEGORY:OWNER:CCY" into an AccountKey, validating the category. */
    public static AccountKey parseAccount(String code) {
        String[] p = code == null ? new String[0] : code.split(":");
        if (p.length != 3) throw PspException.badRequest("INVALID_ACCOUNT", "Account code must be CATEGORY:OWNER:CURRENCY, got " + code);
        try {
            return new AccountKey(AccountCategory.valueOf(p[0]), p[1], p[2]);
        } catch (IllegalArgumentException e) {
            throw PspException.badRequest("INVALID_ACCOUNT", e.getMessage());
        }
    }

    private static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
