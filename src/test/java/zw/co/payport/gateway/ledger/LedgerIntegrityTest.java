package zw.co.payport.gateway.ledger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static zw.co.payport.gateway.ledger.LedgerCategory.*;
import static zw.co.payport.gateway.ledger.LedgerDb.*;

/**
 * The integrity detector must catch tampering done OUTSIDE the procedures (for example by
 * someone with DBA access). Each test injects one fault as the DBA, checks it is reported,
 * then repairs it so the database is clean again.
 */
@Tag("ledger-db")
class LedgerIntegrityTest {

    private final DataSource admin = admin();
    private final LedgerClient ledger = new LedgerClient(app());

    private LedgerClient.PostResult post(String m) {
        return ledger.post(JournalRequest.builder("int-" + m).entity(ENTITY).book(Book.LIVE).effectiveDate(LocalDate.now())
                .postedBy("integrity-test").template("TEST", 1)
                .debit(live(PROVIDER_RECEIVABLE, "ECOCASH_" + m, "USD"), 1_000, null)
                .credit(live(MERCHANT_PAYABLE, m, "USD"), 1_000, null).build());
    }

    private Set<String> codes() {
        return ledger.integrityReport().stream().map(LedgerClient.Violation::checkCode).collect(Collectors.toSet());
    }

    @Test
    void cleanLedgerReportsNothing() {
        post("i" + uid());
        assertEquals(List.of(), ledger.integrityReport());
    }

    @Test
    void changedLineAmountIsDetectedThreeWays() {
        long id = post("j" + uid()).entryId();
        exec(admin, "UPDATE ledger_lines SET amount_minor = amount_minor + 1 WHERE entry_id = " + id + " AND direction = 'CREDIT'");
        try {
            Set<String> c = codes();
            assertTrue(c.containsAll(Set.of("UNBALANCED_ENTRY", "BALANCE_MISMATCH", "CONTENT_HASH_MISMATCH")), c.toString());
        } finally {
            exec(admin, "UPDATE ledger_lines SET amount_minor = amount_minor - 1 WHERE entry_id = " + id + " AND direction = 'CREDIT'");
        }
        assertEquals(Set.of(), codes());
    }

    @Test
    void tamperedBalanceProjectionIsDetected() {
        String m = "k" + uid();
        post(m);
        long acct = queryLong(admin, "SELECT id FROM ledger_accounts WHERE code = '" + live(MERCHANT_PAYABLE, m, "USD").code() + "'");
        exec(admin, "UPDATE ledger_balances SET balance = balance + 5000 WHERE account_id = " + acct);
        try {
            assertTrue(codes().contains("BALANCE_MISMATCH"));
        } finally {
            exec(admin, "UPDATE ledger_balances SET balance = balance - 5000 WHERE account_id = " + acct);
        }
        assertEquals(Set.of(), codes());
    }

    @Test
    void negativeProtectedBalanceIsDetected() {
        String m = "n" + uid();
        long id = post(m).entryId();
        long acct = queryLong(admin, "SELECT id FROM ledger_accounts WHERE code = '" + live(MERCHANT_PAYABLE, m, "USD").code() + "'");
        exec(admin, "UPDATE ledger_balances SET balance = -1 WHERE account_id = " + acct);
        try {
            assertTrue(codes().contains("NEGATIVE_PROTECTED"));
        } finally {
            exec(admin, "UPDATE ledger_balances SET balance = 1000 WHERE account_id = " + acct);
        }
        assertEquals(Set.of(), codes(), "entry " + id);
    }

    @Test
    void backdatedEntryIsDetectedByContentHash() {
        long id = post("d" + uid()).entryId();
        exec(admin, "UPDATE ledger_entries SET effective_date = effective_date - INTERVAL 1 DAY WHERE id = " + id);
        try {
            assertTrue(codes().contains("CONTENT_HASH_MISMATCH"));
        } finally {
            exec(admin, "UPDATE ledger_entries SET effective_date = effective_date + INTERVAL 1 DAY WHERE id = " + id);
        }
        assertEquals(Set.of(), codes());
    }

    @Test
    void sealsCoverEntriesAndDetectRewrittenHistory() {
        long id = post("s" + uid()).entryId();
        LedgerClient.Seal seal = ledger.seal("integrity-test", 0, 100_000).orElseThrow();
        assertTrue(seal.entryCount() >= 1);
        assertEquals(Set.of(), codes());

        // Rewrite the entry AND its content hash consistently: only the seal can catch this.
        String original = queryString("SELECT content_hash FROM ledger_entries WHERE id = " + id);
        exec(admin, "UPDATE ledger_entries SET content_hash = SHA2('forged', 256) WHERE id = " + id);
        try {
            Set<String> c = codes();
            assertTrue(c.contains("SEAL_MISMATCH"), c.toString());
        } finally {
            exec(admin, "UPDATE ledger_entries SET content_hash = '" + original + "' WHERE id = " + id);
        }
        assertEquals(Set.of(), codes());

        // A second seal links to the first.
        post("s2" + uid());
        ledger.seal("integrity-test", 0, 100_000).orElseThrow();
        assertEquals(Set.of(), codes());
        long last = queryLong(admin, "SELECT MAX(id) FROM ledger_seals");
        String prev = queryString("SELECT prev_seal_hash FROM ledger_seals WHERE id = " + last);
        exec(admin, "UPDATE ledger_seals SET prev_seal_hash = SHA2('x', 256) WHERE id = " + last);
        try {
            assertTrue(codes().contains("SEAL_CHAIN_BROKEN"));
        } finally {
            exec(admin, "UPDATE ledger_seals SET prev_seal_hash = '" + prev + "' WHERE id = " + last);
        }
        assertEquals(Set.of(), codes());
    }

    @Test
    void sealingRespectsTheSettleWindow() {
        post("w" + uid());
        // With a 1-hour settle window, the entry just posted must not be sealed yet.
        long before = queryLong(admin, "SELECT IFNULL(MAX(to_entry_id), 0) FROM ledger_seals");
        ledger.seal("integrity-test", 3600, 100_000);
        long after = queryLong(admin, "SELECT IFNULL(MAX(to_entry_id), 0) FROM ledger_seals");
        long newest = queryLong(admin, "SELECT MAX(id) FROM ledger_entries");
        assertTrue(after < newest, "entries younger than the settle window stay unsealed (before=" + before + ", after=" + after + ")");
    }

    private String queryString(String sql) {
        try (var c = admin.getConnection(); var s = c.createStatement(); var rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
