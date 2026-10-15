package zw.co.payport.gateway.psp.recon;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.psp.common.BreakReporter;

import java.util.List;

/**
 * Feeds the gateway's existing provider-statement reconciliation (EcoCash,
 * OneMoney, Omari, InnBucks, NetOne, Telecel parsers) into the PSP breaks
 * queue, so every discrepancy lands in one place with one resolution
 * workflow. Reporting is idempotent, so restarting from zero is harmless.
 */
@Component
public class StatementBreakFeeder {

    private final ReconBreakRepository repo;
    private final BreakReporter breaks;
    private volatile long watermark = 0;

    public StatementBreakFeeder(ReconBreakRepository repo, BreakReporter breaks) {
        this.repo = repo;
        this.breaks = breaks;
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    public void feed() {
        List<Object[]> rows;
        do {
            rows = repo.statementMismatchesAfter(watermark);
            for (Object[] r : rows) {
                long id = ((Number) r[0]).longValue();
                String provider = String.valueOf(r[1]);
                String type = String.valueOf(r[2]);
                String txRef = r[3] == null ? null : String.valueOf(r[3]);
                String provRef = r[4] == null ? null : String.valueOf(r[4]);
                Long provAmt = r[5] == null ? null : ((Number) r[5]).longValue();
                Long ppAmt = r[6] == null ? null : ((Number) r[6]).longValue();
                String reference = txRef != null ? txRef : provider + ":" + (provRef != null ? provRef : "match" + id);
                breaks.report("PROVIDER_STATEMENT", "STATEMENT_" + type, reference, null, ppAmt, provAmt,
                        provider + " statement " + type.replace('_', ' ').toLowerCase() + " (match #" + id + ", provider ref " + provRef
                                + ", at " + r[7] + ")");
                watermark = Math.max(watermark, id);
            }
        } while (rows.size() == 1000);
    }
}
