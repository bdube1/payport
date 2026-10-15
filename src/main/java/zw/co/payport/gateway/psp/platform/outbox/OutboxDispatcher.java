package zw.co.payport.gateway.psp.platform.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Delivers outbox events to every handler that supports them.
 *
 * Guarantees: at-least-once per handler; a failing handler is retried with
 * exponential backoff without re-running handlers that already succeeded;
 * after {@code maxAttempts} the event is parked as DEAD and shows up on the
 * ops dashboard (GET /api/v1/psp/admin/events/dead).
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final List<String> CLAIMED = List.of("NEW", "RETRY");

    private final OutboxEventRepository events;
    private final OutboxDeliveryRepository deliveries;
    private final List<PspEventHandler> handlers;
    private final ExternalEventPublisher external;
    private final TransactionTemplate claimTx;
    private final TransactionTemplate handlerTx;
    private final String node;

    @Value("${payport.psp.outbox.batch-size:100}")
    private int batchSize = 100;

    @Value("${payport.psp.outbox.max-attempts:15}")
    private int maxAttempts = 15;

    public OutboxDispatcher(OutboxEventRepository events, OutboxDeliveryRepository deliveries,
                            List<PspEventHandler> handlers, Optional<ExternalEventPublisher> external,
                            PlatformTransactionManager txManager) {
        this.events = events;
        this.deliveries = deliveries;
        this.handlers = handlers;
        this.external = external.orElse(ExternalEventPublisher.NO_OP);
        this.claimTx = new TransactionTemplate(txManager);
        this.handlerTx = new TransactionTemplate(txManager);
        this.handlerTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.node = nodeName();
    }

    @Scheduled(fixedDelayString = "${payport.psp.outbox.poll-ms:2000}", initialDelay = 15_000)
    public void dispatch() {
        LocalDateTime now = LocalDateTime.now();
        Integer claimed = claimTx.execute(s -> events.claimBatch(node, now, now.plusMinutes(5), batchSize));
        if (claimed == null || claimed == 0) return;

        for (OutboxEvent ev : events.findByLockedByAndStatusInOrderByIdAsc(node, CLAIMED)) {
            deliver(ev);
        }
    }

    private void deliver(OutboxEvent ev) {
        PspEvent event = new PspEvent(ev.getEventId(), ev.getEventType(), ev.getAggregateType(), ev.getAggregateId(), ev.getPayload());
        String firstError = null;

        for (PspEventHandler h : handlers) {
            if (!h.supports(ev.getEventType())) continue;
            if (deliveries.existsByEventIdAndHandler(ev.getEventId(), h.name())) continue;
            try {
                handlerTx.executeWithoutResult(status -> {
                    try {
                        h.handle(event);
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    OutboxDelivery d = new OutboxDelivery();
                    d.setEventId(ev.getEventId());
                    d.setHandler(h.name());
                    deliveries.save(d);
                });
            } catch (Exception e) {
                log.warn("[OUTBOX] Handler {} failed on {} ({}): {}", h.name(), ev.getEventId(), ev.getEventType(), e.getMessage());
                if (firstError == null) firstError = h.name() + ": " + e.getMessage();
            }
        }

        if (firstError == null) {
            try {
                external.publish(event);
            } catch (Exception e) {
                firstError = "external: " + e.getMessage();
            }
        }

        final String error = firstError;
        claimTx.executeWithoutResult(s -> {
            OutboxEvent fresh = events.findById(ev.getId()).orElseThrow();
            fresh.setLockedBy(null);
            fresh.setLockedUntil(null);
            if (error == null) {
                fresh.setStatus("PUBLISHED");
                fresh.setPublishedAt(LocalDateTime.now());
                fresh.setLastError(null);
            } else {
                int attempts = fresh.getAttempts() + 1;
                fresh.setAttempts(attempts);
                fresh.setLastError(error.length() > 2000 ? error.substring(0, 2000) : error);
                if (attempts >= maxAttempts) {
                    fresh.setStatus("DEAD");
                    log.error("[OUTBOX] Event {} ({}) is DEAD after {} attempts: {}", fresh.getEventId(), fresh.getEventType(), attempts, error);
                } else {
                    fresh.setStatus("RETRY");
                    long backoffSeconds = Math.min(3600, (long) Math.pow(2, attempts) * 5);
                    fresh.setNextAttemptAt(LocalDateTime.now().plusSeconds(backoffSeconds));
                }
            }
            events.save(fresh);
        });
    }

    /** Ops: put a DEAD event back in the queue after fixing the cause. */
    public void requeue(Long id) {
        claimTx.executeWithoutResult(s -> {
            OutboxEvent ev = events.findById(id).orElseThrow();
            ev.setStatus("RETRY");
            ev.setAttempts(0);
            ev.setNextAttemptAt(LocalDateTime.now());
            events.save(ev);
        });
    }

    private static String nodeName() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID().toString().substring(0, 8);
        } catch (Exception e) {
            return "node-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }
}
