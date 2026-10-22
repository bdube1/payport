package zw.co.payport.gateway.payments;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * payment_checks: the uncertain-outcome resolver's schedule. One row per payment that has
 * been sent to a provider; OPEN until the payment reaches a definite outcome (RESOLVED) or
 * automatic checks give up and a person must decide (MANUAL). Times are written from the
 * application clock and compared with the application clock, never NOW() in SQL.
 */
@Component
public class PaymentCheckStore {

    public enum Status { OPEN, RESOLVED, MANUAL }

    public record Check(String reference, String providerCode, Status status, LocalDateTime submittedAt,
                        LocalDateTime nextCheckAt, int checkCount, LocalDateTime lastCheckedAt,
                        String lastResult, LocalDateTime closedAt) {}

    private final JdbcTemplate jdbc;

    public PaymentCheckStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Opens (or re-opens) the check for a payment. Idempotent. */
    public void open(String reference, String providerCode, LocalDateTime submittedAt, LocalDateTime firstCheckAt) {
        jdbc.update("INSERT INTO payment_checks (transaction_reference, provider_code, status, submitted_at, next_check_at) "
                        + "VALUES (?, ?, 'OPEN', ?, ?) "
                        + "ON DUPLICATE KEY UPDATE provider_code = VALUES(provider_code), status = 'OPEN', closed_at = NULL, "
                        + "submitted_at = VALUES(submitted_at), next_check_at = VALUES(next_check_at)",
                reference, providerCode, submittedAt, firstCheckAt);
    }

    /** Opens a check only if the payment has none (backfill for payments pending before this release). */
    public boolean openIfAbsent(String reference, String providerCode, LocalDateTime submittedAt, LocalDateTime firstCheckAt) {
        return jdbc.update("INSERT IGNORE INTO payment_checks (transaction_reference, provider_code, status, submitted_at, next_check_at) "
                + "VALUES (?, ?, 'OPEN', ?, ?)", reference, providerCode, submittedAt, firstCheckAt) > 0;
    }

    /** Asks the resolver to check this payment on its next run (e.g. a callback arrived that the provider has not confirmed yet). */
    public void checkSoon(String reference, LocalDateTime at) {
        jdbc.update("UPDATE payment_checks SET next_check_at = LEAST(next_check_at, ?) WHERE transaction_reference = ? AND status = 'OPEN'",
                at, reference);
    }

    public List<Check> due(LocalDateTime now, int limit) {
        return jdbc.query("SELECT * FROM payment_checks WHERE status = 'OPEN' AND next_check_at <= ? ORDER BY next_check_at LIMIT ?",
                (rs, i) -> map(rs), now, limit);
    }

    public Optional<Check> find(String reference) {
        return jdbc.query("SELECT * FROM payment_checks WHERE transaction_reference = ?", (rs, i) -> map(rs), reference)
                .stream().findFirst();
    }

    public void recordCheck(String reference, LocalDateTime now, String result, LocalDateTime nextCheckAt) {
        jdbc.update("UPDATE payment_checks SET check_count = check_count + 1, last_checked_at = ?, last_result = ?, next_check_at = ? "
                + "WHERE transaction_reference = ?", now, PaymentAttemptStore.truncate(result, 500), nextCheckAt, reference);
    }

    public void reschedule(String reference, LocalDateTime nextCheckAt) {
        jdbc.update("UPDATE payment_checks SET next_check_at = ? WHERE transaction_reference = ?", nextCheckAt, reference);
    }

    public void close(String reference, Status status, String result, LocalDateTime now) {
        jdbc.update("UPDATE payment_checks SET status = ?, last_result = ?, closed_at = ? WHERE transaction_reference = ? AND status = 'OPEN'",
                status.name(), PaymentAttemptStore.truncate(result, 500), now, reference);
    }

    private static Check map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Check(rs.getString("transaction_reference"), rs.getString("provider_code"),
                Status.valueOf(rs.getString("status")), rs.getObject("submitted_at", LocalDateTime.class),
                rs.getObject("next_check_at", LocalDateTime.class), rs.getInt("check_count"),
                rs.getObject("last_checked_at", LocalDateTime.class), rs.getString("last_result"),
                rs.getObject("closed_at", LocalDateTime.class));
    }
}
