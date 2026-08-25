package zw.co.payport.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionStatusSyncListener {

    private final CheckoutSessionService checkoutSessionService;

    @EventListener
    public void onTransactionStatusChanged(TransactionOrchestratorService.TransactionStatusChangedEvent event) {
        try {
            checkoutSessionService.syncFromTransactionReference(event.transactionReference(), event.newStatus());
        } catch (Exception e) {
            log.warn("[CHECKOUT_SYNC] Could not sync checkout session for transaction {}: {}",
                    event.transactionReference(), e.getMessage());
        }
    }
}
