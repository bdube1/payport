package zw.co.payport.gateway.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.entity.Transaction;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.repository.TransactionRepository;
import zw.co.payport.gateway.service.adaptor.PaymentProviderAdaptor;
import zw.co.payport.gateway.service.adaptor.ecocash.EcoCashAdaptor;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconciliationScheduler {

    private final TransactionRepository transactionRepository;
    private final EcoCashAdaptor ecoCashAdaptor;
    private final TransactionOrchestratorService orchestratorService;

    private static final long GIVE_UP_AFTER_MINUTES = 30;
    /** Was 25s -- tightened since speed genuinely matters here: the
      * customer's own EcoCash confirmation on their phone is close to
      * instant, so a long gap before Payport even starts actively
      * checking reads as slow regardless of how reliable the eventual
      * result is. Still leaves the real callback a real head start,
      * just a shorter one. */
    private static final long GRACE_PERIOD_SECONDS = 10;

    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    public void reconcileStalledEcoCashPayments() {
        LocalDateTime graceThreshold = LocalDateTime.now().minusSeconds(GRACE_PERIOD_SECONDS);

        // PAYMENT_TIMEOUT transactions the watchdog already gave up
        // waiting on, plus PAYMENT_PENDING transactions past the grace
        // period -- was PAYMENT_TIMEOUT only, meaning a transaction
        // wasn't actively checked against EcoCash until the full 180s
        // watchdog window had already elapsed. Given how routinely the
        // callback appears not to arrive at all (confirmed in production
        // logs -- no /callbacks/ecocash request anywhere near a payment
        // the customer had already approved), that's a needlessly long
        // wait before even attempting to ask EcoCash directly.
        List<Transaction> stalled = transactionRepository.findByStatusIn(
                        List.of(TransactionStatus.PAYMENT_TIMEOUT, TransactionStatus.PAYMENT_PENDING))
                .stream()
                .filter(tx -> "ECOCASH".equalsIgnoreCase(tx.getPaymentProviderCode()))
                .filter(tx -> tx.getStatus() == TransactionStatus.PAYMENT_TIMEOUT
                        || (tx.getPaymentSubmittedAt() != null && tx.getPaymentSubmittedAt().isBefore(graceThreshold)))
                .toList();

        if (stalled.isEmpty()) return;

        log.info("[RECONCILE] Checking {} EcoCash payment(s) directly against EcoCash", stalled.size());

        for (Transaction tx : stalled) {
            try {
                reconcileOne(tx);
            } catch (Exception e) {
                log.error("[RECONCILE] Could not reconcile {}: {}", tx.getReference(), e.getMessage(), e);
            }
        }
    }

    private void reconcileOne(Transaction tx) throws Exception {
        EcoCashAdaptor.QueryResult result = ecoCashAdaptor.queryTransaction(tx.getReference(), tx.getCustomerPhone());
        String mappedStatus = ecoCashAdaptor.mapEcoCashStatus(result.getTransactionStatus());

        log.info("[RECONCILE] {} -- EcoCash says: {} (mapped: {})", tx.getReference(), result.getTransactionStatus(), mappedStatus);

        if ("SUCCESS".equals(mappedStatus) || "FAILED".equals(mappedStatus)) {
            PaymentProviderAdaptor.CallbackEvent event = PaymentProviderAdaptor.CallbackEvent.builder()
                    .payportReference(tx.getReference())
                    .providerReference(result.getEcocashReference() != null ? result.getEcocashReference() : tx.getProviderReference())
                    .status(mappedStatus)
                    .amountCents(Math.round(result.getAmount() * 100))
                    .currency(result.getCurrency())
                    .timestamp(LocalDateTime.now().toString())
                    .rawPayload("Resolved via reconciliation query, not a direct callback -- EcoCash reported status: " + result.getTransactionStatus())
                    .build();

            orchestratorService.handlePaymentCallback(event);
            log.info("[RECONCILE] {} resolved to {} via reconciliation", tx.getReference(), mappedStatus);
            return;
        }

        long minutesStalled = java.time.Duration.between(tx.getPaymentSubmittedAt(), LocalDateTime.now()).toMinutes();
        if (minutesStalled >= GIVE_UP_AFTER_MINUTES) {
            log.warn("[RECONCILE] {} still unresolved after {} minutes -- giving up, marking failed", tx.getReference(), minutesStalled);
            PaymentProviderAdaptor.CallbackEvent giveUpEvent = PaymentProviderAdaptor.CallbackEvent.builder()
                    .payportReference(tx.getReference())
                    .providerReference(tx.getProviderReference())
                    .status("FAILED")
                    .amountCents(0)
                    .currency(tx.getCurrency())
                    .timestamp(LocalDateTime.now().toString())
                    .rawPayload("Gave up after " + minutesStalled + " minutes of reconciliation attempts -- EcoCash never returned a definitive status.")
                    .build();
            orchestratorService.handlePaymentCallback(giveUpEvent);
        }
    }
}
