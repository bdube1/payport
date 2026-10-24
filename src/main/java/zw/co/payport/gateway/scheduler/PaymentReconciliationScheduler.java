package zw.co.payport.gateway.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.entity.Transaction;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.payments.PaymentCheckStore;
import zw.co.payport.gateway.platform.JobRunner;
import zw.co.payport.gateway.repository.TransactionRepository;
import zw.co.payport.gateway.service.PaymentStatusVerifier;
import zw.co.payport.gateway.service.adaptor.PaymentProviderAdaptor;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The uncertain-outcome resolver: settles payments whose outcome we do not know yet by
 * asking the provider (R6, Phase 2).
 *
 * Rules:
 *  - Only a definite provider answer (SUCCESS with a matching amount, or FAILED) changes
 *    a payment. Time passing NEVER marks a payment FAILED.
 *  - A payment is never re-sent to resolve it; only status queries are made.
 *  - Polling backs off: every 10 seconds for the first 30 minutes (ZimSwitch: at most every
 *    35 s, its rate limit), then every 10 minutes. The customer is told the outcome when the
 *    payment window closes (PaymentTimeoutWatchdog); checks continue only to detect a late
 *    payment, for payport.payments.late-detection-hours (default 24), then the check is
 *    closed as MANUAL and statement reconciliation (Phase 3) is the remaining safety net.
 *  - A definite SUCCESS for a different amount goes straight to MANUAL.
 *
 * The schedule lives in payment_checks, so it survives restarts, and the run is guarded
 * by a JobRunner lease, so with several instances only one resolves at a time. Payments
 * that were pending before this release (no check row) are picked up automatically.
 */
@Slf4j
@Component
public class PaymentReconciliationScheduler {

    public static final String JOB = "payment-resolver";
    public static final Duration GRACE = Duration.ofSeconds(10);
    static final Duration FAST_WINDOW = Duration.ofMinutes(30);
    static final Duration FAST_INTERVAL = Duration.ofSeconds(10);
    static final Duration SLOW_INTERVAL = Duration.ofMinutes(10);
    /** Default for payport.payments.late-detection-hours (was 72 h; owner decision: shorter). */
    static final Duration STOP_AFTER = Duration.ofHours(24);
    /** ZimSwitch (OPPWA) allows two status requests per checkout per minute. */
    static final Duration ZIMSWITCH_MIN_INTERVAL = Duration.ofSeconds(35);
    static final int BATCH = 200;

    private final TransactionRepository transactionRepository;
    private final PaymentStatusVerifier verifier;
    private final TransactionOrchestratorService orchestratorService;
    private final PaymentCheckStore checks;
    private final JobRunner jobs;
    private final boolean enabled;

    public PaymentReconciliationScheduler(TransactionRepository transactionRepository, PaymentStatusVerifier verifier,
                                          TransactionOrchestratorService orchestratorService, PaymentCheckStore checks,
                                          JobRunner jobs, @Value("${payport.payments.resolver.enabled:true}") boolean enabled,
                                          @Value("${payport.payments.late-detection-hours:24}") long lateDetectionHours) {
        this.transactionRepository = transactionRepository;
        this.verifier = verifier;
        this.orchestratorService = orchestratorService;
        this.checks = checks;
        this.jobs = jobs;
        this.enabled = enabled;
        this.stopAfter = Duration.ofHours(lateDetectionHours);
    }

    private final Duration stopAfter;

    public record Summary(int backfilled, int checked, int resolved, int stillOpen, int manual) {}

    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    public void reconcileOpenPayments() {
        if (!enabled) return;
        JobRunner.RunRecord r = jobs.runExclusive(JOB, 600, () -> runOnce(LocalDateTime.now()).toString());
        if (r.outcome() == JobRunner.Outcome.FAILED) log.error("[RESOLVER] run failed: {}", r.summary());
    }

    /** One pass. Public for tests and the ops console; callers outside the scheduler should hold the lease via runExclusive. */
    public Summary runOnce(LocalDateTime now) {
        int backfilled = backfill(now);
        int checked = 0, resolved = 0, open = 0, manual = 0;
        List<String> manualRefs = new ArrayList<>();
        for (PaymentCheckStore.Check c : checks.due(now, BATCH)) {
            try {
                switch (resolveOne(c, now)) {
                    case RESOLVED -> { checked++; resolved++; }
                    case OPEN -> { checked++; open++; }
                    case MANUAL -> { manual++; manualRefs.add(c.reference() + "(" + c.providerCode() + ")"); }
                    case CLOSED -> resolved++;
                }
            } catch (Exception e) {
                log.error("[RESOLVER] Could not resolve {}: {}", c.reference(), e.getMessage(), e);
                checks.reschedule(c.reference(), nextCheckAt(c.submittedAt(), now));
            }
        }
        if (!manualRefs.isEmpty()) {
            log.error("[RESOLVER] {} payment(s) need manual resolution with provider evidence (NOT marked failed): {}",
                    manualRefs.size(), String.join(", ", manualRefs.subList(0, Math.min(50, manualRefs.size()))));
        }
        return new Summary(backfilled, checked, resolved, open, manual);
    }

    private enum Result { RESOLVED, OPEN, MANUAL, CLOSED }

    private Result resolveOne(PaymentCheckStore.Check c, LocalDateTime now) {
        Transaction tx = transactionRepository.findByReference(c.reference()).orElse(null);
        if (tx == null) {
            checks.close(c.reference(), PaymentCheckStore.Status.MANUAL, "transaction not found", now);
            return Result.MANUAL;
        }
        if (!isOpen(tx.getStatus())) {
            checks.close(c.reference(), PaymentCheckStore.Status.RESOLVED, "already " + tx.getStatus(), now);
            return Result.CLOSED;
        }
        Decision d = decide(c.submittedAt(), c.lastCheckedAt(), now, stopAfter);
        if (d == Decision.CHECK && "ZIMSWITCH".equalsIgnoreCase(c.providerCode()) && c.lastCheckedAt() != null
                && Duration.between(c.lastCheckedAt(), now).compareTo(ZIMSWITCH_MIN_INTERVAL) < 0) {
            d = Decision.WAIT;
        }
        if (d == Decision.STOP) {
            checks.close(c.reference(), PaymentCheckStore.Status.MANUAL,
                    "no definite answer after " + stopAfter.toHours() + "h; last: " + c.lastResult(), now);
            return Result.MANUAL;
        }
        if (d == Decision.WAIT) {
            checks.reschedule(c.reference(), nextCheckAt(c.submittedAt(), c.lastCheckedAt() != null ? c.lastCheckedAt() : now));
            return Result.OPEN;
        }

        String provider = c.providerCode() != null ? c.providerCode() : tx.getPaymentProviderCode();
        PaymentStatusVerifier.Verified v = verifier.verify(provider, tx);
        if (verifier.isAmountMismatch(tx, v)) {
            checks.recordCheck(c.reference(), now, v.detail(), now);
            checks.close(c.reference(), PaymentCheckStore.Status.MANUAL,
                    "AMOUNT MISMATCH: provider confirmed " + v.amountCents() + " cents, expected " + tx.collectCents(), now);
            return Result.MANUAL;
        }
        PaymentProviderAdaptor.CallbackEvent event = verifier.toEvent(tx, v, "resolver");
        if (event != null) {
            TransactionOrchestratorService.CallbackOutcome outcome = orchestratorService.handlePaymentCallback(event);
            checks.recordCheck(c.reference(), now, v.status() + " -> " + outcome + ": " + v.detail(), now);
            if (outcome == TransactionOrchestratorService.CallbackOutcome.CONFLICT) {
                checks.close(c.reference(), PaymentCheckStore.Status.MANUAL, "CONFLICT: " + v.detail(), now);
                return Result.MANUAL;
            }
            // APPLIED closes the check inside the orchestrator; NO_CHANGE means another path settled it.
            checks.close(c.reference(), PaymentCheckStore.Status.RESOLVED, v.status() + " (" + outcome + ")", now);
            log.info("[RESOLVER] {} resolved to {} by provider status query", c.reference(), v.status());
            return Result.RESOLVED;
        }
        checks.recordCheck(c.reference(), now, v.status() + ": " + v.detail(), nextCheckAt(c.submittedAt(), now));
        return Result.OPEN;
    }

    /** Opens checks for payments that are pending but have none (e.g. submitted before this release). */
    private int backfill(LocalDateTime now) {
        int n = 0;
        for (Transaction tx : transactionRepository.findByStatusIn(
                List.of(TransactionStatus.PAYMENT_PENDING, TransactionStatus.PAYMENT_TIMEOUT))) {
            if (tx.getPaymentSubmittedAt() == null || tx.getPaymentProviderCode() == null) continue;
            if (checks.openIfAbsent(tx.getReference(), tx.getPaymentProviderCode().toUpperCase(),
                    tx.getPaymentSubmittedAt(), now)) {
                n++;
            }
        }
        if (n > 0) log.info("[RESOLVER] Opened {} check(s) for payments pending without one", n);
        return n;
    }

    private static boolean isOpen(TransactionStatus s) {
        return s == TransactionStatus.PAYMENT_PENDING || s == TransactionStatus.PAYMENT_TIMEOUT;
    }

    enum Decision { CHECK, WAIT, STOP }

    /** Pure scheduling rule, unit-tested. */
    static Decision decide(LocalDateTime submittedAt, LocalDateTime lastCheckedAt, LocalDateTime now) {
        return decide(submittedAt, lastCheckedAt, now, STOP_AFTER);
    }

    static Decision decide(LocalDateTime submittedAt, LocalDateTime lastCheckedAt, LocalDateTime now, Duration stopAfter) {
        Duration age = Duration.between(submittedAt, now);
        if (age.compareTo(GRACE) < 0) return Decision.WAIT;
        if (age.compareTo(stopAfter) >= 0) return Decision.STOP;
        if (age.compareTo(FAST_WINDOW) < 0 || lastCheckedAt == null) return Decision.CHECK;
        return Duration.between(lastCheckedAt, now).compareTo(SLOW_INTERVAL) >= 0 ? Decision.CHECK : Decision.WAIT;
    }

    /** When to ask next after a check at {@code from}; never later than the stop time. */
    static LocalDateTime nextCheckAt(LocalDateTime submittedAt, LocalDateTime from) {
        LocalDateTime earliest = submittedAt.plus(GRACE);
        LocalDateTime next = Duration.between(submittedAt, from).compareTo(FAST_WINDOW) < 0
                ? from.plus(FAST_INTERVAL) : from.plus(SLOW_INTERVAL);
        if (next.isBefore(earliest)) next = earliest;
        LocalDateTime stop = submittedAt.plus(STOP_AFTER);
        return next.isAfter(stop) ? stop : next;
    }
}
