package zw.co.payport.gateway.platform;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs a named job on at most one application instance at a time, using a lease row in
 * the database, and records every run (platform_job_runs). Safe to call from @Scheduled
 * methods on every instance: only the lease holder does the work.
 *
 * The lease expires on its own (leaseSeconds), so a crashed instance cannot block a job
 * forever. Choose leaseSeconds comfortably above the job's longest expected run.
 */
public class JobRunner {

    private static final Logger log = Logger.getLogger(JobRunner.class.getName());

    public enum Outcome { SUCCEEDED, FAILED, SKIPPED_LEASE_HELD }

    public record RunRecord(Outcome outcome, Long runId, String summary) {}

    private final DataSource dataSource;
    private final String owner;

    public JobRunner(DataSource dataSource) {
        this(dataSource, defaultOwner());
    }

    public JobRunner(DataSource dataSource, String owner) {
        this.dataSource = dataSource;
        this.owner = owner;
    }

    public String owner() {
        return owner;
    }

    public RunRecord runExclusive(String jobName, int leaseSeconds, Supplier<String> work) {
        if (!acquire(jobName, leaseSeconds)) {
            return new RunRecord(Outcome.SKIPPED_LEASE_HELD, null, "lease held by another instance");
        }
        Long runId = null;
        try {
            runId = startRun(jobName);
            String summary = work.get();
            finishRun(runId, "SUCCEEDED", summary, null);
            return new RunRecord(Outcome.SUCCEEDED, runId, summary);
        } catch (RuntimeException e) {
            log.log(Level.SEVERE, "[JOB] " + jobName + " failed", e);
            if (runId != null) finishRun(runId, "FAILED", null, String.valueOf(e));
            return new RunRecord(Outcome.FAILED, runId, String.valueOf(e));
        } finally {
            release(jobName);
        }
    }

    /** Acquires the lease if it is free, expired, or already ours. Uses the database clock only. */
    boolean acquire(String jobName, int leaseSeconds) {
        try (Connection c = dataSource.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT IGNORE INTO platform_job_leases (job_name, owner, lease_until) VALUES (?, '', '1970-01-01 00:00:01')")) {
                    ins.setString(1, jobName);
                    ins.executeUpdate();
                }
                boolean free;
                try (PreparedStatement sel = c.prepareStatement(
                        "SELECT owner, lease_until < NOW(6) FROM platform_job_leases WHERE job_name = ? FOR UPDATE")) {
                    sel.setString(1, jobName);
                    try (ResultSet rs = sel.executeQuery()) {
                        rs.next();
                        free = rs.getBoolean(2) || owner.equals(rs.getString(1));
                    }
                }
                if (free) {
                    try (PreparedStatement up = c.prepareStatement(
                            "UPDATE platform_job_leases SET owner = ?, lease_until = NOW(6) + INTERVAL ? SECOND, acquired_at = NOW(6) WHERE job_name = ?")) {
                        up.setString(1, owner);
                        up.setInt(2, leaseSeconds);
                        up.setString(3, jobName);
                        up.executeUpdate();
                    }
                }
                c.commit();
                return free;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "[JOB] could not acquire lease for " + jobName, e);
            return false;
        }
    }

    void release(String jobName) {
        update("UPDATE platform_job_leases SET lease_until = NOW(6) WHERE job_name = ? AND owner = ?", jobName, owner);
    }

    public Optional<String> lastRunSummary(String jobName) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT CONCAT(status, ' at ', IFNULL(finished_at, started_at), ': ', IFNULL(summary, IFNULL(error, ''))) "
                        + "FROM platform_job_runs WHERE job_name = ? ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, jobName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    private long startRun(String jobName) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO platform_job_runs (job_name, owner, started_at, status) VALUES (?, ?, NOW(6), 'RUNNING')",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, jobName);
            ps.setString(2, owner);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not record job start for " + jobName, e);
        }
    }

    private void finishRun(long runId, String status, String summary, String error) {
        update("UPDATE platform_job_runs SET finished_at = NOW(6), status = ?, summary = ?, error = ? WHERE id = ?",
                status, cut(summary), cut(error), runId);
    }

    private void update(String sql, Object... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "[JOB] bookkeeping update failed", e);
        }
    }

    private static String cut(String s) {
        return s == null ? null : (s.length() > 2000 ? s.substring(0, 2000) : s);
    }

    static String defaultOwner() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }
        String pid = ManagementFactory.getRuntimeMXBean().getName();
        String id = host + "/" + pid + "/" + UUID.randomUUID().toString().substring(0, 8);
        return id.length() > 120 ? id.substring(0, 120) : id;
    }
}
