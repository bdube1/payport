package zw.co.payport.gateway.psp.recon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.BreakReporter;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.common.Refs;
import zw.co.payport.gateway.psp.ledger.LedgerService;
import zw.co.payport.gateway.psp.ledger.domain.AccountCategory;
import zw.co.payport.gateway.psp.ledger.domain.AccountKey;
import zw.co.payport.gateway.psp.ledger.domain.ChainHasher;
import zw.co.payport.gateway.psp.ledger.domain.PostingRules;
import zw.co.payport.gateway.psp.ledger.LedgerAccount;
import zw.co.payport.gateway.psp.ledger.LedgerAccountRepository;
import zw.co.payport.gateway.psp.platform.audit.AuditService;
import zw.co.payport.gateway.psp.risk.SanctionsListSources;
import zw.co.payport.gateway.psp.settlement.SettlementInstruction;
import zw.co.payport.gateway.psp.settlement.SettlementInstructionRepository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trust / settlement bank account reconciliation: PayPort's ledger vs the
 * bank's statement. It proposes matches; it never changes balances by itself.
 *
 *  Debits  -> matched to PAID settlement instructions (reference + amount);
 *             SENT ones are SUGGESTED so finance can confirm them as paid with the line as proof.
 *  Credits -> SUGGESTED as rail settlements (EcoCash, OneMoney, ZimSwitch...) when the
 *             narrative names the rail; finance records the gross / fees, which posts
 *             PROVIDER_SETTLEMENT_RECEIVED and clears the rail receivable.
 *  Every day: lines unmatched for 3+ days, cash-balance mismatches between ledger
 *             and statement, and rail receivables not settled for 5+ days become breaks.
 */
@Service
public class BankReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(BankReconciliationService.class);
    private static final Pattern INSTRUCTION = Pattern.compile("(STI_[0-9A-Z]{26})", Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> RAIL_HINTS = Map.of("ECOCASH", "ECOCASH", "ECONET", "ECOCASH", "ONEMONEY", "ONEMONEY",
            "NETONE", "ONEMONEY", "ZIMSWITCH", "ZIMSWITCH", "TELECASH", "TELECASH", "OMARI", "OMARI", "INNBUCKS", "INNBUCKS");

    public record ImportResult(String importRef, int lines, int duplicates, int matched, int suggested, Long closingBalanceMinor) {}

    private final BankStatementLineRepository lines;
    private final SettlementInstructionRepository instructions;
    private final LedgerService ledger;
    private final LedgerAccountRepository accounts;
    private final zw.co.payport.gateway.psp.ledger.JournalLineRepository journalLines;
    private final BreakReporter breaks;
    private final AuditService audit;

    public BankReconciliationService(BankStatementLineRepository lines, SettlementInstructionRepository instructions, LedgerService ledger,
                                     LedgerAccountRepository accounts, zw.co.payport.gateway.psp.ledger.JournalLineRepository journalLines,
                                     BreakReporter breaks, AuditService audit) {
        this.journalLines = journalLines;
        this.lines = lines;
        this.instructions = instructions;
        this.ledger = ledger;
        this.accounts = accounts;
        this.breaks = breaks;
        this.audit = audit;
    }

    /**
     * Imports a statement CSV. Expected columns (header row required, names case-insensitive):
     * date, reference, narrative, debit, credit, balance. Dates yyyy-MM-dd or dd/MM/yyyy; amounts in major units.
     */
    @Transactional
    public ImportResult importCsv(String cashAccount, String currency, byte[] csv, Actor actor) throws Exception {
        String acct = cashAccount.toUpperCase();
        String ccy = AccountKey.normaliseCurrency(currency);
        List<List<String>> rows = SanctionsListSources.Csv.parse(new String(csv, StandardCharsets.UTF_8));
        if (rows.isEmpty()) throw PspException.badRequest("EMPTY_STATEMENT", "Statement is empty");
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < rows.get(0).size(); i++) col.put(rows.get(0).get(i).trim().toLowerCase(), i);
        for (String c : List.of("date", "narrative", "debit", "credit")) {
            if (!col.containsKey(c)) throw PspException.badRequest("BAD_STATEMENT", "Missing column '" + c + "'");
        }
        String importRef = Refs.newRef("bst");
        int n = 0, dup = 0, matched = 0, suggested = 0;
        Long closing = null;
        for (List<String> r : rows.subList(1, rows.size())) {
            if (r.size() < col.size() || r.stream().allMatch(String::isBlank)) continue;
            long debit = minor(cell(r, col, "debit")), credit = minor(cell(r, col, "credit"));
            if (debit == 0 && credit == 0) continue;
            LocalDate date = date(cell(r, col, "date"));
            String ref = cell(r, col, "reference");
            String narrative = cell(r, col, "narrative");
            Long balance = col.containsKey("balance") && !cell(r, col, "balance").isBlank() ? minor(cell(r, col, "balance")) : null;
            String direction = debit > 0 ? "DEBIT" : "CREDIT";
            long amount = debit > 0 ? debit : credit;
            String hash = ChainHasher.contentHash(acct, date, ref, narrative, direction, amount, balance);
            if (lines.findByCashAccountAndLineHash(acct, hash).isPresent()) { dup++; continue; }
            BankStatementLine l = new BankStatementLine();
            l.setImportRef(importRef);
            l.setCashAccount(acct);
            l.setCurrency(ccy);
            l.setValueDate(date);
            l.setBankReference(trim(ref, 150));
            l.setNarrative(trim(narrative, 500));
            l.setDirection(direction);
            l.setAmountMinor(amount);
            l.setRunningBalanceMinor(balance);
            l.setLineHash(hash);
            l.setMatchStatus("UNMATCHED");
            autoMatch(l);
            lines.save(l);
            n++;
            if ("MATCHED".equals(l.getMatchStatus())) matched++;
            if ("SUGGESTED".equals(l.getMatchStatus())) suggested++;
            if (balance != null) closing = balance;
        }
        audit.record(actor, "BANK_STATEMENT_IMPORTED", "CASH_ACCOUNT", acct, Map.of("importRef", importRef, "lines", n, "duplicates", dup));
        if (closing != null) checkBalance(acct, ccy, closing, rows.size() > 1 ? lastDate(rows, col) : LocalDate.now());
        return new ImportResult(importRef, n, dup, matched, suggested, closing);
    }

    private void autoMatch(BankStatementLine l) {
        String text = (l.getBankReference() == null ? "" : l.getBankReference()) + " " + (l.getNarrative() == null ? "" : l.getNarrative());
        if ("DEBIT".equals(l.getDirection())) {
            Matcher m = INSTRUCTION.matcher(text);
            if (m.find()) {
                Optional<SettlementInstruction> i = instructions.findByInstructionRef("sti_" + m.group(1).substring(4).toUpperCase());
                if (i.isPresent() && i.get().getAmountMinor() == l.getAmountMinor()) {
                    l.setMatchedType("SETTLEMENT");
                    l.setMatchedRef(i.get().getInstructionRef());
                    l.setMatchStatus("PAID".equals(i.get().getStatus()) ? "MATCHED" : "SUGGESTED");
                    if ("MATCHED".equals(l.getMatchStatus())) { l.setMatchedBy("AUTO"); l.setMatchedAt(LocalDateTime.now()); }
                }
            }
        } else {
            String upper = text.toUpperCase();
            for (Map.Entry<String, String> h : RAIL_HINTS.entrySet()) {
                if (upper.contains(h.getKey())) {
                    l.setMatchedType("PROVIDER_SETTLEMENT");
                    l.setMatchedRef(h.getValue());
                    l.setMatchStatus("SUGGESTED");
                    break;
                }
            }
        }
    }

    /**
     * Finance confirms a credit line as a rail settlement. Posts
     * Dr PLATFORM_CASH (net) + Dr PROVIDER_FEE_EXPENSE (fees) / Cr PROVIDER_RECEIVABLE (gross).
     */
    @Transactional
    public BankStatementLine recordProviderSettlement(Long lineId, String provider, long grossMinor, long feesMinor, Actor actor) {
        BankStatementLine l = lines.findById(lineId).orElseThrow(() -> PspException.notFound("Statement line", lineId));
        if (!"CREDIT".equals(l.getDirection())) throw PspException.badRequest("NOT_A_CREDIT", "Only credits can be rail settlements");
        if ("MATCHED".equals(l.getMatchStatus())) throw PspException.conflict("ALREADY_MATCHED", "Line already matched to " + l.getMatchedRef());
        if (grossMinor - feesMinor != l.getAmountMinor()) {
            throw PspException.badRequest("AMOUNT_MISMATCH", "gross - fees must equal the credit of " + l.getAmountMinor());
        }
        LedgerService.PostedEntry e = ledger.post(PostingRules.providerSettlementReceived("line" + l.getId(), provider.toUpperCase(),
                l.getCurrency(), grossMinor, feesMinor, l.getCashAccount()), actor.id());
        l.setMatchStatus("MATCHED");
        l.setMatchedType("PROVIDER_SETTLEMENT");
        l.setMatchedRef(provider.toUpperCase() + ":" + e.entryRef());
        l.setMatchedBy(actor.id());
        l.setMatchedAt(LocalDateTime.now());
        lines.save(l);
        audit.record(actor, "PROVIDER_SETTLEMENT_RECORDED", "BANK_LINE", String.valueOf(lineId),
                Map.of("provider", provider, "grossMinor", grossMinor, "feesMinor", feesMinor, "entry", e.entryRef()));
        return l;
    }

    /** Finance links a line to something explained outside the matchers (bank charges, transfers between own accounts...). */
    @Transactional
    public BankStatementLine markExplained(Long lineId, String matchedType, String matchedRef, Actor actor) {
        BankStatementLine l = lines.findById(lineId).orElseThrow(() -> PspException.notFound("Statement line", lineId));
        l.setMatchStatus("MATCHED");
        l.setMatchedType(matchedType);
        l.setMatchedRef(matchedRef);
        l.setMatchedBy(actor.id());
        l.setMatchedAt(LocalDateTime.now());
        lines.save(l);
        audit.record(actor, "BANK_LINE_EXPLAINED", "BANK_LINE", String.valueOf(lineId), Map.of("type", matchedType, "ref", String.valueOf(matchedRef)));
        return l;
    }

    private void checkBalance(String cashAccount, String ccy, long statementBalance, LocalDate date) {
        long ledgerBalance = ledger.balanceAsOf(AccountKey.cash(cashAccount, ccy), date.plusDays(1).atStartOfDay());
        if (ledgerBalance != statementBalance) {
            breaks.report("BANK_RECON", "CASH_BALANCE_MISMATCH", cashAccount + ":" + date, ccy, ledgerBalance, statementBalance,
                    "Ledger shows " + ledgerBalance + " in " + cashAccount + " at end of " + date + ", bank shows " + statementBalance
                            + ". Check unmatched lines and unrecorded rail settlements.");
        }
    }

    @Scheduled(cron = "${payport.psp.recon.bank-check-cron:0 0 7 * * *}", zone = "Africa/Harare")
    public void dailyChecks() {
        for (BankStatementLine l : lines.findByMatchStatusAndValueDateBefore("UNMATCHED", LocalDate.now().minusDays(3))) {
            breaks.report("BANK_RECON", "UNMATCHED_BANK_LINE", l.getCashAccount() + ":" + l.getId(), l.getCurrency(),
                    null, l.getAmountMinor(), l.getDirection() + " " + l.getAmountMinor() + " on " + l.getValueDate() + ": " + l.getNarrative());
        }
        for (BankStatementLine l : lines.findByMatchStatusAndValueDateBefore("SUGGESTED", LocalDate.now().minusDays(3))) {
            breaks.report("BANK_RECON", "UNCONFIRMED_BANK_MATCH", l.getCashAccount() + ":" + l.getId(), l.getCurrency(),
                    null, l.getAmountMinor(), "Suggested " + l.getMatchedType() + " " + l.getMatchedRef() + " not confirmed");
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(5);
        for (LedgerAccount a : accounts.findByCategory(AccountCategory.PROVIDER_RECEIVABLE)) {
            // FIFO ageing: what was owed 5 days ago, minus everything the rail has settled (credited) since.
            List<Object[]> then = journalLines.sumsForAccountAsOf(a.getId(), cutoff);
            List<Object[]> now = journalLines.sumsForAccount(a.getId());
            long debitsThen = then.isEmpty() ? 0 : ((Number) then.get(0)[0]).longValue();
            long creditsThen = then.isEmpty() ? 0 : ((Number) then.get(0)[1]).longValue();
            long creditsNow = now.isEmpty() ? 0 : ((Number) now.get(0)[1]).longValue();
            long owedThen = debitsThen - creditsThen;
            long stale = owedThen - (creditsNow - creditsThen);
            if (stale > 0) {
                breaks.report("BANK_RECON", "RAIL_RECEIVABLE_AGED", a.getCode(), a.getCurrency(), 0L, stale,
                        a.getOwnerRef() + " owes " + stale + " collected 5+ days ago that has not reached the trust account");
            }
        }
    }

    public List<BankStatementLine> openLines() {
        List<BankStatementLine> l = new ArrayList<>(lines.findByMatchStatusOrderByValueDateAsc("UNMATCHED"));
        l.addAll(lines.findByMatchStatusOrderByValueDateAsc("SUGGESTED"));
        return l;
    }

    // ── parsing helpers ──────────────────────────────────────────────────

    private static String cell(List<String> r, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return i == null || i >= r.size() ? "" : r.get(i).trim();
    }

    static long minor(String v) {
        if (v == null || v.isBlank()) return 0;
        String s = v.replace(",", "").replace(" ", "").trim();
        boolean neg = s.startsWith("(") && s.endsWith(")");
        s = s.replace("(", "").replace(")", "");
        long m = new BigDecimal(s).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        return neg ? -m : Math.abs(m);
    }

    static LocalDate date(String v) {
        for (String p : List.of("yyyy-MM-dd", "dd/MM/yyyy", "d/M/yyyy", "dd-MM-yyyy", "dd MMM yyyy")) {
            try { return LocalDate.parse(v.trim(), DateTimeFormatter.ofPattern(p, Locale.ENGLISH)); } catch (Exception ignored) { }
        }
        throw PspException.badRequest("BAD_DATE", "Unrecognised date '" + v + "'");
    }

    private static LocalDate lastDate(List<List<String>> rows, Map<String, Integer> col) {
        LocalDate last = null;
        for (List<String> r : rows.subList(1, rows.size())) {
            try { LocalDate d = date(cell(r, col, "date")); if (last == null || d.isAfter(last)) last = d; } catch (Exception ignored) { }
        }
        return last == null ? LocalDate.now() : last;
    }

    private static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
