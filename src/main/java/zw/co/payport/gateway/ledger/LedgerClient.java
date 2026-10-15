package zw.co.payport.gateway.ledger;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * The only way the application writes to the ledger: calls the definer procedures of V34
 * (ADR-0008), each in its own short transaction on a connection from the pool.
 *
 * Deliberately independent of Spring transactions: a posting is never part of, and can
 * never roll back, the caller's business transaction (shadow mode, ADR-0009).
 *
 * Retries: deadlock (1213) and lock-wait timeout (1205) are retried up to 3 times. A
 * concurrent duplicate of the same idempotency key (1062) resolves to DUPLICATE when the
 * request is identical. Every other failure surfaces as LedgerException with the
 * database's stable code.
 */
public class LedgerClient {

    public enum Outcome { POSTED, DUPLICATE }

    public record PostResult(long entryId, String entryRef, Outcome outcome) {}

    public record Balance(AccountKey account, long balance, long debitTotal, long creditTotal) {}

    public record Violation(String checkCode, String subject, String detail) {}

    public record Seal(long sealId, int entryCount) {}

    private static final int MAX_ATTEMPTS = 3;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DataSource dataSource;

    public LedgerClient(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ── Posting ──────────────────────────────────────────────────────────

    public PostResult post(JournalRequest request) {
        // Accounts are ensured BEFORE, in their own short transactions and in a fixed order.
        // Opening them inside the posting transaction would lock account rows in line order
        // and deadlock opposing transfers (found by LedgerConcurrencyTest).
        ensureAccounts(request.accounts(), request.postedBy());
        return withRetry(() -> {
            try (Connection c = dataSource.getConnection()) {
                boolean auto = c.getAutoCommit();
                c.setAutoCommit(false);
                try {
                    String entryRef = newEntryRef();
                    try (CallableStatement cs = c.prepareCall("{call ledger_post(?, ?, ?)}")) {
                        cs.setString(1, request.toJson(entryRef));
                        cs.registerOutParameter(2, Types.BIGINT);
                        cs.registerOutParameter(3, Types.VARCHAR);
                        cs.execute();
                        long id = cs.getLong(2);
                        Outcome outcome = Outcome.valueOf(cs.getString(3));
                        c.commit();
                        return new PostResult(id, outcome == Outcome.POSTED ? entryRef : entryRefOf(c, id), outcome);
                    }
                } catch (SQLException e) {
                    c.rollback();
                    if (e.getErrorCode() == 1062) {
                        return existingIfSameRequest(c, request);
                    }
                    throw e;
                } finally {
                    c.setAutoCommit(auto);
                }
            }
        });
    }

    public PostResult reverse(String originalEntryRef, String idempotencyKey, LocalDate effectiveDate, String postedBy, String reason) {
        return withRetry(() -> {
            try (Connection c = dataSource.getConnection()) {
                boolean auto = c.getAutoCommit();
                c.setAutoCommit(false);
                try (CallableStatement cs = c.prepareCall("{call ledger_reverse(?, ?, ?, ?, ?, ?, ?, ?)}")) {
                    String entryRef = newEntryRef();
                    cs.setString(1, originalEntryRef);
                    cs.setString(2, entryRef);
                    cs.setString(3, idempotencyKey);
                    if (effectiveDate == null) cs.setNull(4, Types.DATE); else cs.setDate(4, Date.valueOf(effectiveDate));
                    cs.setString(5, postedBy);
                    cs.setString(6, reason);
                    cs.registerOutParameter(7, Types.BIGINT);
                    cs.registerOutParameter(8, Types.VARCHAR);
                    cs.execute();
                    long id = cs.getLong(7);
                    Outcome outcome = Outcome.valueOf(cs.getString(8));
                    c.commit();
                    return new PostResult(id, outcome == Outcome.POSTED ? entryRef : entryRefOf(c, id), outcome);
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(auto);
                }
            }
        });
    }

    /** Opens (idempotently) and returns the account id. */
    public long openAccount(AccountKey account, String by) {
        return withRetry(() -> {
            try (Connection c = dataSource.getConnection()) {
                boolean auto = c.getAutoCommit();
                c.setAutoCommit(false);
                try {
                    long id = openAccount(c, account, by);
                    c.commit();
                    return id;
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(auto);
                }
            }
        });
    }

    /** Opens any of these accounts that do not exist yet, each in its own transaction, in code order. */
    void ensureAccounts(java.util.Collection<AccountKey> accounts, String by) {
        java.util.List<AccountKey> sorted = new java.util.ArrayList<>(accounts);
        sorted.sort(java.util.Comparator.comparing(AccountKey::code));
        java.util.Set<String> existing = new java.util.HashSet<>();
        try (Connection c = dataSource.getConnection()) {
            StringBuilder q = new StringBuilder("SELECT a.code FROM ledger_accounts a JOIN ledger_balances b ON b.account_id = a.id WHERE a.code IN (");
            for (int i = 0; i < sorted.size(); i++) q.append(i == 0 ? "?" : ",?");
            q.append(')');
            try (PreparedStatement ps = c.prepareStatement(q.toString())) {
                for (int i = 0; i < sorted.size(); i++) ps.setString(i + 1, sorted.get(i).code());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) existing.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw LedgerException.fromDatabaseMessage(e.getMessage(), e);
        }
        for (AccountKey a : sorted) {
            if (!existing.contains(a.code())) openAccount(a, by);
        }
    }

    // ── Periods and sealing ─────────────────────────────────────────────

    public void closePeriod(String entity, Book book, String period, String by) {
        callInTx("{call ledger_close_period(?, ?, ?, ?)}", entity, book.name(), period, by);
    }

    /** Callers must hold an approved four-eyes request before re-opening (see ApprovalService). */
    public void reopenPeriod(String entity, Book book, String period, String by) {
        callInTx("{call ledger_reopen_period(?, ?, ?, ?)}", entity, book.name(), period, by);
    }

    public Optional<Seal> seal(String by, int settleSeconds, int maxEntries) {
        return withRetry(() -> {
            try (Connection c = dataSource.getConnection()) {
                boolean auto = c.getAutoCommit();
                c.setAutoCommit(false);
                try (CallableStatement cs = c.prepareCall("{call ledger_seal(?, ?, ?, ?, ?)}")) {
                    cs.setString(1, by);
                    cs.setInt(2, settleSeconds);
                    cs.setInt(3, maxEntries);
                    cs.registerOutParameter(4, Types.BIGINT);
                    cs.registerOutParameter(5, Types.INTEGER);
                    cs.execute();
                    long id = cs.getLong(4);
                    boolean none = cs.wasNull();
                    int count = cs.getInt(5);
                    c.commit();
                    return none ? Optional.<Seal>empty() : Optional.of(new Seal(id, count));
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(auto);
                }
            }
        });
    }

    // ── Reads ────────────────────────────────────────────────────────────

    public Balance balance(AccountKey account) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT b.balance, b.debit_total, b.credit_total FROM ledger_accounts a "
                             + "JOIN ledger_balances b ON b.account_id = a.id WHERE a.code = ?")) {
            ps.setString(1, account.code());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new Balance(account, 0, 0, 0);
                return new Balance(account, rs.getLong(1), rs.getLong(2), rs.getLong(3));
            }
        } catch (SQLException e) {
            throw LedgerException.fromDatabaseMessage(e.getMessage(), e);
        }
    }

    /** Runs every integrity check; an empty list means the ledger is consistent. */
    public List<Violation> integrityReport() {
        try (Connection c = dataSource.getConnection();
             CallableStatement cs = c.prepareCall("{call ledger_integrity_report()}")) {
            List<Violation> out = new ArrayList<>();
            boolean hasResult = cs.execute();
            while (true) {
                if (hasResult) {
                    try (ResultSet rs = cs.getResultSet()) {
                        while (rs.next()) out.add(new Violation(rs.getString(1), rs.getString(2), rs.getString(3)));
                    }
                } else if (cs.getUpdateCount() == -1) {
                    break;
                }
                hasResult = cs.getMoreResults();
            }
            return out;
        } catch (SQLException e) {
            throw LedgerException.fromDatabaseMessage(e.getMessage(), e);
        }
    }

    // ── Internals ────────────────────────────────────────────────────────

    private static long openAccount(Connection c, AccountKey a, String by) throws SQLException {
        try (CallableStatement cs = c.prepareCall("{call ledger_open_account(?, ?, ?, ?, ?, ?, ?)}")) {
            cs.setString(1, a.entity());
            cs.setString(2, a.book().name());
            cs.setString(3, a.category().name());
            cs.setString(4, a.owner());
            cs.setString(5, a.currency());
            cs.setString(6, by);
            cs.registerOutParameter(7, Types.BIGINT);
            cs.execute();
            return cs.getLong(7);
        }
    }

    private PostResult existingIfSameRequest(Connection c, JournalRequest request) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, entry_ref, request_hash FROM ledger_entries WHERE idempotency_key = ?")) {
            ps.setString(1, request.idempotencyKey());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    if (request.requestHash().equals(rs.getString(3))) {
                        return new PostResult(rs.getLong(1), rs.getString(2), Outcome.DUPLICATE);
                    }
                    throw new LedgerException("LEDGER_IDEMPOTENCY_CONFLICT", "key already used for a different request", null);
                }
            }
        }
        throw new LedgerException("LEDGER_ERROR", "duplicate key but no entry found for " + request.idempotencyKey(), null);
    }

    private static String entryRefOf(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT entry_ref FROM ledger_entries WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void callInTx(String sql, String... args) {
        withRetry(() -> {
            try (Connection c = dataSource.getConnection()) {
                boolean auto = c.getAutoCommit();
                c.setAutoCommit(false);
                try (CallableStatement cs = c.prepareCall(sql)) {
                    for (int i = 0; i < args.length; i++) cs.setString(i + 1, args[i]);
                    cs.execute();
                    c.commit();
                    return null;
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(auto);
                }
            }
        });
    }

    static String newEntryRef() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return "le_" + HexFormat.of().formatHex(b);
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run() throws SQLException;
    }

    private static <T> T withRetry(SqlWork<T> work) {
        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return work.run();
            } catch (SQLException e) {
                last = e;
                boolean transientLock = e.getErrorCode() == 1213 || e.getErrorCode() == 1205;
                if (!transientLock || attempt == MAX_ATTEMPTS) break;
                try {
                    Thread.sleep(5L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw LedgerException.fromDatabaseMessage(last.getMessage(), last);
    }
}
