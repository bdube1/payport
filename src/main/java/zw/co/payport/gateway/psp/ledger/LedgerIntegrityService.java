package zw.co.payport.gateway.psp.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.psp.common.BreakReporter;
import zw.co.payport.gateway.psp.platform.audit.ChainSealService;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Nightly proof that the ledger is internally consistent. Never fixes
 * anything itself: every problem becomes a reconciliation break for a human.
 *
 *  1. every journal entry nets to zero
 *  2. no entry header exists without its lines
 *  3. the balance projection equals SUM(journal lines) for every account
 *  4. protected accounts are not negative
 *  5. the LEDGER and AUDIT hash chains verify
 *  6. the books balance globally: total debits == total credits
 */
@Service
public class LedgerIntegrityService {

    private static final Logger log = LoggerFactory.getLogger(LedgerIntegrityService.class);

    public record IntegrityReport(LocalDateTime ranAt, boolean ok, long accountsChecked, long totalDebits,
                                  long totalCredits, List<String> problems) {}

    private final JournalEntryRepository entries;
    private final JournalLineRepository lines;
    private final LedgerAccountRepository accounts;
    private final AccountBalanceRepository balances;
    private final ChainSealService seals;
    private final BreakReporter breaks;

    private volatile IntegrityReport lastReport;

    public LedgerIntegrityService(JournalEntryRepository entries, JournalLineRepository lines,
                                  LedgerAccountRepository accounts, AccountBalanceRepository balances,
                                  ChainSealService seals, BreakReporter breaks) {
        this.entries = entries;
        this.lines = lines;
        this.accounts = accounts;
        this.balances = balances;
        this.seals = seals;
        this.breaks = breaks;
    }

    @Scheduled(cron = "${payport.psp.ledger.integrity-cron:0 30 1 * * *}", zone = "Africa/Harare")
    public void nightly() {
        IntegrityReport r = run();
        if (!r.ok()) log.error("[LEDGER-INTEGRITY] FAILED: {}", r.problems());
        else log.info("[LEDGER-INTEGRITY] OK: {} accounts, debits=credits={}", r.accountsChecked(), r.totalDebits());
    }

    @Transactional(readOnly = true)
    public IntegrityReport run() {
        List<String> problems = new ArrayList<>();

        for (Long id : entries.findUnbalancedEntryIds()) {
            problems.add("Entry " + id + " is unbalanced");
            breaks.report("LEDGER_INTEGRITY", "UNBALANCED_ENTRY", "entry:" + id, null, null, null, "Journal entry debits != credits");
        }
        for (Long id : entries.findEntriesWithoutLines()) {
            problems.add("Entry " + id + " has fewer than two lines");
            breaks.report("LEDGER_INTEGRITY", "ENTRY_WITHOUT_LINES", "entry:" + id, null, null, null, "Journal header without lines");
        }

        Map<Long, long[]> derived = new HashMap<>();
        long totalD = 0, totalC = 0;
        for (Object[] row : lines.sumsForAllAccounts()) {
            long d = LedgerService.num(row[1]), c = LedgerService.num(row[2]);
            derived.put(LedgerService.num(row[0]), new long[]{d, c});
            totalD += d;
            totalC += c;
        }
        if (totalD != totalC) {
            problems.add("Global debits " + totalD + " != credits " + totalC);
            breaks.report("LEDGER_INTEGRITY", "TRIAL_BALANCE", "global", null, totalD, totalC, "Trial balance does not net to zero");
        }

        long checked = 0;
        for (LedgerAccount a : accounts.findAll()) {
            checked++;
            long[] dc = derived.getOrDefault(a.getId(), new long[2]);
            long truth = a.getAccountType().balanceOf(dc[0], dc[1]);
            AccountBalance b = balances.findByAccountId(a.getId()).orElse(null);
            if (b == null || b.getBalanceMinor() != truth || b.getDebitsMinor() != dc[0] || b.getCreditsMinor() != dc[1]) {
                problems.add("Projection drift on " + a.getCode() + ": projected " + (b == null ? "missing" : b.getBalanceMinor()) + ", journal " + truth);
                breaks.report("LEDGER_INTEGRITY", "PROJECTION_DRIFT", a.getCode(), a.getCurrency(),
                        truth, b == null ? null : b.getBalanceMinor(), "Balance projection differs from journal");
            }
            if (!a.isAllowNegative() && truth < 0) {
                problems.add("Protected account " + a.getCode() + " is negative: " + truth);
                breaks.report("LEDGER_INTEGRITY", "NEGATIVE_PROTECTED_ACCOUNT", a.getCode(), a.getCurrency(),
                        0L, truth, "Protected account below zero");
            }
        }

        for (String chain : seals.chains()) {
            ChainSealService.ChainReport cr = seals.verify(chain);
            if (!cr.intact()) {
                problems.addAll(cr.problems().stream().map(p -> chain + ": " + p).toList());
                breaks.report("LEDGER_INTEGRITY", "HASH_CHAIN_BROKEN", "chain:" + chain, null, null, null,
                        String.join("; ", cr.problems()));
            }
        }

        IntegrityReport report = new IntegrityReport(LocalDateTime.now(), problems.isEmpty(), checked, totalD, totalC, problems);
        lastReport = report;
        return report;
    }

    public IntegrityReport lastReport() {
        return lastReport;
    }
}
