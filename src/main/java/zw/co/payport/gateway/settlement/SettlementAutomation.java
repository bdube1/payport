package zw.co.payport.gateway.settlement;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.platform.JobRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Automatic settlement. OFF by default (payport.settlement.automation.enabled=false): switching
 * from manual to automatic is a configuration change, no code change.
 *
 * Each run (cron, one instance at a time via the job lease), per currency:
 *  1. drafts a batch from the eligible funds (as SYSTEM:settlement-automation);
 *  2. approval policy: a batch whose total is within payport.settlement.automation.auto-approve-limit-minor.CCY
 *     (default 0 = never) and that passes the checks (every item has a payout destination and a
 *     positive amount; none of its collections has an open reconciliation exception) gets the
 *     policy's approval (SYSTEM:auto-approval-policy). Batches that need two approvals, or fail a
 *     check, wait for people as before;
 *  3. in LIVE mode only, an APPROVED batch is instructed and handed to the PayoutTransport;
 *  4. bank results the transport has collected are applied (PAID needs the bank reference).
 * In PARALLEL mode steps 1-2 run (for comparison with the legacy settlement); nothing is instructed.
 */
@Slf4j
@Component
public class SettlementAutomation {

    public static final String CREATOR = "SYSTEM:settlement-automation";
    public static final String POLICY_APPROVER = "SYSTEM:auto-approval-policy";

    private final SettlementBatchService batches;
    private final PayoutTransport transport;
    private final JobRunner jobs;
    private final JdbcTemplate jdbc;
    private final Environment env;
    private final boolean enabled;
    private final List<String> currencies;

    public SettlementAutomation(SettlementBatchService batches, PayoutTransport transport, JobRunner jobs, JdbcTemplate jdbc, Environment env,
                                @Value("${payport.settlement.automation.enabled:false}") boolean enabled,
                                @Value("${payport.settlement.automation.currencies:USD,ZWG}") String currencies) {
        this.batches = batches;
        this.transport = transport;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.env = env;
        this.enabled = enabled;
        this.currencies = Arrays.stream(currencies.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(String::toUpperCase).toList();
    }

    public boolean enabled() {
        return enabled;
    }

    @Scheduled(cron = "${payport.settlement.automation.cron:0 30 6 * * *}")
    public void scheduled() {
        if (!enabled) return;
        JobRunner.RunRecord r = jobs.runExclusive("settlement-automation", 3600, () -> runOnce().toString());
        if (r.outcome() == JobRunner.Outcome.FAILED) log.error("[SETTLEMENT-AUTO] run failed: {}", r.summary());
    }

    public record RunResult(List<String> drafted, List<String> policyApproved, List<String> waitingForPeople, List<String> instructed, int resultsApplied) {}

    /** One automation pass. Public for tests and the ops console. */
    public RunResult runOnce() {
        List<String> drafted = new ArrayList<>(), approved = new ArrayList<>(), waiting = new ArrayList<>(), instructed = new ArrayList<>();
        for (String ccy : currencies) {
            SettlementBatchService.Batch b = batches.draft(ccy, CREATOR);
            if (b == null) continue;
            drafted.add(b.batchRef());
            String why = policyRefusal(b);
            if (why == null) {
                b = batches.approve(b.id(), POLICY_APPROVER);
                if ("APPROVED".equals(b.status())) approved.add(b.batchRef());
                else waiting.add(b.batchRef() + " (needs " + b.approvalsRequired() + " approvals)");
            } else {
                waiting.add(b.batchRef() + " (" + why + ")");
            }
        }
        // Instruct every approved batch (including ones people approved since the last run).
        if (batches.mode() == SettlementBatchService.Mode.LIVE) {
            for (Long id : jdbc.queryForList("SELECT id FROM stl_batches WHERE status = 'APPROVED' AND mode = 'LIVE' ORDER BY id", Long.class)) {
                SettlementBatchService.PayoutFile f = batches.instruct(id, CREATOR);
                transport.send(f);
                instructed.add(f.batchRef());
            }
        }
        int applied = 0;
        for (PayoutTransport.BankResultFile rf : transport.fetchResults()) {
            batches.applyPayoutResults(rf.results(), rf.name(), rf.sha256(), CREATOR);
            applied += rf.results().size();
        }
        RunResult r = new RunResult(drafted, approved, waiting, instructed, applied);
        log.info("[SETTLEMENT-AUTO] {}", r);
        return r;
    }

    /** Why the policy will not approve this batch, or null if it may. */
    String policyRefusal(SettlementBatchService.Batch b) {
        long limit = env.getProperty("payport.settlement.automation.auto-approve-limit-minor." + b.currency(), Long.class,
                env.getProperty("payport.settlement.automation.auto-approve-limit-minor", Long.class, 0L));
        if (limit <= 0) return "automatic approval not enabled";
        if (b.totalNetMinor() > limit) return "total above the automatic approval limit";
        Integer noDestination = jdbc.queryForObject("SELECT COUNT(*) FROM stl_items WHERE batch_id = ? AND (destination IS NULL "
                + "OR TRIM(REPLACE(destination, ':', '')) = '' OR net_minor <= 0)", Integer.class, b.id());
        if (noDestination != null && noDestination > 0) return "an item has no payout destination or no amount";
        Integer openExceptions = jdbc.queryForObject("SELECT COUNT(*) FROM stl_items i JOIN stl_item_sessions s ON s.item_id = i.id "
                + "JOIN checkout_sessions cs ON cs.id = s.session_id JOIN recon_exceptions e ON e.transaction_reference = cs.transaction_reference "
                + "WHERE i.batch_id = ? AND e.status = 'OPEN'", Integer.class, b.id());
        if (openExceptions != null && openExceptions > 0) return "collections with open reconciliation exceptions";
        return null;
    }
}
