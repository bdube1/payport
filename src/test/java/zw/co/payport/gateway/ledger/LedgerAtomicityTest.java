package zw.co.payport.gateway.ledger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static zw.co.payport.gateway.ledger.LedgerCategory.*;
import static zw.co.payport.gateway.ledger.LedgerDb.*;

/** Phase 1 exit criterion E3: an interrupted posting leaves no partial entry. */
@Tag("ledger-db")
class LedgerAtomicityTest {

    private JournalRequest request(String m, String key) {
        return JournalRequest.builder(key).entity(ENTITY).book(Book.LIVE).effectiveDate(LocalDate.now()).postedBy("atomicity-test")
                .debit(live(PROVIDER_RECEIVABLE, "ECOCASH_" + m, "USD"), 500, null)
                .credit(live(MERCHANT_PAYABLE, m, "USD"), 500, null).build();
    }

    @Test
    void connectionKilledAfterPostingButBeforeCommitLeavesNothing() throws Exception {
        DataSource app = app();
        LedgerClient ledger = new LedgerClient(app);
        String m = "a" + uid();
        JournalRequest r = request(m, "kill-" + m);
        r.accounts().forEach(a -> ledger.openAccount(a, "test"));

        Connection victim = app.getConnection();
        victim.setAutoCommit(false);
        long connId;
        try (Statement s = victim.createStatement(); ResultSet rs = s.executeQuery("SELECT CONNECTION_ID()")) {
            rs.next();
            connId = rs.getLong(1);
        }
        try (CallableStatement cs = victim.prepareCall("{call ledger_post(?, ?, ?)}")) {
            cs.setString(1, r.toJson(LedgerClient.newEntryRef()));
            cs.registerOutParameter(2, Types.BIGINT);
            cs.registerOutParameter(3, Types.VARCHAR);
            cs.execute();
            assertEquals("POSTED", cs.getString(3), "the procedure completed inside the open transaction");
        }
        // Simulate the application process dying before COMMIT.
        exec(admin(), "KILL CONNECTION " + connId);
        try { victim.close(); } catch (Exception ignored) { }

        assertEquals(0, queryLong(app, "SELECT COUNT(*) FROM ledger_entries WHERE idempotency_key = 'kill-" + m + "'"));
        assertEquals(0, ledger.balance(live(MERCHANT_PAYABLE, m, "USD")).balance());
        assertEquals(LedgerClient.Outcome.POSTED, ledger.post(r).outcome(), "the retry after restart posts normally");
        assertEquals(500, ledger.balance(live(MERCHANT_PAYABLE, m, "USD")).balance());
    }

    @Test
    void rollbackAfterSuccessfulCallLeavesNothing() throws Exception {
        DataSource app = app();
        LedgerClient ledger = new LedgerClient(app);
        String m = "b" + uid();
        JournalRequest r = request(m, "rb-" + m);
        r.accounts().forEach(a -> ledger.openAccount(a, "test"));
        try (Connection c = app.getConnection()) {
            c.setAutoCommit(false);
            try (CallableStatement cs = c.prepareCall("{call ledger_post(?, ?, ?)}")) {
                cs.setString(1, r.toJson(LedgerClient.newEntryRef()));
                cs.registerOutParameter(2, Types.BIGINT);
                cs.registerOutParameter(3, Types.VARCHAR);
                cs.execute();
            }
            c.rollback();
        }
        assertEquals(0, queryLong(app, "SELECT COUNT(*) FROM ledger_entries WHERE idempotency_key = 'rb-" + m + "'"));
        assertEquals(0, ledger.balance(live(MERCHANT_PAYABLE, m, "USD")).balance());
        assertEquals(0, ledger.balance(live(PROVIDER_RECEIVABLE, "ECOCASH_" + m, "USD")).balance());
    }

    @Test
    void failedValidationMidProcedureLeavesNothing() {
        LedgerClient ledger = new LedgerClient(app());
        String m = "c" + uid();
        AccountKey rec = live(PROVIDER_RECEIVABLE, "ECOCASH_" + m, "USD");
        AccountKey pay = live(MERCHANT_PAYABLE, m, "USD");
        // Insufficient funds is detected after locking balances; nothing may be written.
        LedgerException e = assertThrows(LedgerException.class, () -> ledger.post(JournalRequest.builder("v-" + m).entity(ENTITY)
                .book(Book.LIVE).effectiveDate(LocalDate.now()).postedBy("t").debit(pay, 5, null).credit(rec, 5, null).build()));
        assertEquals("LEDGER_INSUFFICIENT_FUNDS", e.code());
        assertEquals(0, queryLong(app(), "SELECT COUNT(*) FROM ledger_entries WHERE idempotency_key = 'v-" + m + "'"));
        assertEquals(0, ledger.balance(rec).balance());
    }
}
