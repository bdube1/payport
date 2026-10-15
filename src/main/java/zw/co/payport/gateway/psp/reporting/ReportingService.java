package zw.co.payport.gateway.psp.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.BreakReporter;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.common.Refs;
import zw.co.payport.gateway.psp.ledger.JournalLineRepository;
import zw.co.payport.gateway.psp.ledger.LedgerAccount;
import zw.co.payport.gateway.psp.ledger.LedgerAccountRepository;
import zw.co.payport.gateway.psp.ledger.LedgerService;
import zw.co.payport.gateway.psp.ledger.domain.AccountCategory;
import zw.co.payport.gateway.psp.ledger.domain.AccountKey;
import zw.co.payport.gateway.psp.ledger.domain.ChainHasher;
import zw.co.payport.gateway.psp.platform.audit.AuditService;
import zw.co.payport.gateway.psp.risk.AmlAlert;
import zw.co.payport.gateway.psp.risk.AmlAlertRepository;
import zw.co.payport.gateway.psp.risk.RiskCaseRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

/**
 * Regulatory and tax returns, generated from the ledger and transaction
 * records (never typed in by hand):
 *
 *   RBZ_NPS_MONTHLY     volumes / values by product, channel and currency; customer, agent and
 *                       merchant counts; failures, fraud and disputes; safeguarding position
 *   SAFEGUARDING_DAILY  customer + merchant + agent funds owed vs trust cash held, per currency
 *   FIU_CTR / FIU_STR   cash-threshold reports and suspicious transaction reports filed in the period
 *   ZIMRA_IMTT          IMTT collected and remitted (TAX_PAYABLE:IMTT movements)
 *   ZIMRA_VAT_ON_FEES   VAT charged on PayPort fees (TAX_PAYABLE:VAT movements)
 *   TRIAL_BALANCE       every ledger account, debits / credits / balance as at period end
 *
 * Field layouts follow a neutral structure; map them onto the exact
 * templates RBZ, the FIU and ZIMRA currently prescribe before submission.
 * A report must be reviewed by a second person before it is marked submitted.
 */
@Service
public class ReportingService {

    public static final Set<String> TYPES = Set.of("RBZ_NPS_MONTHLY", "SAFEGUARDING_DAILY", "FIU_CTR", "FIU_STR",
            "ZIMRA_IMTT", "ZIMRA_VAT_ON_FEES", "TRIAL_BALANCE");

    /** Liabilities that must be fully covered by funds held in trust. */
    static final List<AccountCategory> SAFEGUARDED = List.of(AccountCategory.CUSTOMER_WALLET, AccountCategory.AGENT_FLOAT,
            AccountCategory.AGENT_COMMISSION_PAYABLE, AccountCategory.MERCHANT_PENDING, AccountCategory.MERCHANT_AVAILABLE,
            AccountCategory.MERCHANT_RESERVE, AccountCategory.SETTLEMENT_PAYABLE, AccountCategory.CUSTOMER_FUNDS_IN_TRANSIT,
            AccountCategory.CUSTOMER_REFUND_PAYABLE);

    private final RegulatoryReportRepository reports;
    private final LedgerAccountRepository accounts;
    private final JournalLineRepository lines;
    private final LedgerService ledger;
    private final AmlAlertRepository alerts;
    private final RiskCaseRepository cases;
    private final AuditService audit;
    private final BreakReporter breaks;
    private final ObjectMapper om;

    /** Cash accounts that are trust / safeguarding accounts, e.g. TRUST_CBZ_USD,TRUST_CBZ_ZWG. */
    @Value("${payport.psp.safeguarding.trust-accounts:}")
    private String trustAccounts = "";

    public ReportingService(RegulatoryReportRepository reports, LedgerAccountRepository accounts, JournalLineRepository lines,
                            LedgerService ledger, AmlAlertRepository alerts, RiskCaseRepository cases, AuditService audit,
                            BreakReporter breaks, ObjectMapper om) {
        this.reports = reports;
        this.accounts = accounts;
        this.lines = lines;
        this.ledger = ledger;
        this.alerts = alerts;
        this.cases = cases;
        this.audit = audit;
        this.breaks = breaks;
        this.om = om;
    }

    @Transactional
    public RegulatoryReport generate(String type, LocalDateTime from, LocalDateTime to, Actor actor) {
        if (!TYPES.contains(type)) throw PspException.badRequest("UNKNOWN_REPORT", "Report type must be one of " + TYPES);
        if (!to.isAfter(from)) throw PspException.badRequest("INVALID_PERIOD", "Period end must be after start");
        Map<String, Object> data;
        List<List<Object>> rows;
        switch (type) {
            case "RBZ_NPS_MONTHLY" -> { data = rbzMonthly(from, to); rows = flatten(data); }
            case "SAFEGUARDING_DAILY" -> { data = safeguarding(to); rows = flatten(data); }
            case "FIU_CTR" -> { data = fiu("CTR_THRESHOLD", from, to, false); rows = flatten(data); }
            case "FIU_STR" -> { data = fiu(null, from, to, true); rows = flatten(data); }
            case "ZIMRA_IMTT" -> { data = taxMovements("IMTT", from, to); rows = flatten(data); }
            case "ZIMRA_VAT_ON_FEES" -> { data = taxMovements("VAT", from, to); rows = flatten(data); }
            default -> { data = trialBalance(to); rows = flatten(data); }
        }
        String json;
        try { json = om.writeValueAsString(data); } catch (Exception e) { throw new IllegalStateException(e); }
        RegulatoryReport r = new RegulatoryReport();
        r.setReportRef(Refs.newRef("rpt"));
        r.setReportType(type);
        r.setPeriodStart(from);
        r.setPeriodEnd(to);
        r.setStatus("GENERATED");
        r.setPayloadJson(json);
        r.setCsv(toCsv(rows));
        r.setSha256(ChainHasher.sha256(json));
        r.setGeneratedBy(actor.id());
        reports.save(r);
        audit.record(actor, "REPORT_GENERATED", "REGULATORY_REPORT", r.getReportRef(), Map.of("type", type, "from", from.toString(), "to", to.toString()));
        return r;
    }

    @Transactional
    public RegulatoryReport review(String reportRef, Actor actor) {
        RegulatoryReport r = get(reportRef);
        if (actor.id().equalsIgnoreCase(r.getGeneratedBy())) throw PspException.forbidden("FOUR_EYES_VIOLATION", "Another person must review the report");
        if (!"GENERATED".equals(r.getStatus())) throw PspException.conflict("NOT_GENERATED", "Report is " + r.getStatus());
        r.setStatus("REVIEWED");
        r.setReviewedBy(actor.id());
        r.setReviewedAt(LocalDateTime.now());
        reports.save(r);
        audit.record(actor, "REPORT_REVIEWED", "REGULATORY_REPORT", reportRef, Map.of("sha256", r.getSha256()));
        return r;
    }

    @Transactional
    public RegulatoryReport markSubmitted(String reportRef, String submissionRef, Actor actor) {
        RegulatoryReport r = get(reportRef);
        if (!"REVIEWED".equals(r.getStatus())) throw PspException.conflict("NOT_REVIEWED", "Review the report before submitting it");
        r.setStatus("SUBMITTED");
        r.setSubmittedBy(actor.id());
        r.setSubmittedAt(LocalDateTime.now());
        r.setSubmissionRef(submissionRef);
        reports.save(r);
        audit.record(actor, "REPORT_SUBMITTED", "REGULATORY_REPORT", reportRef, Map.of("submissionRef", String.valueOf(submissionRef)));
        return r;
    }

    public RegulatoryReport get(String ref) {
        return reports.findByReportRef(ref).orElseThrow(() -> PspException.notFound("Report", ref));
    }

    public List<RegulatoryReport> recent() {
        return reports.findTop100ByOrderByCreatedAtDesc();
    }

    // ── Report builders ──────────────────────────────────────────────────

    Map<String, Object> rbzMonthly(LocalDateTime from, LocalDateTime to) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("period", Map.of("from", from.toString(), "to", to.toString()));
        m.put("paymentsByCategoryChannelCurrency", rows(reports.gatewayVolumes(from, to), "category", "channel", "currency", "count", "valueMinor"));
        m.put("paymentsByMethod", rows(reports.methodVolumes(from, to), "method", "currency", "count", "valueMinor"));
        m.put("walletAndAgentActivity", rows(reports.walletVolumes(from, to), "type", "channel", "currency", "count", "valueMinor", "feesMinor"));
        m.put("activeWallets", reports.activeWallets(from, to));
        m.put("customersByKycTier", rows(reports.customersByTier(), "tier", "count"));
        m.put("agentsByStatus", rows(reports.agentsByStatus(), "status", "count"));
        m.put("merchantsByStatus", rows(reports.merchantsByStatus(), "status", "count"));
        m.put("failedOrUnresolvedPayments", rows(reports.failures(from, to), "status", "count"));
        m.put("disputes", rows(reports.disputes(from, to), "status", "count", "valueMinor"));
        m.put("fraudCasesOpened", cases.countByCaseTypeAndCreatedAtBetween("FRAUD", from, to));
        m.put("safeguardingAtPeriodEnd", safeguarding(to));
        return m;
    }

    /** Liabilities vs trust cash per currency, as at {@code asOf}. A shortfall opens a break. */
    Map<String, Object> safeguarding(LocalDateTime asOf) {
        Map<String, Object> out = new LinkedHashMap<>();
        Set<String> trusts = new HashSet<>();
        for (String t : trustAccounts.split(",")) if (!t.isBlank()) trusts.add(t.trim().toUpperCase());
        Map<Long, long[]> totals = accountTotals(asOf);
        Map<Long, LedgerAccount> byId = new HashMap<>();
        for (LedgerAccount a : accounts.findAll()) byId.put(a.getId(), a);
        for (String ccy : List.of("USD", "ZWG")) {
            Map<String, Long> liab = new LinkedHashMap<>();
            for (AccountCategory c : SAFEGUARDED) liab.put(c.name(), 0L);
            Map<String, Long> cash = new LinkedHashMap<>();
            long totalLiab = 0, trustCash = 0, inTransit = 0;
            for (Map.Entry<Long, long[]> e : totals.entrySet()) {
                LedgerAccount a = byId.get(e.getKey());
                if (a == null || !ccy.equals(a.getCurrency())) continue;
                long bal = a.getAccountType().balanceOf(e.getValue()[0], e.getValue()[1]);
                if (SAFEGUARDED.contains(a.getCategory())) {
                    liab.merge(a.getCategory().name(), bal, Long::sum);
                    totalLiab += bal;
                } else if (a.getCategory() == AccountCategory.PLATFORM_CASH && (trusts.isEmpty() || trusts.contains(a.getOwnerRef()))) {
                    cash.merge(a.getOwnerRef(), bal, Long::sum);
                    trustCash += bal;
                } else if (a.getCategory() == AccountCategory.PROVIDER_RECEIVABLE) {
                    inTransit += bal;
                }
            }
            long shortfall = totalLiab - trustCash - inTransit;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("liabilitiesMinor", liab);
            c.put("totalLiabilitiesMinor", totalLiab);
            c.put("trustCashMinor", cash);
            c.put("totalTrustCashMinor", trustCash);
            c.put("receivableFromRailsMinor", inTransit);
            c.put("shortfallMinor", Math.max(0, shortfall));
            c.put("coverageRatio", totalLiab == 0 ? null : (double) (trustCash + inTransit) / totalLiab);
            out.put(ccy, c);
            if (shortfall > 0) {
                breaks.report("SAFEGUARDING", "SAFEGUARDING_SHORTFALL", ccy + ":" + asOf.toLocalDate(), ccy, totalLiab, trustCash + inTransit,
                        "Funds owed to customers/merchants/agents exceed trust cash + rail receivables by " + shortfall);
            }
        }
        out.put("asOf", asOf.toString());
        out.put("trustAccountsConfigured", trusts);
        return out;
    }

    private Map<Long, long[]> accountTotals(LocalDateTime asOf) {
        Map<Long, long[]> m = new HashMap<>();
        for (Object[] r : lines.accountTotalsAsOf(asOf)) {
            m.put(((Number) r[0]).longValue(), new long[]{((Number) r[1]).longValue(), ((Number) r[2]).longValue()});
        }
        return m;
    }

    Map<String, Object> fiu(String alertType, LocalDateTime from, LocalDateTime to, boolean strFiledOnly) {
        List<AmlAlert> list = strFiledOnly ? alerts.findByStatusAndDecidedAtBetween("STR_FILED", from, to)
                : alerts.findByAlertTypeAndCreatedAtBetween(alertType, from, to);
        List<Map<String, Object>> items = new ArrayList<>();
        for (AmlAlert a : list) {
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("alertRef", a.getAlertRef());
            i.put("type", a.getAlertType());
            i.put("subjectType", a.getSubjectType());
            i.put("subjectRef", a.getSubjectRef());
            i.put("subjectName", a.getSubjectName());
            i.put("amountUsdMinor", a.getAmountUsdMinor());
            i.put("details", a.getDetails());
            i.put("status", a.getStatus());
            i.put("reviewNote", a.getReviewNote());
            i.put("createdAt", String.valueOf(a.getCreatedAt()));
            items.add(i);
        }
        return Map.of("reports", items, "count", items.size());
    }

    Map<String, Object> taxMovements(String taxCode, LocalDateTime from, LocalDateTime to) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String ccy : List.of("USD", "ZWG")) {
            Optional<LedgerAccount> a = accounts.findByCode(AccountKey.tax(taxCode, ccy).code());
            long collected = 0, remitted = 0;
            Map<String, Long> byType = new LinkedHashMap<>();
            if (a.isPresent()) {
                for (Object[] r : lines.movementsByType(a.get().getId(), from, to)) {
                    long v = ((Number) r[3]).longValue();
                    if ("CREDIT".equals(String.valueOf(r[1]))) { collected += v; byType.merge(r[0] + ":collected", v, Long::sum); }
                    else { remitted += v; byType.merge(r[0] + ":remitted", v, Long::sum); }
                }
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("collectedMinor", collected);
            c.put("remittedOrReversedMinor", remitted);
            c.put("byEntryType", byType);
            c.put("closingBalanceOwedMinor", ledger.balanceAsOf(AccountKey.tax(taxCode, ccy), to));
            out.put(ccy, c);
        }
        return out;
    }

    Map<String, Object> trialBalance(LocalDateTime asOf) {
        List<Map<String, Object>> rows = new ArrayList<>();
        long d = 0, c = 0;
        Map<Long, long[]> totals = accountTotals(asOf);
        for (LedgerAccount a : accounts.findAll()) {
            long[] t = totals.get(a.getId());
            if (t == null || (t[0] == 0 && t[1] == 0)) continue;
            d += t[0];
            c += t[1];
            rows.add(new LinkedHashMap<>(Map.of("account", a.getCode(), "type", a.getAccountType().name(), "debitsMinor", t[0],
                    "creditsMinor", t[1], "balanceMinor", a.getAccountType().balanceOf(t[0], t[1]))));
        }
        return Map.of("asOf", asOf.toString(), "accounts", rows, "totalDebitsMinor", d, "totalCreditsMinor", c, "balanced", d == c);
    }

    // ── Scheduling ───────────────────────────────────────────────────────

    @Scheduled(cron = "${payport.psp.reporting.safeguarding-cron:0 15 0 * * *}", zone = "Africa/Harare")
    public void dailySafeguarding() {
        generate("SAFEGUARDING_DAILY", LocalDate.now().minusDays(1).atStartOfDay(), LocalDate.now().atStartOfDay(), Actor.system("reporting"));
    }

    @Scheduled(cron = "${payport.psp.reporting.monthly-cron:0 0 2 1 * *}", zone = "Africa/Harare")
    public void monthlyDrafts() {
        YearMonth last = YearMonth.now().minusMonths(1);
        LocalDateTime from = last.atDay(1).atStartOfDay(), to = last.plusMonths(1).atDay(1).atStartOfDay();
        for (String t : List.of("RBZ_NPS_MONTHLY", "ZIMRA_IMTT", "ZIMRA_VAT_ON_FEES", "FIU_STR", "TRIAL_BALANCE")) {
            generate(t, from, to, Actor.system("reporting"));
        }
    }

    // ── Formatting helpers ───────────────────────────────────────────────

    private static List<Map<String, Object>> rows(List<Object[]> data, String... cols) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : data) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < cols.length && i < r.length; i++) m.put(cols[i], r[i] instanceof Number n ? n.longValue() : r[i]);
            out.add(m);
        }
        return out;
    }

    /** Flattens nested report data into section,key,value rows for the CSV export. */
    @SuppressWarnings("unchecked")
    static List<List<Object>> flatten(Map<String, Object> data) {
        List<List<Object>> rows = new ArrayList<>();
        walk("", data, rows);
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static void walk(String prefix, Object v, List<List<Object>> rows) {
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) walk(prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey(), e.getValue(), rows);
        } else if (v instanceof Collection<?> c) {
            int i = 0;
            for (Object o : c) walk(prefix + "[" + (i++) + "]", o, rows);
        } else {
            rows.add(List.of(prefix, v == null ? "" : v));
        }
    }

    static String toCsv(List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder("field,value\r\n");
        for (List<Object> r : rows) {
            sb.append(esc(String.valueOf(r.get(0)))).append(',').append(esc(String.valueOf(r.get(1)))).append("\r\n");
        }
        return sb.toString();
    }

    private static String esc(String s) {
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !s.matches("-?\\d+(\\.\\d+)?")) s = "'" + s;
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
