package zw.co.payport.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.repository.TransactionRepository;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;

/**
 * Listens for FulfilmentJobEvents and processes them asynchronously.
 * This decouples payment confirmation from service delivery.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FulfilmentJobListener {

    private final TransactionOrchestratorService orchestratorService;
    private final TransactionRepository transactionRepository;

    @Async
    @EventListener
    public void onFulfilmentJob(FulfilmentQueueService.FulfilmentJobEvent event) {
        log.info("[FULFILMENT_WORKER] Processing job for: {}", event.reference());

        try {
            // Check if transaction is ready for fulfilment before proceeding
            var txOpt = transactionRepository.findByReference(event.reference());
            if (txOpt.isPresent()) {
                var tx = txOpt.get();
                TransactionStatus status = tx.getStatus();

                // If transaction is not ready, reschedule with a small delay
                if (status != TransactionStatus.PAYMENT_CONFIRMED
                        && status != TransactionStatus.FULFILMENT_RETRY) {
                    log.warn("[FULFILMENT_WORKER] Transaction {} is in status {} - not ready for fulfilment. Rescheduling...",
                            event.reference(), status);
                    // Reschedule with a short delay (1 second)
                    Thread.sleep(1000);
                    orchestratorService.executeFulfilment(event.reference());
                    return;
                }
            }

            orchestratorService.executeFulfilment(event.reference());
        } catch (InterruptedException e) {
            log.warn("[FULFILMENT_WORKER] Interrupted while waiting to process {}: {}",
                    event.reference(), e.getMessage());
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("[FULFILMENT_WORKER] Job processing failed for {}: {}",
                    event.reference(), e.getMessage(), e);
        }
    }
}