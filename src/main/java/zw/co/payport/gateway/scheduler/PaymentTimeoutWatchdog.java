package zw.co.payport.gateway.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.entity.Transaction;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.repository.TransactionRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Payment Timeout Watchdog
 * 
 * Scans for PAYMENT_PENDING transactions that have exceeded the timeout period
 * and marks them as PAYMENT_FAILED.
 * 
 * Timeout: 90 seconds for mobile money (EcoCash/OneMoney)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentTimeoutWatchdog {

    private final TransactionRepository transactionRepository;
    private final zw.co.payport.gateway.service.impl.TransactionOrchestratorService orchestratorService;

    @Value("${payport.gateway.payment-timeout-seconds:90}")
    private int paymentTimeoutSeconds;

    /**
     * Runs every 30 seconds to check for timed-out PAYMENT_PENDING transactions
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void processTimeouts() {
        LocalDateTime timeoutThreshold = LocalDateTime.now().minusSeconds(paymentTimeoutSeconds);
        
        // Use a native query to find timed out transactions
        List<Transaction> timedOutTransactions = transactionRepository
                .findByStatusAndPaymentSubmittedAtBefore(TransactionStatus.PAYMENT_PENDING, timeoutThreshold);

        if (timedOutTransactions == null || timedOutTransactions.isEmpty()) {
            return;
        }

        log.info("[WATCHDOG] Found {} timed-out PAYMENT_PENDING transactions", timedOutTransactions.size());

        for (Transaction tx : timedOutTransactions) {
            processTimeout(tx);
        }
    }

    /**
     * PSP: delegates to the orchestrator so the PAYMENT_PENDING -> PAYMENT_TIMEOUT
     * move goes through the audited state machine (it was a direct setStatus with
     * no audit row and no status event). PAYMENT_TIMEOUT means "outcome unknown",
     * never "failed": the PSP UncertainPaymentResolver keeps asking the provider.
     */
    public void processTimeout(Transaction tx) {
        try {
            long secondsPending = java.time.Duration.between(tx.getPaymentSubmittedAt(), LocalDateTime.now()).getSeconds();
            orchestratorService.markPaymentOutcomeUnknown(tx.getReference(),
                    "No provider confirmation after " + secondsPending + "s -- still watching for a late confirmation");
            log.info("[WATCHDOG] {} marked PAYMENT_TIMEOUT (outcome unknown, not failed)", tx.getReference());
        } catch (Exception e) {
            log.error("[WATCHDOG] Failed to process timeout for {}: {}", tx.getReference(), e.getMessage(), e);
        }
    }
}
