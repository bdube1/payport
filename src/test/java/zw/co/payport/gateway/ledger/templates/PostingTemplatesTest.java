package zw.co.payport.gateway.ledger.templates;

import org.junit.jupiter.api.Test;
import zw.co.payport.gateway.ledger.Book;
import zw.co.payport.gateway.ledger.Direction;
import zw.co.payport.gateway.ledger.JournalLine;
import zw.co.payport.gateway.ledger.JournalRequest;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Pure tests: every template balances per currency and reproduces the legacy arithmetic. */
class PostingTemplatesTest {

    private static final String E = "PAYPORT_ZW";
    private static final LocalDate D = LocalDate.of(2026, 10, 15);

    private static void assertBalanced(JournalRequest r) {
        Map<String, Long> net = new HashMap<>();
        for (JournalLine l : r.lines()) {
            net.merge(l.account().currency(), l.direction() == Direction.DEBIT ? l.amountMinor() : -l.amountMinor(), Long::sum);
        }
        net.forEach((ccy, n) -> assertEquals(0L, n, "unbalanced in " + ccy + ": " + r));
    }

    @Test
    void legacyProRataIsTheSameExpressionAsSettlementService() {
        Random rnd = new Random(7);
        for (int i = 0; i < 100_000; i++) {
            long gross = 1 + rnd.nextInt(10_000_000);
            long full = rnd.nextInt((int) Math.min(gross, Integer.MAX_VALUE)) + 1;
            long remaining = 1 + (long) (rnd.nextDouble() * gross);
            double ratio = (double) remaining / gross;           // SettlementService.runForMerchant
            assertEquals(Math.round(full * ratio), PostingTemplates.legacyProRata(full, gross, remaining));
        }
        assertEquals(0, PostingTemplates.legacyProRata(100, 100, 0));
    }

    @Test
    void collectionSplitsGrossIntoNetAndFeeAndBalances() {
        JournalRequest r = PostingTemplates.merchantCollection(E, new PostingTemplates.Collection(1, 7, true, "USD", 10_001, 250L, 9_751L, "ecocash", D));
        assertBalanced(r);
        assertEquals("MERCHANT_COLLECTION:v1:session:1", r.idempotencyKey());
        assertEquals(Book.LIVE, r.book());
        assertEquals(3, r.lines().size());
    }

    @Test
    void collectionWithoutCommissionHasNoFeeLine() {
        JournalRequest r = PostingTemplates.merchantCollection(E, new PostingTemplates.Collection(2, 7, true, "USD", 500, null, null, "OMARI", D));
        assertBalanced(r);
        assertEquals(2, r.lines().size());
    }

    @Test
    void testModeGoesToTestBookWithItsOwnKey() {
        JournalRequest r = PostingTemplates.merchantCollection(E, new PostingTemplates.Collection(3, 7, false, "USD", 500, 0L, 500L, "TEST_MODE", D));
        assertEquals(Book.TEST, r.book());
        assertTrue(r.idempotencyKey().endsWith(":test"));
    }

    @Test
    void refundsBalanceAndTakeExactlyTheLegacyReductionFromTheMerchant() {
        Random rnd = new Random(11);
        for (int i = 0; i < 20_000; i++) {
            long gross = 2 + rnd.nextInt(5_000_000);
            long commission = rnd.nextInt((int) Math.max(1, gross / 10));
            long net = gross - commission;
            long before = rnd.nextInt((int) (gross - 1));
            long amount = 1 + rnd.nextInt((int) (gross - before));
            JournalRequest r = PostingTemplates.merchantRefund(E, new PostingTemplates.Refund(i, 1, 7, true, "USD", gross, commission, net, before, amount, D));
            assertBalanced(r);
            long merchantDebit = r.lines().stream().filter(l -> l.account().category().name().equals("MERCHANT_PAYABLE"))
                    .mapToLong(JournalLine::amountMinor).sum();
            long expected = PostingTemplates.legacyProRata(net, gross, gross - before) - PostingTemplates.legacyProRata(net, gross, gross - before - amount);
            assertEquals(expected, merchantDebit);
        }
    }

    @Test
    void settlementTemplatesBalance() {
        PostingTemplates.Settlement s = new PostingTemplates.Settlement(9, 7, "ZWG", 12_345, D);
        assertBalanced(PostingTemplates.settlementCreated(E, s));
        assertBalanced(PostingTemplates.settlementPaid(E, s, "SETTLEMENT_ZWG"));
        assertNotEquals(PostingTemplates.settlementCreated(E, s).idempotencyKey(), PostingTemplates.settlementPaid(E, s, "B").idempotencyKey());
    }

    @Test
    void consumerTemplatesBalanceAndCommissionRoundsHalfUp() {
        PostingTemplates.ConsumerPurchase p = new PostingTemplates.ConsumerPurchase("PP-WEB-1", "USD", 1_050, "ECOCASH", "econet", D);
        assertBalanced(PostingTemplates.consumerPayment(E, p));
        assertBalanced(PostingTemplates.consumerFulfilment(E, p, 250));
        assertBalanced(PostingTemplates.consumerRefundDue(E, p));
        assertBalanced(PostingTemplates.consumerRefundPaid(E, p));
        assertEquals(26, PostingTemplates.bps(1_050, 250), "26.25 -> 26");
        assertEquals(27, PostingTemplates.bps(1_060, 250), "26.5 -> 27 (half up)");
        assertEquals(0, PostingTemplates.bps(1_000, 0));
    }

    @Test
    void ownersAreNormalisedSoAccountsAreStable() {
        JournalRequest a = PostingTemplates.consumerPayment(E, new PostingTemplates.ConsumerPurchase("R1", "USD", 10, "ecocash", "x", D));
        JournalRequest b = PostingTemplates.consumerPayment(E, new PostingTemplates.ConsumerPurchase("R2", "USD", 10, " ECOCASH ", "x", D));
        assertEquals(a.lines().get(0).account(), b.lines().get(0).account());
        JournalRequest u = PostingTemplates.consumerPayment(E, new PostingTemplates.ConsumerPurchase("R3", "USD", 10, null, "x", D));
        assertEquals("UNKNOWN", u.lines().get(0).account().owner());
    }

    @Test
    void sameInputGivesSameRequestHash() {
        PostingTemplates.Collection c = new PostingTemplates.Collection(1, 7, true, "USD", 100, 1L, 99L, "ECOCASH", D);
        assertEquals(PostingTemplates.merchantCollection(E, c).requestHash(), PostingTemplates.merchantCollection(E, c).requestHash());
    }
}
