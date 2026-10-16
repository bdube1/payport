package zw.co.payport.gateway.recon;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.service.reconciliation.ProviderStatementLine;
import zw.co.payport.gateway.service.reconciliation.StatementParser;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Phase 3 reconciliation of provider statements against PayPort's payments.
 *
 *  1. importStatement: stores the file once (same provider + same file hash = the same
 *     statement; re-importing returns the existing one) and its transaction lines.
 *  2. run: matches the lines against PayPort's payments for the statement window
 *     (ReconEngine) and records every difference as a recon exception with evidence.
 *     Re-running is safe: exceptions are de-duplicated. Nothing is ever adjusted.
 *
 * Payment-side providers only (the provider collected the customer's money). Statement
 * formats: the existing provider parsers, or the generic CSV format (GenericCsvStatementParser).
 */
@Slf4j
@Service("statementReconciliationEngineService")
public class ReconciliationService {

    /** Statuses in which PayPort holds that the provider collected the money. */
    static final Set<String> PAID_STATUSES = Set.of("PAYMENT_CONFIRMED", "FULFILMENT_PENDING", "FULFILMENT_FAILED", "FULFILMENT_RETRY",
            "MANUAL_FULFILMENT", "COMPLETED", "REFUND_PENDING", "REFUNDED");

    public record ImportResult(long statementId, boolean alreadyImported, int lines) {}

    public record RunSummary(long runId, long statementId, int lines, int matched, Map<ReconEngine.Result, Integer> counts) {}

    private final JdbcTemplate jdbc;
    private final ReconExceptionStore exceptions;
    private final List<StatementParser> parsers;
    private final Duration timingTolerance;

    public ReconciliationService(JdbcTemplate jdbc, ReconExceptionStore exceptions, List<StatementParser> parsers,
                                 @org.springframework.beans.factory.annotation.Value("${payport.recon.timing-tolerance-minutes:60}") long toleranceMinutes) {
        this.jdbc = jdbc;
        this.exceptions = exceptions;
        this.parsers = parsers;
        this.timingTolerance = Duration.ofMinutes(toleranceMinutes);
    }

    /** Parses with the provider's own parser, or the generic CSV format when format is "CSV". */
    public List<ProviderStatementLine> parse(String provider, String format, byte[] content) throws StatementParser.StatementParseException {
        if ("CSV".equalsIgnoreCase(format)) {
            return new GenericCsvStatementParser(provider).parse(new ByteArrayInputStream(content));
        }
        StatementParser p = parsers.stream().filter(x -> x.providerCode().equalsIgnoreCase(provider)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No statement parser for " + provider + "; use format=CSV"));
        return p.parse(new ByteArrayInputStream(content));
    }

    @Transactional
    public ImportResult importStatement(String provider, String fileName, byte[] content, List<ProviderStatementLine> parsed, String importedBy) {
        String code = provider.toUpperCase();
        String sha = sha256(content);
        List<Long> existing = jdbc.queryForList("SELECT id FROM recon_statements WHERE provider_code = ? AND file_sha256 = ?", Long.class, code, sha);
        if (!existing.isEmpty()) {
            Integer lines = jdbc.queryForObject("SELECT line_count FROM recon_statements WHERE id = ?", Integer.class, existing.get(0));
            return new ImportResult(existing.get(0), true, lines == null ? 0 : lines);
        }
        List<ProviderStatementLine> tx = parsed.stream().filter(ProviderStatementLine::isTransactionLine).toList();
        LocalDateTime start = tx.stream().map(ProviderStatementLine::getTimestamp).filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null);
        LocalDateTime end = tx.stream().map(ProviderStatementLine::getTimestamp).filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
        org.springframework.jdbc.support.GeneratedKeyHolder kh = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(c -> {
            var ps = c.prepareStatement("INSERT INTO recon_statements (provider_code, file_name, file_sha256, line_count, period_start, "
                    + "period_end, imported_by, imported_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setString(2, fileName);
            ps.setString(3, sha);
            ps.setInt(4, parsed.size());
            ps.setTimestamp(5, start == null ? null : Timestamp.valueOf(start));
            ps.setTimestamp(6, end == null ? null : Timestamp.valueOf(end));
            ps.setString(7, importedBy);
            ps.setTimestamp(8, Timestamp.valueOf(LocalDateTime.now()));
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        int n = 0;
        for (ProviderStatementLine l : parsed) {
            n++;
            jdbc.update("INSERT INTO recon_statement_lines (statement_id, line_no, references_text, amount_minor, currency, occurred_at, "
                            + "raw_row, match_status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    id, n, trunc(String.join("|", l.getCandidateReferences() == null ? List.of() : l.getCandidateReferences()), 1000),
                    l.getAmountCents(), l.getCurrency(), l.getTimestamp(), l.getRawRow(), l.isTransactionLine() ? "UNMATCHED" : "SKIPPED");
        }
        log.info("[RECON] Imported {} statement {} ({} lines) as #{}", code, fileName, n, id);
        return new ImportResult(id, false, n);
    }

    @Transactional
    public RunSummary run(long statementId, String runBy) {
        Map<String, Object> st = jdbc.queryForMap("SELECT * FROM recon_statements WHERE id = ?", statementId);
        String provider = (String) st.get("provider_code");
        List<ReconEngine.Line> lines = jdbc.query("SELECT * FROM recon_statement_lines WHERE statement_id = ? AND match_status <> 'SKIPPED' ORDER BY line_no",
                (rs, i) -> new ReconEngine.Line(rs.getLong("id"), splitRefs(rs.getString("references_text")), rs.getLong("amount_minor"),
                        rs.getString("currency"), rs.getObject("occurred_at", LocalDateTime.class)), statementId);
        LocalDateTime start = st.get("period_start") == null ? null : toLdt(st.get("period_start"));
        LocalDateTime end = st.get("period_end") == null ? null : toLdt(st.get("period_end"));
        if (start == null || end == null) {
            throw new IllegalStateException("Statement #" + statementId + " has no dated transaction lines");
        }

        List<ReconEngine.Payment> payments = loadPayments(provider, start.minusDays(2), end.plusDays(2), lines);
        List<ReconEngine.Finding> findings = ReconEngine.match(lines, payments, start, end, timingTolerance);

        Map<ReconEngine.Result, Integer> counts = new EnumMap<>(ReconEngine.Result.class);
        int matched = 0;
        for (ReconEngine.Finding f : findings) {
            counts.merge(f.result(), 1, Integer::sum);
            if (f.lineId() != null) {
                jdbc.update("UPDATE recon_statement_lines SET match_status = ?, matched_transaction = ? WHERE id = ?",
                        f.result() == ReconEngine.Result.MATCHED ? "MATCHED" : "EXCEPTION", f.transactionReference(), f.lineId());
            }
            if (f.result() == ReconEngine.Result.MATCHED) {
                matched++;
                continue;
            }
            exceptions.raise(f.result().name(), provider, f.transactionReference(), f.providerReference(), f.lineId(),
                    f.expectedMinor(), f.actualMinor(), f.currency(), f.detail(), "STATEMENT");
        }
        String summary = counts.toString();
        org.springframework.jdbc.support.GeneratedKeyHolder kh = new org.springframework.jdbc.support.GeneratedKeyHolder();
        final int matchedFinal = matched;
        jdbc.update(c -> {
            var ps = c.prepareStatement("INSERT INTO recon_runs (provider_code, statement_id, window_start, window_end, matched, exceptions, "
                    + "summary, run_by, run_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, provider);
            ps.setLong(2, statementId);
            ps.setTimestamp(3, Timestamp.valueOf(start));
            ps.setTimestamp(4, Timestamp.valueOf(end));
            ps.setInt(5, matchedFinal);
            ps.setInt(6, findings.size() - matchedFinal);
            ps.setString(7, trunc(summary, 2000));
            ps.setString(8, runBy);
            ps.setTimestamp(9, Timestamp.valueOf(LocalDateTime.now()));
            return ps;
        }, kh);
        log.info("[RECON] {} statement #{}: {}", provider, statementId, summary);
        return new RunSummary(kh.getKey().longValue(), statementId, lines.size(), matched, counts);
    }

    private List<ReconEngine.Payment> loadPayments(String provider, LocalDateTime from, LocalDateTime to, List<ReconEngine.Line> lines) {
        List<Map<String, Object>> rows = new ArrayList<>(jdbc.queryForList(
                "SELECT reference, status, amount_cents, currency, provider_reference, payment_correlator, payment_confirmed_at, payment_submitted_at "
                        + "FROM transactions WHERE payment_provider_code = ? AND payment_submitted_at BETWEEN ? AND ?", provider, from, to));
        // Payments referenced by a line but submitted outside the window (e.g. very late payments) are matched too.
        Set<String> have = new HashSet<>();
        for (Map<String, Object> r : rows) have.add((String) r.get("reference"));
        for (ReconEngine.Line l : lines) {
            for (String ref : l.references()) {
                String intent = zw.co.payport.gateway.payments.PaymentAttemptStore.intentReference(ref);
                if (intent == null || have.contains(intent) || !intent.startsWith("PP-")) continue;
                List<Map<String, Object>> extra = jdbc.queryForList(
                        "SELECT reference, status, amount_cents, currency, provider_reference, payment_correlator, payment_confirmed_at, payment_submitted_at "
                                + "FROM transactions WHERE reference = ?", intent);
                rows.addAll(extra);
                have.add(intent);
            }
        }
        List<ReconEngine.Payment> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String ref = (String) r.get("reference");
            Set<String> refs = new HashSet<>();
            if (r.get("provider_reference") != null) refs.add((String) r.get("provider_reference"));
            if (r.get("payment_correlator") != null) refs.add((String) r.get("payment_correlator"));
            for (Map<String, Object> a : jdbc.queryForList("SELECT attempt_reference, provider_reference FROM payment_attempts WHERE transaction_reference = ?", ref)) {
                if (a.get("attempt_reference") != null) refs.add((String) a.get("attempt_reference"));
                if (a.get("provider_reference") != null) refs.add((String) a.get("provider_reference"));
            }
            String status = (String) r.get("status");
            out.add(new ReconEngine.Payment(ref, refs, ((Number) r.get("amount_cents")).longValue(), (String) r.get("currency"),
                    PAID_STATUSES.contains(status), status, r.get("payment_confirmed_at") == null ? null : toLdt(r.get("payment_confirmed_at"))));
        }
        return out;
    }

    private static LocalDateTime toLdt(Object o) {
        if (o instanceof LocalDateTime l) return l;
        if (o instanceof Timestamp t) return t.toLocalDateTime();
        if (o instanceof java.util.Date d) return new Timestamp(d.getTime()).toLocalDateTime();
        return LocalDateTime.parse(o.toString().replace(' ', 'T'));
    }

    private static List<String> splitRefs(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : s.split("\\|")) if (!p.isBlank()) out.add(p.trim());
        return out;
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String trunc(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
