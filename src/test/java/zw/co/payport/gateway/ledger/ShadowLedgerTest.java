package zw.co.payport.gateway.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import zw.co.payport.gateway.ledger.shadow.ShadowComparison;
import zw.co.payport.gateway.ledger.shadow.ShadowLedgerSweeper;
import zw.co.payport.gateway.ledger.templates.PostingTemplates;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static zw.co.payport.gateway.ledger.LedgerCategory.*;
import static zw.co.payport.gateway.ledger.LedgerDb.*;

/**
 * Shadow posting end to end on the real database: gateway rows are created as the gateway
 * would create them (as the app user), the sweeper posts them, and the comparison report
 * must agree with the legacy arithmetic at every step.
 */
@Tag("ledger-db")
class ShadowLedgerTest {

    private DataSource ds;
    private LedgerClient ledger;
    private ShadowLedgerSweeper sweeper;
    private ShadowComparison comparison;
    private long merchant;

    @BeforeEach
    void setUp() throws Exception {
        ds = app();
        ledger = new LedgerClient(ds);
        sweeper = new ShadowLedgerSweeper(ds, ledger, ENTITY, ccy -> "SETTLEMENT_" + ccy,
                vendor -> Map.of("ECONET", 300).get(vendor), Duration.ofMinutes(10), 200);
        comparison = new ShadowComparison(ds, ENTITY);
        merchant = insert("INSERT INTO merchants (business_name, registration_number, contact_email, contact_phone, status) "
                + "VALUES ('Shadow Test', 'R" + uid() + "', 'x@y', '+263770000000', 'ACTIVE')");
    }

    // ── fixtures (gateway tables, as the gateway writes them) ──────────

    private long insert(String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS);
            try (ResultSet rs = s.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void update(String sql) {
        exec(ds, sql);
    }

    private String txRef() {
        return "PP-API-20261015-" + String.format("%06d", (int) (Math.random() * 1_000_000)) + uid().substring(0, 2);
    }

    private long paidSession(boolean live, long gross, long commission, String paidAt) throws Exception {
        String ref = txRef();
        insert("INSERT INTO transactions (reference, status, channel, product_type_id, recipient_identifier, amount_cents, currency, "
                + "payment_provider_code, provider_reference, payment_confirmed_at) SELECT '" + ref + "', 'COMPLETED', 'API', id, 'x', "
                + gross + ", 'USD', '" + (live ? "ECOCASH" : "TEST_MODE") + "', '" + (live ? "ECO-" + uid() : "TEST_SIM_" + uid())
                + "', '" + paidAt + "' FROM product_types WHERE code = 'MERCHANT_CHECKOUT'");
        return insert("INSERT INTO checkout_sessions (merchant_id, reference, amount_cents, currency, status, transaction_reference, "
                + "payment_method_code, commission_bps_applied, commission_amount_cents, net_amount_cents, expires_at, livemode, updated_at) VALUES ("
                + merchant + ", 'sess_" + uid() + "', " + gross + ", 'USD', 'PAID', '" + ref + "', 'ECOCASH', 250, " + commission + ", "
                + (gross - commission) + ", NOW(), " + live + ", '" + paidAt + "')");
    }

    private ShadowComparison.Row row() {
        return comparison.compare().stream().filter(r -> r.merchantId() == merchant && r.currency().equals("USD")).findFirst()
                .orElse(new ShadowComparison.Row(merchant, "USD", 0, 0, 0, 0));
    }

    private AccountKey payable() {
        return live(MERCHANT_PAYABLE, String.valueOf(merchant), "USD");
    }

    private void assertMatches(String step) {
        ShadowComparison.Row r = row();
        assertTrue(r.matches(), step + ": " + r);
    }

    private long openFailures(String like) {
        return queryLong(ds, "SELECT COUNT(*) FROM shadow_ledger_failures WHERE resolved_at IS NULL AND idempotency_key LIKE '" + like + "'");
    }

    // ── tests ───────────────────────────────────────────────────────────

    @Test
    void collectionRefundsAndSettlementAgreeWithLegacyAtEveryStep() throws Exception {
        long refundsOwedBefore = ledger.balance(live(CUSTOMER_REFUND_PAYABLE, "ALL", "USD")).balance();
        long session = paidSession(true, 10_001, 250, LocalDateTime.now().minusMinutes(1).toString().replace('T', ' '));
        sweeper.runOnce();
        assertEquals(9_751, ledger.balance(payable()).balance());
        assertMatches("after collection");

        insert("INSERT INTO refunds (checkout_session_id, amount_cents, reason, status, initiated_by_type, initiated_by_id) VALUES ("
                + session + ", 3333, 'part', 'COMPLETED', 'STAFF', 1)");
        update("UPDATE checkout_sessions SET status = 'PARTIALLY_REFUNDED', updated_at = NOW() WHERE id = " + session);
        sweeper.runOnce();
        assertEquals(PostingTemplates.legacyProRata(9_751, 10_001, 6_668), ledger.balance(payable()).balance());
        assertMatches("after first refund");

        insert("INSERT INTO refunds (checkout_session_id, amount_cents, reason, status, initiated_by_type, initiated_by_id) VALUES ("
                + session + ", 1111, 'more', 'COMPLETED', 'STAFF', 1)");
        sweeper.runOnce();
        long owed = PostingTemplates.legacyProRata(9_751, 10_001, 5_557);
        assertEquals(owed, ledger.balance(payable()).balance());
        assertMatches("after second refund");
        assertEquals(4_444, ledger.balance(live(CUSTOMER_REFUND_PAYABLE, "ALL", "USD")).balance() - refundsOwedBefore,
                "both refunds are now owed to the customer");

        long settlement = insert("INSERT INTO settlements (merchant_id, period_start, period_end, currency, session_count, gross_amount_cents, "
                + "net_amount_cents, status) VALUES (" + merchant + ", NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 1 DAY, 'USD', 1, 5557, " + owed + ", 'PROCESSING')");
        update("UPDATE checkout_sessions SET settlement_id = " + settlement + ", updated_at = NOW() WHERE id = " + session);
        sweeper.runOnce();
        assertEquals(0, ledger.balance(payable()).balance());
        assertEquals(owed, ledger.balance(live(SETTLEMENT_PAYABLE, String.valueOf(merchant), "USD")).balance());
        assertMatches("after settlement created");

        update("UPDATE settlements SET status = 'PAID', paid_at = NOW(), payout_reference = 'BANK-1' WHERE id = " + settlement);
        sweeper.runOnce();
        assertEquals(0, ledger.balance(live(SETTLEMENT_PAYABLE, String.valueOf(merchant), "USD")).balance());
        assertMatches("after settlement paid");

        assertEquals(0, openFailures("%:" + session), "no failures for this session");
        assertTrue(ledger.integrityReport().isEmpty(), () -> "integrity: " + ledger.integrityReport());
    }

    @Test
    void testModeSessionsPostToTheTestBookOnly() throws Exception {
        long session = paidSession(false, 5_000, 0, LocalDateTime.now().minusMinutes(1).toString().replace('T', ' '));
        sweeper.runOnce();
        assertEquals(0, ledger.balance(payable()).balance(), "nothing in the live book");
        assertEquals(5_000, ledger.balance(AccountKey.of(ENTITY, Book.TEST, MERCHANT_PAYABLE, merchant, "USD")).balance());
        assertMatches("test money is invisible to the live comparison");
        assertEquals(1, queryLong(ds, "SELECT COUNT(*) FROM ledger_entries WHERE source_type='session' AND source_ref='" + session + "' AND book='TEST'"));
    }

    @Test
    void rerunningPostsNothingTwice() throws Exception {
        long session = paidSession(true, 1_234, 34, LocalDateTime.now().minusMinutes(1).toString().replace('T', ' '));
        sweeper.runOnce();
        long entries = queryLong(ds, "SELECT COUNT(*) FROM ledger_entries");
        sweeper.runOnce();
        sweeper.resetWatermarks(LocalDateTime.now().minusDays(1)); // full rescan, as a backfill would
        ShadowLedgerSweeper.RunResult r = sweeper.runOnce();
        assertEquals(entries, queryLong(ds, "SELECT COUNT(*) FROM ledger_entries"), "rescans post nothing new: " + r);
        assertEquals(1_200, ledger.balance(payable()).balance());
        assertEquals(1, queryLong(ds, "SELECT COUNT(*) FROM ledger_entries WHERE source_type='session' AND source_ref='" + session + "'"));
    }

    @Test
    void consumerAirtimeWithVendorCommissionAndARefundedPurchase() throws Exception {
        String done = txRef(), refunded = txRef();
        long before = ledger.balance(live(COMMISSION_REVENUE, "ECONET", "USD")).balance();
        insert("INSERT INTO transactions (reference, status, channel, product_type_id, recipient_identifier, amount_cents, currency, "
                + "payment_provider_code, provider_reference, payment_confirmed_at, completed_at) SELECT '" + done + "', 'COMPLETED', 'WEB', id, "
                + "'+263771111111', 1000, 'USD', 'ONEMONEY', 'OM-" + uid() + "', NOW() - INTERVAL 2 MINUTE, NOW() - INTERVAL 1 MINUTE "
                + "FROM product_types WHERE code = 'AIRTIME_ECONET'");
        insert("INSERT INTO transactions (reference, status, channel, product_type_id, recipient_identifier, amount_cents, currency, "
                + "payment_provider_code, provider_reference, payment_confirmed_at, refunded_at) SELECT '" + refunded + "', 'REFUNDED', 'WEB', id, "
                + "'+263771111111', 700, 'USD', 'ECOCASH', 'EC-" + uid() + "', NOW() - INTERVAL 2 MINUTE, NOW() - INTERVAL 1 MINUTE "
                + "FROM product_types WHERE code = 'AIRTIME_ECONET'");
        sweeper.runOnce();
        assertEquals(30, ledger.balance(live(COMMISSION_REVENUE, "ECONET", "USD")).balance() - before, "3% of 1000");
        assertEquals(5, queryLong(ds, "SELECT COUNT(*) FROM ledger_entries WHERE source_type='transaction' AND source_ref IN ('" + done + "','" + refunded + "')"),
                "delivered: payment + fulfilment; refunded: payment + refund due + refund paid");
    }

    @Test
    void refundAfterSettlementIsCaughtAsAFailureAndShowsInTheComparison() throws Exception {
        long session = paidSession(true, 2_000, 0, LocalDateTime.now().minusMinutes(1).toString().replace('T', ' '));
        long settlement = insert("INSERT INTO settlements (merchant_id, period_start, period_end, currency, session_count, gross_amount_cents, "
                + "net_amount_cents, status) VALUES (" + merchant + ", NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 1 DAY, 'USD', 1, 2000, 2000, 'PROCESSING')");
        update("UPDATE checkout_sessions SET settlement_id = " + settlement + " WHERE id = " + session);
        sweeper.runOnce();
        assertMatches("settled");
        // The gateway lets a refund be recorded after the money went into a settlement.
        long refund = insert("INSERT INTO refunds (checkout_session_id, amount_cents, reason, status, initiated_by_type, initiated_by_id) VALUES ("
                + session + ", 500, 'late', 'COMPLETED', 'STAFF', 1)");
        sweeper.runOnce();
        assertEquals(1, openFailures("MERCHANT_REFUND:v1:refund:" + refund),
                "the ledger refuses to overdraw the merchant; legacy silently ignores the refund");
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT error_code FROM shadow_ledger_failures WHERE idempotency_key = ?")) {
            ps.setString(1, "MERCHANT_REFUND:v1:refund:" + refund);
            ResultSet rs = ps.executeQuery();
            rs.next();
            assertEquals("LEDGER_INSUFFICIENT_FUNDS", rs.getString(1));
        }
    }

    @Test
    void failedPostingIsRetriedAndResolvedOnceTheCauseIsFixed() throws Exception {
        String period = "2002-0" + (1 + (int) (Math.random() * 9));
        ledger.closePeriod(ENTITY, Book.LIVE, period, "test");
        long session;
        try {
            session = paidSession(true, 800, 0, period + "-15 10:00:00");
            update("UPDATE checkout_sessions SET updated_at = NOW() WHERE merchant_id = " + merchant);
            sweeper.runOnce();
            assertEquals(1, openFailures("MERCHANT_COLLECTION:v1:session:" + session));
        } finally {
            ledger.reopenPeriod(ENTITY, Book.LIVE, period, "test");
        }
        sweeper.runOnce();
        assertEquals(0, openFailures("MERCHANT_COLLECTION:v1:session:" + session), "retried and resolved");
        assertEquals(800, ledger.balance(payable()).balance());
    }
}
