package zw.co.payport.gateway.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.entity.Transaction;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.repository.TransactionRepository;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Moves payments that have been PAYMENT_PENDING longer than the timeout to PAYMENT_TIMEOUT.
 *
 * PAYMENT_TIMEOUT is not a failure: the customer may still approve, and the resolver
 * (PaymentReconciliationScheduler) keeps asking the provider. The change is made by the
 * orchestrator under the payment's row lock and only if it is still pending, so a
 * confirmation that lands at the same moment is never overwritten. (The previous version
 * saved a stale entity, which could overwrite PAYMENT_CONFIRMED and skipped the audit log.)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentTimeoutWatchdog {

    private final TransactionRepository transactionRepository;
    private final TransactionOrchestratorService orchestrator;

    @Value("${payport.gateway.payment-timeout-seconds:90}")
    private int paymentTimeoutSeconds;

    @Value("${payport.payments.watchdog.enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void processTimeouts() {
        if (enabled) runOnce(LocalDateTime.now());
    }

    /** Returns how many payments were moved to PAYMENT_TIMEOUT. Safe to run on several instances at once. */
    public int runOnce(LocalDateTime now) {
        List<Transaction> candidates = transactionRepository
                .findByStatusAndPaymentSubmittedAtBefore(TransactionStatus.PAYMENT_PENDING, now.minusSeconds(paymentTimeoutSeconds));
        if (candidates == null || candidates.isEmpty()) return 0;

        int moved = 0;
        for (Transaction tx : candidates) {
            long secondsPending = Duration.between(tx.getPaymentSubmittedAt(), now).getSeconds();
            try {
                if (orchestrator.markPaymentTimedOut(tx.getReference(), secondsPending)) {
                    moved++;
                    log.info("[WATCHDOG] {} pending for {}s -> PAYMENT_TIMEOUT (a late confirmation can still complete it)",
                            tx.getReference(), secondsPending);
                }
            } catch (Exception e) {
                log.error("[WATCHDOG] Failed to process timeout for {}: {}", tx.getReference(), e.getMessage(), e);
            }
        }
        return moved;
    }
}
