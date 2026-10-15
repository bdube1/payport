package zw.co.payport.gateway.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.ledger.shadow.ShadowLedgerSweeper;
import zw.co.payport.gateway.platform.JobRunner;

import java.util.List;
import java.util.Optional;

/**
 * Scheduled ledger work. Runs on every instance; the lease in JobRunner makes sure only one
 * instance does the work at a time, and every run is recorded in platform_job_runs.
 */
@Component
public class LedgerJobs {

    private static final Logger log = LoggerFactory.getLogger(LedgerJobs.class);

    public static final String SHADOW_JOB = "ledger-shadow-sweep";
    public static final String INTEGRITY_JOB = "ledger-seal-and-integrity";

    private final JobRunner jobs;
    private final ShadowLedgerSweeper sweeper;
    private final LedgerClient ledger;

    @Value("${payport.ledger.shadow.enabled:false}")
    private boolean shadowEnabled;

    public LedgerJobs(JobRunner jobs, ShadowLedgerSweeper sweeper, LedgerClient ledger) {
        this.jobs = jobs;
        this.sweeper = sweeper;
        this.ledger = ledger;
    }

    /** Observe-only shadow posting (ADR-0009). Off unless payport.ledger.shadow.enabled=true. */
    @Scheduled(fixedDelayString = "${payport.ledger.shadow.interval-ms:60000}", initialDelayString = "${payport.ledger.shadow.initial-delay-ms:60000}")
    public void shadowSweep() {
        if (!shadowEnabled) return;
        try {
            JobRunner.RunRecord r = jobs.runExclusive(SHADOW_JOB, 900, () -> sweeper.runOnce().toString());
            if (r.outcome() == JobRunner.Outcome.FAILED) log.error("[LEDGER] shadow sweep failed: {}", r.summary());
        } catch (RuntimeException e) {
            log.error("[LEDGER] shadow sweep crashed (payments unaffected): {}", e.toString());
        }
    }

    /** Seals settled entries, then runs every integrity check. Any violation is logged at ERROR for alerting. */
    @Scheduled(cron = "${payport.ledger.integrity-cron:0 15 2 * * *}")
    public void sealAndCheck() {
        jobs.runExclusive(INTEGRITY_JOB, 3_600, this::sealAndCheckNow);
    }

    public String sealAndCheckNow() {
        Optional<LedgerClient.Seal> seal = ledger.seal("SYSTEM:ledger-jobs", 120, 50_000);
        List<LedgerClient.Violation> violations = ledger.integrityReport();
        for (LedgerClient.Violation v : violations) {
            log.error("[LEDGER-INTEGRITY] {} {} {}", v.checkCode(), v.subject(), v.detail());
        }
        return "sealed " + seal.map(s -> s.entryCount() + " entries (seal " + s.sealId() + ")").orElse("nothing new")
                + "; integrity violations: " + violations.size();
    }
}
