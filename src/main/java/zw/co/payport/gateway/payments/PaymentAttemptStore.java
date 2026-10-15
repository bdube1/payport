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

    public record Attempt(long id, String reference, int attemptNo, String providerCode, Status status,
                          String providerReference, String errorClass, String errorMessage,
                          LocalDateTime sentAt, LocalDateTime respondedAt) {}

    private final JdbcTemplate jdbc;

    public PaymentAttemptStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Records that a request is about to be sent. Must commit BEFORE the provider is called. */
    public int recordSent(String reference, String providerCode, LocalDateTime now) {
        Integer next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(attempt_no), 0) + 1 FROM payment_attempts WHERE transaction_reference = ?",
                Integer.class, reference);
        jdbc.update("INSERT INTO payment_attempts (transaction_reference, attempt_no, provider_code, status, sent_at) "
                + "VALUES (?, ?, ?, 'SENT', ?)", reference, next, providerCode, now);
        return next;
    }

    public void recordOutcome(String reference, int attemptNo, Status status, String providerReference,
                              String errorClass, String errorMessage, LocalDateTime now) {
        jdbc.update("UPDATE payment_attempts SET status = ?, provider_reference = ?, error_class = ?, error_message = ?, "
                        + "responded_at = ? WHERE transaction_reference = ? AND attempt_no = ?",
                status.name(), truncate(providerReference, 200), truncate(errorClass, 200), truncate(errorMessage, 1000),
                now, reference, attemptNo);
    }

    public List<Attempt> forTransaction(String reference) {
        return jdbc.query("SELECT * FROM payment_attempts WHERE transaction_reference = ? ORDER BY attempt_no",
                (rs, i) -> new Attempt(rs.getLong("id"), rs.getString("transaction_reference"), rs.getInt("attempt_no"),
                        rs.getString("provider_code"), Status.valueOf(rs.getString("status")),
                        rs.getString("provider_reference"), rs.getString("error_class"), rs.getString("error_message"),
                        rs.getObject("sent_at", LocalDateTime.class), rs.getObject("responded_at", LocalDateTime.class)),
                reference);
    }

    static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
