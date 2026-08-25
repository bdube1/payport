package zw.co.payport.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fulfilment Queue Service
 *
 * Handles async job queuing for service delivery.
 * In Phase 1: uses Spring async + scheduled executor (simple, no extra infra).
 * In Phase 2+: replace with proper job queue (Redis/BullMQ or Quartz).
 *
 * Publishes FulfilmentJobEvent events which are consumed by
 * the FulfilmentJobListener (also in this file).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FulfilmentQueueService {

    private final ApplicationEventPublisher eventPublisher;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);

    /** Enqueue a fulfilment job immediately */
    public void enqueue(String reference) {
        log.info("[QUEUE] Enqueueing fulfilment job for: {}", reference);
        // Add a small delay to ensure transaction status is updated
        scheduler.schedule(() -> {
            log.info("[QUEUE] Processing enqueued job for: {}", reference);
            eventPublisher.publishEvent(new FulfilmentJobEvent(reference, false));
        }, 500, TimeUnit.MILLISECONDS);
    }

    /** Enqueue with high priority (manual ops retry) */
    public void enqueuePriority(String reference) {
        log.info("[QUEUE] Enqueueing PRIORITY fulfilment job for: {}", reference);
        // Add a small delay to ensure transaction status is updated
        scheduler.schedule(() -> {
            log.info("[QUEUE] Processing priority job for: {}", reference);
            eventPublisher.publishEvent(new FulfilmentJobEvent(reference, true));
        }, 100, TimeUnit.MILLISECONDS);
    }

    /** Enqueue with a delay (for retry backoff) */
    public void enqueueWithDelay(String reference, int delaySeconds) {
        log.info("[QUEUE] Scheduling fulfilment retry for {} in {}s", reference, delaySeconds);
        scheduler.schedule(() -> {
            log.info("[QUEUE] Retry delay elapsed, enqueuing: {}", reference);
            eventPublisher.publishEvent(new FulfilmentJobEvent(reference, false));
        }, delaySeconds, TimeUnit.SECONDS);
    }

    // ── Event classes ──────────────────────────────────────────────────────────

    public record FulfilmentJobEvent(String reference, boolean priority) {}
}