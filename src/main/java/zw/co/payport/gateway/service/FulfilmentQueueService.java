package zw.co.payport.gateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Durable fulfilment queue (fulfilment_jobs, V38). Replaces the in-memory executor, which
 * lost queued deliveries on restart (R12) and could run a job before the confirming
 * transaction had committed.
 *
 * enqueue* writes in the CALLER's transaction: the job commits together with the payment
 * confirmation (or not at all). FulfilmentWorker claims due jobs under a lease.
 */
@Slf4j
@Service
public class FulfilmentQueueService {

    public enum JobStatus { QUEUED, RUNNING, UNKNOWN, DONE, FAILED, MANUAL }

    public record Job(String reference, JobStatus status, int attempts, LocalDateTime nextRunAt, String leaseOwner,
                      LocalDateTime leaseUntil, LocalDateTime unknownSince, String lastResult) {}

    private final JdbcTemplate jdbc;

    public FulfilmentQueueService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Queue a delivery now. */
    public void enqueue(String reference) {
        enqueueAt(reference, LocalDateTime.now());
    }

    /** Ops retry: same as enqueue (the worker runs due jobs oldest first). */
    public void enqueuePriority(String reference) {
        enqueueAt(reference, LocalDateTime.now());
    }

    /** Queue a delivery after a delay (definite-failure retry back-off). */
    public void enqueueWithDelay(String reference, int delaySeconds) {
        enqueueAt(reference, LocalDateTime.now().plusSeconds(delaySeconds));
    }

    /**
     * Never re-queues a job that is RUNNING, UNKNOWN or MANUAL: a delivery that may have
     * happened is resolved by query or by a person, not repeated.
     */
    private void enqueueAt(String reference, LocalDateTime at) {
        LocalDateTime now = LocalDateTime.now();
        int n = jdbc.update("INSERT INTO fulfilment_jobs (transaction_reference, status, next_run_at, created_at, updated_at) "
                        + "VALUES (?, 'QUEUED', ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE "
                        + "next_run_at = IF(status IN ('RUNNING','UNKNOWN','MANUAL'), next_run_at, VALUES(next_run_at)), "
                        + "updated_at = VALUES(updated_at), "
                        + "status = IF(status IN ('RUNNING','UNKNOWN','MANUAL'), status, 'QUEUED')",
                reference, at, now, now);
        log.info("[QUEUE] Fulfilment job for {} queued for {} ({} row(s))", reference, at, n);
    }

    // ─── used by FulfilmentWorker ──────────────────────────────────────────────

    /**
     * Queues confirmed payments that have no job (confirmed before this release, when the
     * in-memory queue could lose them). Only payments confirmed after {@code since}: older
     * ones are left for ops to review rather than delivered unexpectedly long after payment.
     */
    public int backfillConfirmed(LocalDateTime since, LocalDateTime now) {
        // Plain consistent read first, then single-row inserts: an INSERT ... SELECT would take
        // shared locks on transactions rows and deadlock with workers on other instances.
        List<String> refs = jdbc.queryForList("SELECT t.reference FROM transactions t "
                + "LEFT JOIN fulfilment_jobs j ON j.transaction_reference = t.reference "
                + "WHERE t.status = 'PAYMENT_CONFIRMED' AND j.transaction_reference IS NULL AND t.payment_confirmed_at >= ? "
                + "LIMIT 500", String.class, since);
        int n = 0;
        for (String ref : refs) {
            n += jdbc.update("INSERT IGNORE INTO fulfilment_jobs (transaction_reference, status, next_run_at, created_at, updated_at) "
                    + "VALUES (?, 'QUEUED', ?, ?, ?)", ref, now, now, now);
        }
        return n;
    }

    public List<String> dueQueued(LocalDateTime now, int limit) {
        return jdbc.queryForList("SELECT transaction_reference FROM fulfilment_jobs WHERE status = 'QUEUED' AND next_run_at <= ? "
                + "ORDER BY next_run_at LIMIT ?", String.class, now, limit);
    }

    public List<String> dueUnknown(LocalDateTime now, int limit) {
        return jdbc.queryForList("SELECT transaction_reference FROM fulfilment_jobs WHERE status = 'UNKNOWN' AND next_run_at <= ? "
                + "ORDER BY next_run_at LIMIT ?", String.class, now, limit);
    }

    /** Atomically claims a QUEUED job; true only for the one instance/thread that got it. */
    public boolean claim(String reference, String owner, LocalDateTime now, LocalDateTime leaseUntil) {
        return jdbc.update("UPDATE fulfilment_jobs SET status = 'RUNNING', lease_owner = ?, lease_until = ?, attempts = attempts + 1, "
                + "updated_at = ? WHERE transaction_reference = ? AND status = 'QUEUED' AND next_run_at <= ?",
                owner, leaseUntil, now, reference, now) == 1;
    }

    /**
     * Jobs whose lease expired while RUNNING: the instance died during delivery, so the
     * delivery may have happened. They become UNKNOWN (resolved by query), never re-run.
     */
    public int expireLeases(LocalDateTime now) {
        return jdbc.update("UPDATE fulfilment_jobs SET status = 'UNKNOWN', unknown_since = COALESCE(unknown_since, ?), next_run_at = ?, "
                + "last_result = 'lease expired during delivery (instance stopped?)', lease_owner = NULL, lease_until = NULL, updated_at = ? "
                + "WHERE status = 'RUNNING' AND lease_until < ?", now, now, now, now);
    }

    /** Finishes a RUNNING job held by owner. Does nothing if the job was re-queued meanwhile (e.g. a scheduled retry). */
    public void finish(String reference, String owner, JobStatus status, String result, LocalDateTime nextRunAt, LocalDateTime now) {
        jdbc.update("UPDATE fulfilment_jobs SET status = ?, last_result = ?, next_run_at = COALESCE(?, next_run_at), "
                        + "unknown_since = IF(? = 'UNKNOWN', COALESCE(unknown_since, ?), unknown_since), "
                        + "lease_owner = NULL, lease_until = NULL, updated_at = ? "
                        + "WHERE transaction_reference = ? AND status = 'RUNNING' AND lease_owner = ?",
                status.name(), truncate(result), nextRunAt, status.name(), now, now, reference, owner);
    }

    /** Updates an UNKNOWN job after a status query. */
    public void updateUnknown(String reference, JobStatus status, String result, LocalDateTime nextRunAt, LocalDateTime now) {
        jdbc.update("UPDATE fulfilment_jobs SET status = ?, last_result = ?, next_run_at = COALESCE(?, next_run_at), updated_at = ? "
                + "WHERE transaction_reference = ? AND status = 'UNKNOWN'", status.name(), truncate(result), nextRunAt, now, reference);
    }

    public Optional<Job> find(String reference) {
        return jdbc.query("SELECT * FROM fulfilment_jobs WHERE transaction_reference = ?", (rs, i) -> new Job(
                rs.getString("transaction_reference"), JobStatus.valueOf(rs.getString("status")), rs.getInt("attempts"),
                rs.getObject("next_run_at", LocalDateTime.class), rs.getString("lease_owner"),
                rs.getObject("lease_until", LocalDateTime.class), rs.getObject("unknown_since", LocalDateTime.class),
                rs.getString("last_result")), reference).stream().findFirst();
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
