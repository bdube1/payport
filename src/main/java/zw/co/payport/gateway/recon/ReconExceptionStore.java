package zw.co.payport.gateway.recon;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Reconciliation exceptions (recon_exceptions): every difference between what PayPort
 * recorded and what a provider says, from statements (source STATEMENT) or from the live
 * payment lifecycle (source LIFECYCLE, e.g. a payment confirmed after the customer was told
 * it timed out). Raising is idempotent (dedupe key); nothing here changes a payment or a
 * balance. Resolution is a person's recorded decision.
 *
 * Writes join the caller's transaction (JdbcTemplate under the JPA transaction manager).
 */
@Component
public class ReconExceptionStore {

    public enum Category {
        MISSING_IN_PAYPORT, MISSING_IN_STATEMENT, DUPLICATE, AMOUNT_MISMATCH, CURRENCY_MISMATCH, STATUS_MISMATCH, TIMING,
        PAID_AFTER_TIMEOUT, PAID_AFTER_FAILURE, FAILED_AFTER_PAID, PAID_ON_SUPERSEDED_ATTEMPT
    }

    /** Recorded decisions. HONOURED is only set by the approved honour-late-payment action. */
    public enum Resolution { EXPLAINED_TIMING, REFUND_REQUIRED, REFUNDED, HONOURED, PROVIDER_CORRECTED, WRITTEN_OFF, FALSE_POSITIVE }

    private final JdbcTemplate jdbc;

    public ReconExceptionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Raise (or re-see) an exception. Returns true if it is new. */
    public boolean raise(String category, String provider, String txRef, String providerRef, Long expected, Long actual,
                         String currency, String detail, String source) {
        return raise(category, provider, txRef, providerRef, null, expected, actual, currency, detail, source);
    }

    public boolean raise(String category, String provider, String txRef, String providerRef, Long statementLineId,
                         Long expected, Long actual, String currency, String detail, String source) {
        Category.valueOf(category); // reject unknown categories early
        String key = category + "|" + nz(txRef) + "|" + (statementLineId != null ? "L" + statementLineId : nz(providerRef));
        if (key.length() > 200) key = key.substring(0, 200);
        LocalDateTime now = LocalDateTime.now();
        int n = jdbc.update("INSERT INTO recon_exceptions (dedupe_key, category, source, provider_code, transaction_reference, "
                        + "provider_reference, statement_line_id, expected_minor, actual_minor, currency, detail, status, created_at, last_seen_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?) "
                        + "ON DUPLICATE KEY UPDATE last_seen_at = VALUES(last_seen_at)",
                key, category, source, provider, txRef, trunc(providerRef, 200), statementLineId, expected, actual,
                currency, trunc(detail, 1000), now, now);
        return n == 1;
    }

    /** Records a person's decision on every open exception of this category for this payment. */
    public int resolve(String category, String txRef, String resolution, String by, String note) {
        Resolution.valueOf(resolution);
        return jdbc.update("UPDATE recon_exceptions SET status = 'RESOLVED', resolution = ?, resolved_by = ?, resolved_at = ?, "
                        + "resolution_note = ? WHERE category = ? AND transaction_reference = ? AND status = 'OPEN'",
                resolution, by, LocalDateTime.now(), trunc(note, 1000), category, txRef);
    }

    /** Records a person's decision on one exception. HONOURED cannot be set here (it needs the approved action). */
    public int resolveById(long id, String resolution, String by, String note) {
        if (Resolution.valueOf(resolution) == Resolution.HONOURED) {
            throw new IllegalArgumentException("HONOURED is set only by the approved honour-late-payment action");
        }
        if (note == null || note.isBlank()) throw new IllegalArgumentException("a resolution note is required");
        return jdbc.update("UPDATE recon_exceptions SET status = 'RESOLVED', resolution = ?, resolved_by = ?, resolved_at = ?, "
                + "resolution_note = ? WHERE id = ? AND status = 'OPEN'", resolution, by, LocalDateTime.now(), trunc(note, 1000), id);
    }

    public List<Map<String, Object>> open(String category, int limit) {
        if (category == null) {
            return jdbc.queryForList("SELECT * FROM recon_exceptions WHERE status = 'OPEN' ORDER BY created_at DESC LIMIT ?", limit);
        }
        return jdbc.queryForList("SELECT * FROM recon_exceptions WHERE status = 'OPEN' AND category = ? ORDER BY created_at DESC LIMIT ?",
                category, limit);
    }

    public List<Map<String, Object>> forTransaction(String txRef) {
        return jdbc.queryForList("SELECT * FROM recon_exceptions WHERE transaction_reference = ? ORDER BY id", txRef);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String trunc(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
