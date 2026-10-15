package zw.co.payport.gateway.psp.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.psp.common.BreakReporter;
import zw.co.payport.gateway.psp.legacy.LegacyGateway;
import zw.co.payport.gateway.psp.legacy.LegacyPayments;
import zw.co.payport.gateway.psp.platform.outbox.EventTypes;
import zw.co.payport.gateway.psp.platform.outbox.OutboxService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Resolves payments whose outcome PayPort does not know.
 *
 * Replaces the gateway's EcoCash-only reconciliation scheduler, which marked
 * a payment FAILED after 30 minutes of silence even though the customer may
 * have been debited. Here a timeout only ever means "unknown":
 *
 *  - every rail with a status API is polled on the {@link PollingPolicy} back-off
 *  - a definitive SUCCESS / FAILED from the rail is applied through the normal state machine
 *  - amounts are checked; a mismatch is escalated, never applied
 *  - at 30 minutes an UNCERTAIN_PAYMENT break is opened for ops and the customer is told we are verifying
 *  - after 72 hours polling stops; only a provider statement match or a four-eyes manual
 *    resolution with evidence (PAYMENT_RESOLVE approval) can close it
 */
@Service
public class UncertainPaymentResolver {

    private static final Logger log = LoggerFactory.getLogger(UncertainPaymentResolver.class);
    private static final List<TransactionStatus> OPEN = List.of(TransactionStatus.PAYMENT_PENDING, TransactionStatus.PAYMENT_TIMEOUT);
    private static final Duration GRACE = Duration.ofSeconds(10);

    private final PaymentCheckRepository checks;
    private final LegacyGateway gateway;
    private final LegacyPayments payments;
    private final BreakReporter breaks;
    private final OutboxService outbox;
    private final TransactionTemplate newTx;

    public UncertainPaymentResolver(PaymentCheckRepository checks, LegacyGateway gateway, LegacyPayments payments,
                                    BreakReporter breaks, OutboxService outbox, PlatformTransactionManager txManager) {
        this.checks = checks;
        this.gateway = gateway;
        this.payments = payments;
        this.breaks = breaks;
        this.outbox = outbox;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${payport.psp.resolver.poll-ms:15000}", initialDelay = 20_000)
    public void run() {
        discover();
        for (PaymentCheck c : checks.findByStatusAndNextCheckAtBeforeOrderByNextCheckAtAsc("OPEN", LocalDateTime.now(), PageRequest.of(0, 200))) {
            try {
                check(c.getReference());
            } catch (Exception e) {
                log.error("[RESOLVER] Check of {} failed: {}", c.getReference(), e.getMessage());
            }
        }
    }

    /** Make sure every open payment has a check row. */
    private void discover() {
        for (LegacyGateway.TransactionSnapshot tx : gateway.transactionsInStatus(OPEN)) {
            if (tx.paymentProviderCode() == null || tx.paymentProviderCode().startsWith("TEST")) continue;
            if (checks.findByReference(tx.reference()).isPresent()) continue;
            try {
                newTx.executeWithoutResult(s -> {
                    PaymentCheck c = new PaymentCheck();
                    c.setReference(tx.reference());
                    c.setProvider(tx.paymentProviderCode());
                    c.setStatus("OPEN");
                    c.setAttempts(0);
                    c.setFirstSeenAt(tx.createdAt() != null ? tx.createdAt() : LocalDateTime.now());
                    c.setNextCheckAt(LocalDateTime.now().plus(GRACE));
                    c.setInvestigationOpened(false);
                    checks.saveAndFlush(c);
                });
            } catch (DataIntegrityViolationException raced) {
                // another node created it
            }
        }
    }

    /** One polling step for one payment. Public so ops can force an immediate check. */
    public String check(String reference) {
        PaymentCheck c = checks.findByReference(reference).orElse(null);
        if (c == null) return "NO_CHECK";
        LegacyGateway.TransactionSnapshot tx = gateway.transaction(reference).orElse(null);
        if (tx == null) return close(c, "MISSING", "SYSTEM");
        if (!OPEN.contains(tx.status())) return close(c, tx.status().name(), "STATE_MACHINE");

        Duration age = Duration.between(c.getFirstSeenAt(), LocalDateTime.now());
        if (tx.status() == TransactionStatus.PAYMENT_PENDING && PollingPolicy.shouldMarkUnknown(age)) {
            payments.markOutcomeUnknown(reference, "No definitive answer from " + c.getProvider() + " after " + age.toSeconds() + "s");
        }

        LegacyPayments.ProviderStatus q = payments.queryProvider(c.getProvider(), reference, tx.providerReference(), tx.customerPhone());
        String result = q.status() + (q.detail() != null ? " (" + q.detail() + ")" : "");

        if ("SUCCESS".equals(q.status())) {
            if (q.amountMinor() != null && q.amountMinor() != tx.amountCents()) {
                breaks.report("PAYMENT", "AMOUNT_MISMATCH", reference, tx.currency(), tx.amountCents(), q.amountMinor(),
                        c.getProvider() + " reports success for a different amount; not applied");
                return record(c, age, "AMOUNT_MISMATCH " + result, true);
            }
            payments.applyOutcome(reference, "SUCCESS", q.providerReference(), tx.amountCents(), tx.currency(),
                    "Resolved by status query: " + q.detail(), null);
            return close(c, "SUCCESS", "PROVIDER_QUERY");
        }
        if ("FAILED".equals(q.status())) {
            payments.applyOutcome(reference, "FAILED", q.providerReference(), 0, tx.currency(),
                    "Resolved by status query: " + q.detail(), "Your payment was not completed by " + c.getProvider() + ".");
            return close(c, "FAILED", "PROVIDER_QUERY");
        }
        return record(c, age, result, false);
    }

    private String record(PaymentCheck c, Duration age, String result, boolean forceEscalate) {
        newTx.executeWithoutResult(s -> {
            PaymentCheck fresh = checks.findById(c.getId()).orElseThrow();
            fresh.setAttempts(fresh.getAttempts() + 1);
            fresh.setLastCheckedAt(LocalDateTime.now());
            fresh.setLastResult(result.length() > 500 ? result.substring(0, 500) : result);
            if ((forceEscalate || PollingPolicy.shouldEscalate(age)) && !fresh.isInvestigationOpened()) {
                fresh.setInvestigationOpened(true);
                breaks.report("PAYMENT", "UNCERTAIN_PAYMENT", fresh.getReference(), null, null, null,
                        "Outcome still unknown after " + age.toMinutes() + " min. Last provider answer: " + result);
                outbox.publish(EventTypes.PAYMENT_OUTCOME_UNKNOWN, "TRANSACTION", fresh.getReference(),
                        Map.of("reference", fresh.getReference(), "provider", String.valueOf(fresh.getProvider()),
                               "ageMinutes", age.toMinutes()));
            }
            if (PollingPolicy.shouldStopAutoPolling(age)) {
                fresh.setStatus("MANUAL");
            } else {
                fresh.setNextCheckAt(LocalDateTime.now().plus(PollingPolicy.nextDelay(age)));
            }
            checks.save(fresh);
        });
        return "PENDING: " + result;
    }

    private String close(PaymentCheck c, String outcome, String by) {
        newTx.executeWithoutResult(s -> {
            PaymentCheck fresh = checks.findById(c.getId()).orElseThrow();
            fresh.setStatus("RESOLVED");
            fresh.setResolvedOutcome(outcome.length() > 20 ? outcome.substring(0, 20) : outcome);
            fresh.setResolvedBy(by);
            fresh.setResolvedAt(LocalDateTime.now());
            checks.save(fresh);
        });
        log.info("[RESOLVER] {} resolved: {} via {}", c.getReference(), outcome, by);
        return "RESOLVED: " + outcome;
    }

    public List<PaymentCheck> unresolved() {
        List<PaymentCheck> l = new java.util.ArrayList<>(checks.findByStatusOrderByFirstSeenAtAsc("OPEN"));
        l.addAll(checks.findByStatusOrderByFirstSeenAtAsc("MANUAL"));
        return l;
    }
}
