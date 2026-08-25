package zw.co.payport.gateway.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processTimeout(Transaction tx) {
        try {
            String reference = tx.getReference();
            LocalDateTime submittedAt = tx.getPaymentSubmittedAt();
            long secondsPending = java.time.Duration.between(submittedAt, LocalDateTime.now()).getSeconds();
            
            log.warn("[WATCHDOG] Transaction {} timed out - pending for {} seconds (since {})",
                    reference, secondsPending, submittedAt);

            // Was PAYMENT_FAILED -- a terminal status with nowhere left
            // to go. This class's own documented state machine says
            // PAYMENT_PENDING -> PAYMENT_TIMEOUT, and PAYMENT_TIMEOUT can
            // still resolve to PAYMENT_CONFIRMED later "after
            // reconciliation" -- exactly what a late-arriving real
            // provider callback is. handlePaymentCallback()'s own
            // duplicate-guard already only accepts a late callback when
            // status is PAYMENT_PENDING or PAYMENT_TIMEOUT, which strongly
            // suggests this was the intended status all along. Jumping
            // straight to PAYMENT_FAILED meant a completely real,
            // successful payment -- customer just took more than 90
            // seconds to notice the USSD prompt and enter their PIN --
            // got silently dropped as a "duplicate" the moment the real
            // confirmation arrived a few seconds later.
            tx.setStatus(TransactionStatus.PAYMENT_TIMEOUT);
            tx.setFailureReason(String.format("No response from customer after %d seconds -- still watching for a late confirmation.", secondsPending));
            tx.setFailureDetails("Customer did not complete payment on USSD prompt within the timeout window. A late provider callback can still confirm this payment.");

            transactionRepository.save(tx);
            
            log.info("[WATCHDOG] Transaction {} marked as PAYMENT_TIMEOUT (not PAYMENT_FAILED -- a late callback can still confirm it)", reference);

        } catch (Exception e) {
            log.error("[WATCHDOG] Failed to process timeout for {}: {}", tx.getReference(), e.getMessage(), e);
        }
    }
}
