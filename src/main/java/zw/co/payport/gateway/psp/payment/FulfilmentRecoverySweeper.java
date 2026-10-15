package zw.co.payport.gateway.psp.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.psp.legacy.LegacyGateway;
import zw.co.payport.gateway.psp.legacy.LegacyPayments;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The gateway's fulfilment queue lives in memory, so a restart between
 * "payment confirmed" and "airtime/token delivered" leaves a customer who
 * has paid with nothing. This sweeper re-queues confirmed payments that
 * have not started fulfilment within two minutes. The orchestrator ignores
 * jobs for transactions no longer in PAYMENT_CONFIRMED, so a duplicate
 * enqueue is harmless.
 */
@Component
public class FulfilmentRecoverySweeper {

    private static final Logger log = LoggerFactory.getLogger(FulfilmentRecoverySweeper.class);

    private final LegacyGateway gateway;
    private final LegacyPayments payments;

    public FulfilmentRecoverySweeper(LegacyGateway gateway, LegacyPayments payments) {
        this.gateway = gateway;
        this.payments = payments;
    }

    @Scheduled(fixedDelay = 120_000, initialDelay = 90_000)
    public void sweep() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(2);
        List<LegacyGateway.TransactionSnapshot> stuck = gateway.transactionsInStatus(List.of(TransactionStatus.PAYMENT_CONFIRMED)).stream()
                .filter(t -> t.paymentConfirmedAt() != null && t.paymentConfirmedAt().isBefore(cutoff))
                .toList();
        for (LegacyGateway.TransactionSnapshot t : stuck) {
            log.warn("[FULFILMENT-RECOVERY] {} confirmed at {} but fulfilment never started: re-queueing", t.reference(), t.paymentConfirmedAt());
            payments.requeueFulfilment(t.reference());
        }
    }
}
