package zw.co.payport.gateway.psp.payment;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.psp.platform.outbox.EventTypes;
import zw.co.payport.gateway.psp.platform.outbox.OutboxService;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;

import java.util.Map;

/**
 * Turns the gateway's in-process status-change events into durable PSP
 * events. The orchestrator publishes TransactionStatusChangedEvent inside
 * its own transaction and this listener runs synchronously in that same
 * transaction, so a PSP event exists if and only if the status change
 * committed. Ledger postings, notifications, fraud/AML monitoring and
 * reporting all consume these events from the outbox, so a slow or failing
 * consumer can never block or roll back a payment.
 */
@Component
public class PaymentEventsBridge {

    private final OutboxService outbox;

    public PaymentEventsBridge(OutboxService outbox) {
        this.outbox = outbox;
    }

    @EventListener
    public void onStatusChanged(TransactionOrchestratorService.TransactionStatusChangedEvent e) {
        String type = switch (e.newStatus()) {
            case "PAYMENT_CONFIRMED" -> EventTypes.PAYMENT_CONFIRMED;
            case "PAYMENT_FAILED" -> EventTypes.PAYMENT_FAILED;
            case "PAYMENT_TIMEOUT" -> EventTypes.PAYMENT_OUTCOME_UNKNOWN;
            case "COMPLETED" -> EventTypes.PAYMENT_COMPLETED;
            case "REFUNDED" -> EventTypes.PAYMENT_REFUNDED;
            default -> null;
        };
        if (type != null) {
            outbox.publish(type, "TRANSACTION", e.transactionReference(),
                    Map.of("reference", e.transactionReference(), "status", e.newStatus()));
        }
    }
}
