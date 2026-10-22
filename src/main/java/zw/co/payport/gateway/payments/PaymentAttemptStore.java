package zw.co.payport.gateway.payments;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * payment_attempts: one row per request sent to a provider. Written in the caller's
 * transaction (JdbcTemplate joins the JPA transaction).
 */
@Component
public class PaymentAttemptStore {

    public enum Status { SENT, ACCEPTED, REJECTED, UNKNOWN }

    public record Attempt(long id, String reference, int attemptNo, String attemptReference, String providerCode, Status status,
                          String providerReference, String errorClass, String errorMessage,
                          LocalDateTime sentAt, LocalDateTime respondedAt) {}

    private final JdbcTemplate jdbc;

    public PaymentAttemptStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The next attempt number for this payment (1 for the first). */
    public int nextAttemptNo(String reference) {
        Integer next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(attempt_no), 0) + 1 FROM payment_attempts WHERE transaction_reference = ?",
                Integer.class, reference);
        return next == null ? 1 : next;
    }

    /** The reference sent to the provider: the payment reference for attempt 1, reference-N after that. */
    public static String attemptReference(String reference, int attemptNo) {
        return attemptNo <= 1 ? reference : reference + "-" + attemptNo;
    }

    private static final java.util.regex.Pattern ATTEMPT_SUFFIX = java.util.regex.Pattern.compile("^(PP-[A-Z]+-\\d{8}-\\d{6})-(\\d{1,2})$");

    /** The payment (intent) reference for a reference a provider sent back, which may carry an attempt suffix. */
    public static String intentReference(String providerSideReference) {
        if (providerSideReference == null) return null;
        java.util.regex.Matcher m = ATTEMPT_SUFFIX.matcher(providerSideReference.trim());
        return m.matches() ? m.group(1) : providerSideReference.trim();
    }

    /** Records that a request is about to be sent. Must commit BEFORE the provider is called. */
    public void recordSent(String reference, int attemptNo, String attemptReference, String providerCode, LocalDateTime now) {
        jdbc.update("INSERT INTO payment_attempts (transaction_reference, attempt_no, attempt_reference, provider_code, status, sent_at) "
                + "VALUES (?, ?, ?, ?, 'SENT', ?)", reference, attemptNo, attemptReference, providerCode, now);
    }

    public void recordOutcome(String reference, int attemptNo, Status status, String providerReference,
                              String errorClass, String errorMessage, LocalDateTime now) {
        jdbc.update("UPDATE payment_attempts SET status = ?, provider_reference = ?, error_class = ?, error_message = ?, "
                        + "provider_message = ?, responded_at = ? WHERE transaction_reference = ? AND attempt_no = ?",
                status.name(), truncate(providerReference, 200), truncate(errorClass, 200), truncate(errorMessage, 1000),
                truncate(errorMessage, 1000), now, reference, attemptNo);
    }

    /** Latest attempt, if any. */
    public java.util.Optional<Attempt> latest(String reference) {
        List<Attempt> all = forTransaction(reference);
        return all.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(all.get(all.size() - 1));
    }

    public List<Attempt> forTransaction(String reference) {
        return jdbc.query("SELECT * FROM payment_attempts WHERE transaction_reference = ? ORDER BY attempt_no",
                (rs, i) -> new Attempt(rs.getLong("id"), rs.getString("transaction_reference"), rs.getInt("attempt_no"),
                        rs.getString("attempt_reference"),
                        rs.getString("provider_code"), Status.valueOf(rs.getString("status")),
                        rs.getString("provider_reference"), rs.getString("error_class"), rs.getString("error_message"),
                        rs.getObject("sent_at", LocalDateTime.class), rs.getObject("responded_at", LocalDateTime.class)),
                reference);
    }

    static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
