package zw.co.payport.gateway.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.service.FulfilmentQueueService;
import zw.co.payport.gateway.service.FulfilmentQueueService.JobStatus;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService.FulfilmentOutcome;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Runs durable fulfilment jobs (fulfilment_jobs). Safe on every instance: each job is
 * claimed atomically under a lease.
 *
 *  - QUEUED jobs are delivered once per claim.
 *  - A delivery with an unknown outcome is never repeated: the job becomes UNKNOWN and is
 *    resolved by the provider's delivery query (every 5 minutes); with no definite answer
 *    after the resolution window it becomes MANUAL for a person to check.
 *  - A RUNNING job whose lease expired (instance died mid-delivery) becomes UNKNOWN.
 */
@Slf4j
@Component
public class FulfilmentWorker {

    static final Duration LEASE = Duration.ofMinutes(5);
    static final Duration UNKNOWN_RECHECK = Duration.ofMinutes(5);
    static final Duration PREPARE_RETRY = Duration.ofMinutes(1);
    static final int BATCH = 20;

    private final FulfilmentQueueService queue;
    private final TransactionOrchestratorService orchestrator;
    private final boolean enabled;
    private final Duration manualAfter;
    private final String owner = hostName() + ":" + UUID.randomUUID().toString().substring(0, 8);

    public FulfilmentWorker(FulfilmentQueueService queue, TransactionOrchestratorService orchestrator,
                            @Value("${payport.fulfilment.worker.enabled:true}") boolean enabled,
                            @Value("${payport.fulfilment.unknown-manual-after-minutes:120}") long manualAfterMinutes) {
        this.queue = queue;
        this.orchestrator = orchestrator;
        this.enabled = enabled;
        this.manualAfter = Duration.ofMinutes(manualAfterMinutes);
    }

    public record Summary(int expired, int delivered, int failed, int unknown, int resolved, int manual) {}

    @Scheduled(fixedDelay = 2_000, initialDelay = 5_000)
    public void tick() {
        if (!enabled) return;
        try {
            runOnce(LocalDateTime.now());
        } catch (Exception e) {
            log.error("[FULFILMENT_WORKER] run failed: {}", e.getMessage(), e);
        }
    }

    public Summary runOnce(LocalDateTime now) {
        int backfilled = queue.backfillConfirmed(now.minusHours(24), now);
        if (backfilled > 0) log.warn("[FULFILMENT_WORKER] Queued {} confirmed payment(s) that had no delivery job", backfilled);
        int expired = queue.expireLeases(now);
        if (expired > 0) log.error("[FULFILMENT_WORKER] {} delivery job(s) were interrupted mid-delivery; outcome unknown, resolving by query", expired);

        int delivered = 0, failed = 0, unknown = 0, resolved = 0, manual = 0;
        for (String ref : queue.dueQueued(now, BATCH)) {
            if (!queue.claim(ref, owner, now, now.plus(LEASE))) continue; // another instance has it
            FulfilmentOutcome o;
            try {
                o = orchestrator.executeFulfilment(ref);
            } catch (Exception e) {
                // Raised before the provider was called (executeFulfilment records post-call problems as UNKNOWN).
                log.error("[FULFILMENT_WORKER] {} could not start delivery: {} -- retrying in {}", ref, e.getMessage(), PREPARE_RETRY);
                queue.finish(ref, owner, JobStatus.QUEUED, "not started: " + e.getMessage(), LocalDateTime.now().plus(PREPARE_RETRY), LocalDateTime.now());
                continue;
            }
            LocalDateTime t = LocalDateTime.now();
            switch (o.kind()) {
                case DELIVERED, SKIPPED -> { delivered++; queue.finish(ref, owner, JobStatus.DONE, o.kind() + ": " + o.detail(), null, t); }
                case FAILED -> {
                    failed++;
                    if (o.retryAt() != null) queue.finish(ref, owner, JobStatus.QUEUED, "failed, retry scheduled: " + o.detail(), o.retryAt(), t);
                    else queue.finish(ref, owner, JobStatus.FAILED, o.detail(), null, t);
                }
                case UNKNOWN -> { unknown++; queue.finish(ref, owner, JobStatus.UNKNOWN, o.detail(), t.plus(UNKNOWN_RECHECK), t); }
                case NOT_STARTED -> queue.finish(ref, owner, JobStatus.QUEUED, o.detail(), t.plus(PREPARE_RETRY), t);
            }
        }

        for (String ref : queue.dueUnknown(now, BATCH)) {
            FulfilmentOutcome o = orchestrator.resolveUnknownFulfilment(ref);
            LocalDateTime t = LocalDateTime.now();
            switch (o.kind()) {
                case DELIVERED, SKIPPED -> { resolved++; queue.updateUnknown(ref, JobStatus.DONE, "resolved: " + o.kind() + ": " + o.detail(), null, t); }
                case NOT_STARTED -> queue.updateUnknown(ref, JobStatus.QUEUED, "interrupted before sending; queued again", t, t);
                case FAILED -> {
                    resolved++;
                    if (o.retryAt() != null) queue.updateUnknown(ref, JobStatus.QUEUED, "provider confirmed not delivered; retry scheduled", o.retryAt(), t);
                    else queue.updateUnknown(ref, JobStatus.FAILED, "provider confirmed not delivered: " + o.detail(), null, t);
                }
                case UNKNOWN -> {
                    var job = queue.find(ref).orElse(null);
                    LocalDateTime since = job != null && job.unknownSince() != null ? job.unknownSince() : t;
                    if (Duration.between(since, now).compareTo(manualAfter) >= 0) {
                        manual++;
                        queue.updateUnknown(ref, JobStatus.MANUAL, "no definite answer since " + since + ": " + o.detail(), null, t);
                        log.error("[FULFILMENT_WORKER] {} delivery outcome still unknown after {} -- needs a person to check with the provider (NOT re-delivered)",
                                ref, manualAfter);
                    } else {
                        queue.updateUnknown(ref, JobStatus.UNKNOWN, o.detail(), now.plus(UNKNOWN_RECHECK), t);
                    }
                }
            }
        }
        return new Summary(expired, delivered, failed, unknown, resolved, manual);
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "instance";
        }
    }
}
