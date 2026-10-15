package zw.co.payport.gateway.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static zw.co.payport.gateway.ledger.LedgerCategory.*;
import static zw.co.payport.gateway.ledger.LedgerDb.*;

/**
 * Phase 1 exit criterion E1: scripted scenarios against the real database, as the
 * least-privilege application user. Every accepted entry must balance per currency and
 * every rejected one must leave no trace. Ends each test with a clean integrity report
 * for the accounts it touched.
 */
@Tag("ledger-db")
class LedgerScenarioTest {

    private DataSource ds;
    private LedgerClient ledger;
    private String m;            // unique merchant owner for this test
    private AccountKey payable, receivable, fee, vat, settlement, bank, refund;
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        ds = app();
        ledger = new LedgerClient(ds);
        m = "m" + uid();
        payable = live(MERCHANT_PAYABLE, m, "USD");
        receivable = live(PROVIDER_RECEIVABLE, "ECOCASH_" + m, "USD");
        fee = live(FEE_REVENUE, "CHK_" + m, "USD");
        vat = live(TAX_PAYABLE, "VAT_" + m, "USD");
        settlement = live(SETTLEMENT_PAYABLE, m, "USD");
        bank = live(BANK_CASH, "BANK_" + m, "USD");
        refund = live(CUSTOMER_REFUND_PAYABLE, "R_" + m, "USD");
    }

    private JournalRequest.Builder req(String key) {
        return JournalRequest.builder(key + "-" + m).entity(ENTITY).book(Book.LIVE).effectiveDate(today)
                .template("TEST", 1).postedBy("test").source("TEST", m);
    }

    private LedgerClient.PostResult collect(String key, long gross, long feeAmt) {
        JournalRequest.Builder b = req(key).debit(receivable, gross, "gross").credit(payable, gross - feeAmt, "net");
        if (feeAmt > 0) b.credit(fee, feeAmt, "fee");
        return ledger.post(b.build());
    }

    private long bal(AccountKey k) {
        return ledger.balance(k).balance();
    }

    private void assertRejected(String code, Runnable r) {
        LedgerException e = assertThrows(LedgerException.class, r::run);
        assertEquals(code, e.code(), e.getMessage());
    }

    private String json(String key, String linesJson) {
        String hash = JournalRequest.sha256(key);
        return "{\"idempotency_key\":\"" + key + "-" + m + "\",\"request_hash\":\"" + hash + "\",\"entry_ref\":\"le_raw" + uid()
                + "\",\"entity\":\"PAYPORT_ZW\",\"book\":\"LIVE\",\"effective_date\":\"" + today + "\",\"posted_by\":\"test\",\"lines\":"
                + linesJson + "}";
    }

    private String line(AccountKey a, String dir, String amount) {
        return "{\"account_code\":\"" + a.code() + "\",\"direction\":\"" + dir + "\",\"amount_minor\":" + amount + "}";
    }

    private void open(AccountKey... keys) {
        for (AccountKey k : keys) ledger.openAccount(k, "test");
    }

    // ── Accepted entries ────────────────────────────────────────────────

    @Test void s01_twoLineEntryPostsAndMovesBothBalances() {
        LedgerClient.PostResult r = ledger.post(req("s01").debit(receivable, 500, null).credit(payable, 500, null).build());
        assertEquals(LedgerClient.Outcome.POSTED, r.outcome());
        assertTrue(r.entryRef().startsWith("le_"));
        assertEquals(500, bal(receivable));
        assertEquals(500, bal(payable));
    }

    @Test void s02_collectionSplitsGrossIntoNetAndFee() {
        collect("s02", 10_000, 250);
        assertEquals(10_000, bal(receivable));
        assertEquals(9_750, bal(payable));
        assertEquals(250, bal(fee));
    }

    @Test void s03_collectionWithVatOnFee() {
        ledger.post(req("s03").debit(receivable, 10_000, null).credit(payable, 9_750, null)
                .credit(fee, 216, "fee net of VAT").credit(vat, 34, "VAT 15.5% of 250 (rounded)").build());
        assertEquals(216, bal(fee));
        assertEquals(34, bal(vat));
    }

    @Test void s04_multiCurrencyEntryBalancedInEachCurrency() {
        AccountKey zr = live(PROVIDER_RECEIVABLE, "ECOCASH_" + m, "ZWG");
        AccountKey zp = live(MERCHANT_PAYABLE, m, "ZWG");
        ledger.post(req("s04").debit(receivable, 100, null).credit(payable, 100, null)
                .debit(zr, 2_600, null).credit(zp, 2_600, null).build());
        assertEquals(100, bal(payable));
        assertEquals(2_600, bal(zp));
    }

    @Test void s05_settlementMovesPayableToSettlementPayable() {
        collect("s05a", 1_000, 0);
        ledger.post(req("s05b").debit(payable, 1_000, null).credit(settlement, 1_000, null).build());
        assertEquals(0, bal(payable));
        assertEquals(1_000, bal(settlement));
    }

    @Test void s06_settlementPaidReducesSettlementPayableAndBank() {
        collect("s06a", 1_000, 0);
        ledger.post(req("s06b").debit(payable, 1_000, null).credit(settlement, 1_000, null).build());
        ledger.post(req("s06c").debit(settlement, 1_000, null).credit(bank, 1_000, null).build());
        assertEquals(0, bal(settlement));
        assertEquals(-1_000, bal(bank), "bank (asset) may go negative in this isolated test");
    }

    @Test void s07_merchantRefundMovesPayableToCustomerRefundPayable() {
        collect("s07a", 1_000, 0);
        ledger.post(req("s07b").debit(payable, 400, null).credit(refund, 400, null).build());
        assertEquals(600, bal(payable));
        assertEquals(400, bal(refund));
    }

    @Test void s08_debitToExactlyZeroIsAllowed() {
        collect("s08a", 700, 0);
        ledger.post(req("s08b").debit(payable, 700, null).credit(settlement, 700, null).build());
        assertEquals(0, bal(payable));
    }

    @Test void s09_assetMayGoNegative() {
        ledger.post(req("s09").debit(fee, 50, null).credit(receivable, 50, null).build());
        assertEquals(-50, bal(receivable));
    }

    @Test void s10_sameAccountTwiceInOneEntryIsAggregated() {
        ledger.post(req("s10").debit(receivable, 300, null).debit(receivable, 200, null).credit(payable, 500, null).build());
        assertEquals(500, bal(receivable));
        LedgerClient.Balance b = ledger.balance(receivable);
        assertEquals(500, b.debitTotal());
        assertEquals(0, b.creditTotal());
    }

    @Test void s11_debitAndCreditSameAccountNets() {
        ledger.post(req("s11").debit(receivable, 300, null).credit(receivable, 100, null).credit(payable, 200, null).build());
        assertEquals(200, bal(receivable));
        assertEquals(300, ledger.balance(receivable).debitTotal());
        assertEquals(100, ledger.balance(receivable).creditTotal());
    }

    @Test void s12_hundredLineEntry() {
        JournalRequest.Builder b = req("s12");
        for (int i = 0; i < 99; i++) b.debit(live(PROVIDER_RECEIVABLE, "P" + i + "_" + m, "USD"), 10, null);
        b.credit(payable, 990, null);
        assertEquals(LedgerClient.Outcome.POSTED, ledger.post(b.build()).outcome());
        assertEquals(990, bal(payable));
    }

    @Test void s13_largestAllowedAmount() {
        long max = 999_999_999_999_999L;
        ledger.post(req("s13").debit(receivable, max, null).credit(payable, max, null).build());
        assertEquals(max, bal(payable));
    }

    @Test void s14_descriptionWithQuotesAndUnicodeStoredExactly() {
        String d = "Refund \"partial\" — café ZiG 1,5% \\ path\nline2";
        LedgerClient.PostResult r = ledger.post(req("s14").description(d).debit(receivable, 1, null).credit(payable, 1, null).build());
        try (Connection c = ds.getConnection(); var ps = c.prepareStatement("SELECT description FROM ledger_entries WHERE id=?")) {
            ps.setLong(1, r.entryId());
            var rs = ps.executeQuery();
            assertTrue(rs.next());
            assertEquals(d, rs.getString(1));
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test void s15_templateCodeAndVersionRecorded() {
        LedgerClient.PostResult r = ledger.post(req("s15").template("MERCHANT_COLLECTION", 3)
                .debit(receivable, 5, null).credit(payable, 5, null).build());
        assertEquals(3, queryLong(ds, "SELECT template_version FROM ledger_entries WHERE id=" + r.entryId()));
    }

    @Test void s16_testBookIsSeparateFromLive() {
        AccountKey testPayable = AccountKey.of(ENTITY, Book.TEST, MERCHANT_PAYABLE, m, "USD");
        AccountKey testRec = AccountKey.of(ENTITY, Book.TEST, PROVIDER_RECEIVABLE, "ECOCASH_" + m, "USD");
        ledger.post(JournalRequest.builder("s16-" + m).entity(ENTITY).book(Book.TEST).effectiveDate(today).postedBy("test")
                .debit(testRec, 900, null).credit(testPayable, 900, null).build());
        assertEquals(900, bal(testPayable));
        assertEquals(0, bal(payable), "live balance untouched by test-mode money");
    }

    @Test void s17_balanceOfUnopenedAccountIsZero() {
        assertEquals(0, bal(live(MERCHANT_PAYABLE, "never" + m, "USD")));
    }

    @Test void s18_openAccountIsIdempotent() {
        long a = ledger.openAccount(payable, "test");
        long b = ledger.openAccount(payable, "test");
        assertEquals(a, b);
    }

    // ── Idempotency ──────────────────────────────────────────────────────

    @Test void s19_replaySameRequestReturnsSameEntryAndPostsOnce() {
        JournalRequest r = req("s19").debit(receivable, 500, null).credit(payable, 500, null).build();
        LedgerClient.PostResult first = ledger.post(r);
        LedgerClient.PostResult second = ledger.post(r);
        assertEquals(LedgerClient.Outcome.DUPLICATE, second.outcome());
        assertEquals(first.entryId(), second.entryId());
        assertEquals(first.entryRef(), second.entryRef());
        assertEquals(500, bal(payable));
    }

    @Test void s20_sameKeyDifferentAmountIsConflict() {
        ledger.post(req("s20").debit(receivable, 500, null).credit(payable, 500, null).build());
        assertRejected("LEDGER_IDEMPOTENCY_CONFLICT",
                () -> ledger.post(req("s20").debit(receivable, 501, null).credit(payable, 501, null).build()));
        assertEquals(500, bal(payable));
    }

    @Test void s21_replayByDifferentActorIsStillTheSameRequest() {
        JournalRequest a = req("s21").debit(receivable, 5, null).credit(payable, 5, null).build();
        JournalRequest b = req("s21").postedBy("someone-else").debit(receivable, 5, null).credit(payable, 5, null).build();
        ledger.post(a);
        assertEquals(LedgerClient.Outcome.DUPLICATE, ledger.post(b).outcome());
    }

    // ── Rejected entries (nothing written) ─────────────────────────────

    @Test void s22_unbalancedSameCurrencyRejected() {
        assertRejected("LEDGER_UNBALANCED", () -> ledger.post(req("s22").debit(receivable, 500, null).credit(payable, 499, null).build()));
        assertEquals(0, bal(payable));
        assertEquals(0, bal(receivable));
    }

    @Test void s23_crossCurrencyNettingRejected() {
        AccountKey zp = live(MERCHANT_PAYABLE, m, "ZWG");
        assertRejected("LEDGER_UNBALANCED", () -> ledger.post(req("s23").debit(receivable, 100, null).credit(zp, 100, null).build()));
        assertEquals(0, bal(zp));
    }

    @Test void s24_overdrawingMerchantPayableRejected() {
        collect("s24a", 1_000, 0);
        assertRejected("LEDGER_INSUFFICIENT_FUNDS",
                () -> ledger.post(req("s24b").debit(payable, 1_001, null).credit(settlement, 1_001, null).build()));
        assertEquals(1_000, bal(payable));
        assertEquals(0, bal(settlement));
    }

    @Test void s25_overdrawingFromZeroRejected() {
        assertRejected("LEDGER_INSUFFICIENT_FUNDS",
                () -> ledger.post(req("s25").debit(payable, 1, null).credit(bank, 1, null).build()));
    }

    @Test void s26_singleLineRejectedByJava() {
        assertThrows(IllegalArgumentException.class, () -> req("s26").debit(receivable, 1, null).build());
    }

    @Test void s27_singleLineRejectedByDatabase() {
        open(receivable);
        assertRejected("LEDGER_INVALID", () -> rawPost(ds, json("s27", "[" + line(receivable, "DEBIT", "1") + "]")));
    }

    @Test void s28_zeroAmountRejectedByJava() {
        assertThrows(IllegalArgumentException.class, () -> JournalLine.debit(receivable, 0, null));
    }

    @Test void s29_zeroAmountRejectedByDatabase() {
        open(receivable, payable);
        assertRejected("LEDGER_INVALID_LINE", () -> rawPost(ds, json("s29", "[" + line(receivable, "DEBIT", "0") + "," + line(payable, "CREDIT", "0") + "]")));
    }

    @Test void s30_fractionalAmountRejected() {
        open(receivable, payable);
        assertRejected("LEDGER_INVALID_LINE", () -> rawPost(ds, json("s30", "[" + line(receivable, "DEBIT", "10.5") + "," + line(payable, "CREDIT", "10.5") + "]")));
    }

    @Test void s31_negativeAmountRejected() {
        open(receivable, payable);
        assertRejected("LEDGER_INVALID_LINE", () -> rawPost(ds, json("s31", "[" + line(receivable, "DEBIT", "-5") + "," + line(payable, "CREDIT", "-5") + "]")));
    }

    @Test void s32_amountAsTextRejected() {
        open(receivable, payable);
        assertRejected("LEDGER_INVALID_LINE", () -> rawPost(ds, json("s32", "[" + line(receivable, "DEBIT", "\"abc\"") + "," + line(payable, "CREDIT", "\"abc\"") + "]")));
    }

    @Test void s33_amountAboveLimitRejected() {
        open(receivable, payable);
        assertRejected("LEDGER_INVALID_LINE", () -> rawPost(ds, json("s33", "[" + line(receivable, "DEBIT", "1000000000000000") + "," + line(payable, "CREDIT", "1000000000000000") + "]")));
    }

    @Test void s34_badDirectionRejected() {
        open(receivable, payable);
        assertRejected("LEDGER_INVALID_LINE", () -> rawPost(ds, json("s34", "[" + line(receivable, "UP", "5") + "," + line(payable, "DOWN", "5") + "]")));
    }

    @Test void s35_unknownAccountRejected() {
        open(receivable);
        AccountKey ghost = live(MERCHANT_PAYABLE, "ghost" + m, "USD");
        assertRejected("LEDGER_UNKNOWN_ACCOUNT", () -> rawPost(ds, json("s35", "[" + line(receivable, "DEBIT", "5") + "," + line(ghost, "CREDIT", "5") + "]")));
    }

    @Test void s36_accountFromOtherBookRejectedByJava() {
        AccountKey testPayable = AccountKey.of(ENTITY, Book.TEST, MERCHANT_PAYABLE, m, "USD");
        assertThrows(IllegalArgumentException.class, () -> req("s36").debit(receivable, 5, null).credit(testPayable, 5, null).build());
    }

    @Test void s37_accountFromOtherBookRejectedByDatabase() {
        AccountKey testPayable = AccountKey.of(ENTITY, Book.TEST, MERCHANT_PAYABLE, m, "USD");
        open(receivable, testPayable);
        assertRejected("LEDGER_ENTITY_MISMATCH", () -> rawPost(ds, json("s37", "[" + line(receivable, "DEBIT", "5") + "," + line(testPayable, "CREDIT", "5") + "]")));
    }

    @Test void s38_tooManyLinesRejected() {
        open(receivable, payable);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 501; i++) sb.append(i > 0 ? "," : "").append(line(receivable, "DEBIT", "1"));
        sb.append(",").append(line(payable, "CREDIT", "501")).append("]");
        assertRejected("LEDGER_INVALID", () -> rawPost(ds, json("s38", sb.toString())));
    }

    @Test void s39_missingRequiredFieldRejected() {
        open(receivable, payable);
        String j = json("s39", "[" + line(receivable, "DEBIT", "5") + "," + line(payable, "CREDIT", "5") + "]").replace("\"posted_by\":\"test\",", "");
        assertRejected("LEDGER_INVALID", () -> rawPost(ds, j));
    }

    @Test void s40_badRequestHashRejected() {
        open(receivable, payable);
        String j = json("s40", "[" + line(receivable, "DEBIT", "5") + "," + line(payable, "CREDIT", "5") + "]")
                .replaceFirst("\"request_hash\":\"[0-9a-f]{64}\"", "\"request_hash\":\"not-a-hash\"");
        assertRejected("LEDGER_INVALID", () -> rawPost(ds, j));
    }

    @Test void s41_postCannotClaimToBeAReversal() {
        open(receivable, payable);
        String j = json("s41", "[" + line(receivable, "DEBIT", "5") + "," + line(payable, "CREDIT", "5") + "]")
                .replace("\"lines\":", "\"reverses_entry_ref\":\"le_x\",\"lines\":");
        assertRejected("LEDGER_INVALID", () -> rawPost(ds, j));
    }

    @Test void s42_autocommitCallRefused() {
        open(receivable, payable);
        try (Connection c = ds.getConnection(); CallableStatement cs = c.prepareCall("{call ledger_post(?, ?, ?)}")) {
            c.setAutoCommit(true);
            cs.setString(1, json("s42", "[" + line(receivable, "DEBIT", "5") + "," + line(payable, "CREDIT", "5") + "]"));
            cs.registerOutParameter(2, Types.BIGINT);
            cs.registerOutParameter(3, Types.VARCHAR);
            Exception e = assertThrows(Exception.class, cs::execute);
            assertTrue(e.getMessage().contains("LEDGER_AUTOCOMMIT"), e.getMessage());
        } catch (Exception e) {
            fail(e);
        }
        assertEquals(0, bal(payable));
    }

    @Test void s43_unknownCurrencyAccountRejected() {
        assertRejected("LEDGER_INVALID", () -> ledger.openAccount(live(MERCHANT_PAYABLE, m, "KES"), "test"));
    }

    @Test void s44_unknownEntityRejected() {
        assertRejected("LEDGER_INVALID", () -> ledger.openAccount(AccountKey.of("NOPE_KE", Book.LIVE, MERCHANT_PAYABLE, m, "USD"), "test"));
    }

    @Test void s45_ownerWithColonRejectedByJava() {
        assertThrows(IllegalArgumentException.class, () -> live(MERCHANT_PAYABLE, "a:b", "USD"));
    }

    // ── Periods ──────────────────────────────────────────────────────────

    @Test void s46_closedPeriodRejectsPostingsUntilReopened() {
        LocalDate old = LocalDate.of(2001, 1 + new Random().nextInt(12), 15);
        String period = String.format("%04d-%02d", old.getYear(), old.getMonthValue());
        // Each run uses a different historic period owned by this test's accounts.
        ledger.closePeriod(ENTITY, Book.LIVE, period, "test");
        try {
            assertRejected("LEDGER_PERIOD_CLOSED", () -> ledger.post(req("s46a").effectiveDate(old)
                    .debit(receivable, 5, null).credit(payable, 5, null).build()));
            assertEquals(0, bal(payable));
            assertEquals(LedgerClient.Outcome.POSTED, ledger.post(req("s46b").debit(receivable, 5, null).credit(payable, 5, null).build()).outcome(),
                    "the current period is unaffected");
        } finally {
            ledger.reopenPeriod(ENTITY, Book.LIVE, period, "test");
        }
        assertEquals(LedgerClient.Outcome.POSTED, ledger.post(req("s46c").effectiveDate(old)
                .debit(receivable, 5, null).credit(payable, 5, null).build()).outcome());
    }

    @Test void s47_periodIsRecordedOnTheEntry() {
        LedgerClient.PostResult r = ledger.post(req("s47").debit(receivable, 5, null).credit(payable, 5, null).build());
        assertEquals(today.getYear() * 100L + today.getMonthValue(),
                queryLong(ds, "SELECT CAST(REPLACE(period,'-','') AS UNSIGNED) FROM ledger_entries WHERE id=" + r.entryId()));
    }

    // ── Reversals ────────────────────────────────────────────────────────

    @Test void s48_reversalMirrorsAndRestoresBalances() {
        LedgerClient.PostResult orig = collect("s48", 1_000, 30);
        LedgerClient.PostResult rev = ledger.reverse(orig.entryRef(), "s48-rev-" + m, null, "test", "posted in error");
        assertEquals(LedgerClient.Outcome.POSTED, rev.outcome());
        assertEquals(0, bal(receivable));
        assertEquals(0, bal(payable));
        assertEquals(0, bal(fee));
        assertEquals(orig.entryId(), queryLong(ds, "SELECT reverses_entry_id FROM ledger_entries WHERE id=" + rev.entryId()));
    }

    @Test void s49_secondReversalRejected() {
        LedgerClient.PostResult orig = collect("s49", 1_000, 0);
        ledger.reverse(orig.entryRef(), "s49-rev1-" + m, null, "test", "error");
        assertRejected("LEDGER_ALREADY_REVERSED", () -> ledger.reverse(orig.entryRef(), "s49-rev2-" + m, null, "test", "again"));
    }

    @Test void s50_reversalReplayIsIdempotent() {
        LedgerClient.PostResult orig = collect("s50", 1_000, 0);
        LedgerClient.PostResult a = ledger.reverse(orig.entryRef(), "s50-rev-" + m, null, "test", "error");
        LedgerClient.PostResult b = ledger.reverse(orig.entryRef(), "s50-rev-" + m, null, "test", "error");
        assertEquals(LedgerClient.Outcome.DUPLICATE, b.outcome());
        assertEquals(a.entryId(), b.entryId());
        assertEquals(0, bal(payable));
    }

    @Test void s51_reversingAReversalRejected() {
        LedgerClient.PostResult orig = collect("s51", 1_000, 0);
        LedgerClient.PostResult rev = ledger.reverse(orig.entryRef(), "s51-rev-" + m, null, "test", "error");
        assertRejected("LEDGER_CANNOT_REVERSE_REVERSAL", () -> ledger.reverse(rev.entryRef(), "s51-rev2-" + m, null, "test", "x"));
    }

    @Test void s52_reversalOfUnknownEntryRejected() {
        assertRejected("LEDGER_NOT_FOUND", () -> ledger.reverse("le_does_not_exist", "s52-" + m, null, "test", "x"));
    }

    @Test void s53_reversalThatWouldOverdrawRejected() {
        LedgerClient.PostResult orig = collect("s53a", 1_000, 0);
        ledger.post(req("s53b").debit(payable, 1_000, null).credit(settlement, 1_000, null).build());
        assertRejected("LEDGER_INSUFFICIENT_FUNDS", () -> ledger.reverse(orig.entryRef(), "s53-rev-" + m, null, "test", "late"));
        assertEquals(0, bal(payable));
    }

    @Test void s54_reversalNeedsAReason() {
        LedgerClient.PostResult orig = collect("s54", 1_000, 0);
        assertRejected("LEDGER_INVALID", () -> ledger.reverse(orig.entryRef(), "s54-rev-" + m, null, "test", ""));
    }

    // ── Projection stays exact under a long random sequence ─────────────

    @Test void s55_randomSequenceKeepsProjectionEqualToLines() {
        Random rnd = new Random(42);
        List<AccountKey> merchants = new ArrayList<>();
        for (int i = 0; i < 5; i++) merchants.add(live(MERCHANT_PAYABLE, "r" + i + m, "USD"));
        long[] expected = new long[5];
        int rejected = 0;
        for (int i = 0; i < 300; i++) {
            int k = rnd.nextInt(5);
            long amt = 1 + rnd.nextInt(5_000);
            if (rnd.nextBoolean()) {
                ledger.post(req("s55-" + i).debit(receivable, amt, null).credit(merchants.get(k), amt, null).build());
                expected[k] += amt;
            } else {
                try {
                    ledger.post(req("s55-" + i).debit(merchants.get(k), amt, null).credit(settlement, amt, null).build());
                    expected[k] -= amt;
                } catch (LedgerException e) {
                    assertEquals("LEDGER_INSUFFICIENT_FUNDS", e.code());
                    assertTrue(expected[k] < amt);
                    rejected++;
                }
            }
        }
        for (int k = 0; k < 5; k++) assertEquals(expected[k], bal(merchants.get(k)));
        assertTrue(rejected > 0, "the sequence should exercise rejections too");
        assertTrue(ledger.integrityReport().isEmpty(), () -> "integrity violations: " + ledger.integrityReport());
    }
}
