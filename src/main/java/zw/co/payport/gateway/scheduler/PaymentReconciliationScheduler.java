package zw.co.payport.gateway.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.entity.Transaction;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.repository.TransactionRepository;
import zw.co.payport.gateway.service.PaymentStatusVerifier;
import zw.co.payport.gateway.service.adaptor.PaymentProviderAdaptor;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Resolves EcoCash and OneMoney payments whose outcome we do not know yet, by asking
 * the provider (Phase 0.5 containment for R4 and R6).
 *
 * Rules:
 *  - Only a definite provider answer (SUCCESS with a matching amount, or FAILED)
 *    changes a payment. A callback alone never does (see CallbackController).
 *  - Time passing NEVER marks a payment FAILED. The previous version gave up after
 *    30 minutes and marked the payment failed, which could fail a payment the
 *    customer had in fact made.
 *  - Polling backs off: every run for the first 30 minutes, then every 10 minutes,
 *    and stops after 72 hours. At that point the payment is reported for manual
 *    resolution through the ops tools, with the provider's evidence.
 *
 * Limitation (Phase 2 replaces this): the back-off clock lives in memory, so after a
 * restart every open payment is checked once more immediately. That is safe, because
 * checks only read from the provider. This scheduler assumes a single application
 * instance, like every scheduler in the gateway today.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconciliationScheduler {

    static final Duration GRACE = Duration.ofSeconds(10);
    static final Duration FAST_WINDOW = Duration.ofMinutes(30);
    static final Duration SLOW_INTERVAL = Duration.ofMinutes(10);
    static final Duration STOP_AFTER = Duration.ofHours(72);

    private final TransactionRepository transactionRepository;
    private final PaymentStatusVerifier verifier;
    private final TransactionOrchestratorService orchestratorService;

    private final Map<String, LocalDateTime> lastChecked = new ConcurrentHashMap<>();
    private final Set<String> reportedForManualResolution = ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    public void reconcileOpenPayments() {
        LocalDateTime now = LocalDateTime.now();
        List<Transaction> open = transactionRepository.findByStatusIn(
                        List.of(TransactionStatus.PAYMENT_TIMEOUT, TransactionStatus.PAYMENT_PENDING))
                .stream()
                .filter(tx -> verifier.requiresVerification(tx.getPaymentProviderCode()))
                .filter(tx -> tx.getPaymentSubmittedAt() != null)
                .toList();

        // Forget payments that are no longer open.
        Set<String> openRefs = open.stream().map(Transaction::getReference).collect(Collectors.toSet());
        lastChecked.keySet().retainAll(openRefs);
        reportedForManualResolution.retainAll(openRefs);

        List<String> newlyStopped = new java.util.ArrayList<>();
        for (Transaction tx : open) {
            Decision d = decide(tx.getPaymentSubmittedAt(), lastChecked.get(tx.getReference()), now);
            if (d == Decision.STOP) {
                if (reportedForManualResolution.add(tx.getReference())) {
                    newlyStopped.add(tx.getReference() + "(" + tx.getPaymentProviderCode() + ")");
                }
                continue;
            }
            if (d == Decision.WAIT) continue;
            lastChecked.put(tx.getReference(), now);
            try {
                reconcileOne(tx);
            } catch (Exception e) {
                log.error("[RECONCILE] Could not reconcile {}: {}", tx.getReference(), e.getMessage(), e);
            }
        }
        if (!newlyStopped.isEmpty()) {
            // One summary line, not one per payment: the first run after deploy may find an old backlog.
            log.error("[RECONCILE] {} payment(s) have had no definite provider answer for over {} hours -- automatic checks "
                            + "stopped; they need manual resolution with provider evidence and were NOT marked failed: {}{}",
                    newlyStopped.size(), STOP_AFTER.toHours(),
                    String.join(", ", newlyStopped.subList(0, Math.min(50, newlyStopped.size()))),
                    newlyStopped.size() > 50 ? " ..." : "");
        }
    }

    enum Decision { CHECK, WAIT, STOP }

    /** Pure scheduling rule, unit-tested. */
    static Decision decide(LocalDateTime submittedAt, LocalDateTime lastCheckedAt, LocalDateTime now) {
        Duration age = Duration.between(submittedAt, now);
        if (age.compareTo(GRACE) < 0) return Decision.WAIT;
        if (age.compareTo(STOP_AFTER) >= 0) return Decision.STOP;
        if (age.compareTo(FAST_WINDOW) < 0 || lastCheckedAt == null) return Decision.CHECK;
        return Duration.between(lastCheckedAt, now).compareTo(SLOW_INTERVAL) >= 0 ? Decision.CHECK : Decision.WAIT;
    }

    private void reconcileOne(Transaction tx) {
        PaymentStatusVerifier.Verified v = verifier.verify(tx.getPaymentProviderCode(), tx);
        PaymentProviderAdaptor.CallbackEvent event = verifier.toEvent(tx, v, "reconciliation");
        if (event == null) {
            log.debug("[RECONCILE] {} still open: provider says {} ({})", tx.getReference(), v.status(), v.detail());
            return;
        }
        orchestratorService.handlePaymentCallback(event);
        log.info("[RECONCILE] {} resolved to {} by provider status query", tx.getReference(), v.status());
    }
}
