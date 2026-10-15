package zw.co.payport.gateway.psp.ledger;

import org.junit.jupiter.api.Test;
import zw.co.payport.gateway.psp.ledger.domain.*;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static zw.co.payport.gateway.psp.ledger.domain.AccountCategory.*;

class LedgerDomainTest {

    // ── JournalDraft invariants ──────────────────────────────────────────

    @Test
    void unbalancedEntryIsRejected() {
        assertThrows(LedgerException.class, () -> JournalDraft.builder(EntryType.ADJUSTMENT, "k1", "USD")
                .debit(AccountKey.platform(SUSPENSE, "USD"), 100)
                .credit(AccountKey.platform(FEE_REVENUE, "USD"), 99)
                .build());
    }

    @Test
    void crossCurrencyLineIsRejected() {
        assertThrows(LedgerException.class, () -> JournalDraft.builder(EntryType.ADJUSTMENT, "k2", "USD")
                .debit(AccountKey.platform(SUSPENSE, "USD"), 100)
                .credit(AccountKey.platform(FEE_REVENUE, "ZWG"), 100)
                .build());
    }

    @Test
    void negativeAmountIsRejectedAndZeroLinesAreDropped() {
        assertThrows(LedgerException.class, () -> JournalDraft.builder(EntryType.ADJUSTMENT, "k3", "USD")
                .debit(AccountKey.platform(SUSPENSE, "USD"), -5));
        JournalDraft d = JournalDraft.builder(EntryType.ADJUSTMENT, "k4", "USD")
                .debit(AccountKey.platform(SUSPENSE, "USD"), 100)
                .credit(AccountKey.platform(FEE_REVENUE, "USD"), 100)
                .credit(AccountKey.tax("VAT", "USD"), 0)
                .build();
        assertEquals(2, d.lines().size());
    }

    @Test
    void entryNeedsBothSidesAndAKey() {
        assertThrows(LedgerException.class, () -> JournalDraft.builder(EntryType.ADJUSTMENT, "k5", "USD")
                .debit(AccountKey.platform(SUSPENSE, "USD"), 0).build());
        assertThrows(LedgerException.class, () -> JournalDraft.builder(EntryType.ADJUSTMENT, " ", "USD")
                .debit(AccountKey.platform(SUSPENSE, "USD"), 1)
                .credit(AccountKey.platform(FEE_REVENUE, "USD"), 1).build());
    }

    @Test
    void reversalMirrorsEveryLine() {
        JournalDraft d = PostingRules.merchantCollection("PP-1", 7, "ECOCASH", "USD",
                FeeCalculator.quote(FeeSchedule.percentOnly(250, "std"), 10_000));
        JournalDraft r = d.reversal("REVERSAL:x", "test");
        assertEquals(d.lines().size(), r.lines().size());
        for (int i = 0; i < d.lines().size(); i++) {
            assertEquals(d.lines().get(i).direction().opposite(), r.lines().get(i).direction());
            assertEquals(d.lines().get(i).amountMinor(), r.lines().get(i).amountMinor());
        }
    }

    @Test
    void zigAliasMapsToZwgAccount() {
        assertEquals("MERCHANT_AVAILABLE:9:ZWG", AccountKey.merchant(MERCHANT_AVAILABLE, 9, "zig").code());
        assertThrows(IllegalArgumentException.class, () -> AccountKey.merchant(MERCHANT_AVAILABLE, 9, "EUR"));
        assertThrows(IllegalArgumentException.class, () -> AccountKey.merchant(FEE_REVENUE, 9, "USD"));
    }

    @Test
    void accountTypeBalanceSign() {
        assertEquals(50, AccountType.LIABILITY.balanceOf(50, 100));
        assertEquals(-50, AccountType.ASSET.balanceOf(50, 100));
    }

    // ── Fees ─────────────────────────────────────────────────────────────

    @Test
    void percentFeeRoundsHalfUp() {
        FeeQuote q = FeeCalculator.quote(FeeSchedule.percentOnly(250, "std"), 1_234); // 2.5% of 12.34 = 0.3085
        assertEquals(31, q.feeMinor());
        assertEquals(1_203, q.netToMerchantMinor());
    }

    @Test
    void feeComponentsAlwaysAddUpToGross() {
        FeeSchedule s = new FeeSchedule(290, 30, 50, 2_000, 1_550, 200, 500, "agr-1");
        for (long gross : new long[]{0, 1, 49, 100, 999, 10_000, 1_000_000, 9_999_999}) {
            FeeQuote q = FeeCalculator.quote(s, gross);
            assertEquals(gross, q.feeMinor() + q.vatOnFeeMinor() + q.transferTaxMinor() + q.netToMerchantMinor(), "gross " + gross);
            assertTrue(q.netToMerchantMinor() >= 0);
        }
    }

    @Test
    void minMaxAndCapsApply() {
        FeeSchedule s = new FeeSchedule(100, 0, 50, 500, 0, 200, 300, "agr-2");
        assertEquals(50, FeeCalculator.quote(s, 1_000).feeMinor());          // 1% = 10 -> min 50
        assertEquals(500, FeeCalculator.quote(s, 1_000_000).feeMinor());     // 1% = 10000 -> cap 500
        assertEquals(300, FeeCalculator.quote(s, 1_000_000).transferTaxMinor()); // 2% capped at 300
    }

    @Test
    void tinyPaymentNeverGoesNegative() {
        FeeSchedule s = new FeeSchedule(0, 100, 0, 0, 1_500, 0, 0, "fixed");
        FeeQuote q = FeeCalculator.quote(s, 40);
        assertEquals(40, q.feeMinor());
        assertEquals(0, q.vatOnFeeMinor());
        assertEquals(0, q.netToMerchantMinor());
    }

    @Test
    void moneyHelpersRejectOverflowAndBadInput() {
        assertThrows(ArithmeticException.class, () -> Money.applyBps(Long.MAX_VALUE, 10_000));
        assertThrows(IllegalArgumentException.class, () -> Money.applyBps(100, 10_001));
        assertEquals(33, Money.proportion(100, 1, 3));
    }

    // ── Posting rules: every rule produces a balanced entry with a stable key ──

    @Test
    void everyPostingRuleIsBalancedAndKeyed() {
        FeeQuote q = FeeCalculator.quote(new FeeSchedule(250, 10, 0, 0, 1_500, 0, 0, "agr"), 10_000);
        List<JournalDraft> all = List.of(
                PostingRules.merchantCollection("PP-API-1", 1, "ECOCASH", "USD", q),
                PostingRules.consumerCollection("PP-WEB-1", "ONEMONEY", "ZWG", 5_000),
                PostingRules.consumerFulfilment("PP-WEB-1", "ESOLUTIONS", "ZWG", 5_000, 300),
                PostingRules.consumerRefundPaid("PP-WEB-2", "USD", 500, true, "ECOCASH_WALLET"),
                PostingRules.releaseFunds("PP-API-1", 1, "USD", q.netToMerchantMinor(), 500).draft(),
                PostingRules.releaseReserve("PP-API-1", 1, "USD", 100),
                PostingRules.merchantRefund("rf_1", 1, "USD", 300, 200),
                PostingRules.refundPayout("rf_1", "USD", 500, "TRUST_USD"),
                PostingRules.settlementReserved("si_1", 1, "USD", 9_000),
                PostingRules.settlementPaid("si_1", 1, "USD", 9_000, "TRUST_USD", 150, "CBZ"),
                PostingRules.settlementFailed("si_2", 1, "USD", 9_000),
                PostingRules.settlementReturnedAfterPayment("si_3", 1, "USD", 9_000, "TRUST_USD"),
                PostingRules.providerSettlementReceived("stmt-1", "ECOCASH", "USD", 100_000, 1_500, "TRUST_USD"),
                PostingRules.adjustment("brk_1", "USD", AccountKey.platform(SUSPENSE, "USD"),
                        AccountKey.merchant(MERCHANT_AVAILABLE, 1, "USD"), 10, "fix"),
                PostingRules.openingBalance(1, "USD", 12_345));
        for (JournalDraft d : all) {
            assertDoesNotThrow(d::validate);
            assertNotNull(d.idempotencyKey());
        }
        assertEquals("COLLECT:PP-API-1", all.get(0).idempotencyKey());
    }

    @Test
    void merchantCollectionSplitsGrossExactly() {
        FeeQuote q = FeeCalculator.quote(new FeeSchedule(250, 0, 0, 0, 1_500, 0, 0, "agr"), 10_000);
        JournalDraft d = PostingRules.merchantCollection("PP-API-9", 3, "ECOCASH", "USD", q);
        assertEquals(10_000, d.totalDebits());
        long toMerchant = d.lines().stream().filter(l -> l.account().category() == MERCHANT_PENDING).mapToLong(JournalDraft.Line::amountMinor).sum();
        long fee = d.lines().stream().filter(l -> l.account().category() == FEE_REVENUE).mapToLong(JournalDraft.Line::amountMinor).sum();
        long vat = d.lines().stream().filter(l -> l.account().category() == TAX_PAYABLE).mapToLong(JournalDraft.Line::amountMinor).sum();
        assertEquals(250, fee);
        assertEquals(38, vat);       // 15% of 250 = 37.5 -> 38
        assertEquals(9_712, toMerchant);
    }

    @Test
    void releaseKeepsReserveAndAvailableSummingToAmount() {
        PostingRules.Release r = PostingRules.releaseFunds("h", 1, "USD", 9_750, 500);
        assertEquals(488, r.toReserveMinor());
        assertEquals(9_262, r.toAvailableMinor());
        assertEquals(9_750, r.toAvailableMinor() + r.toReserveMinor());
    }

    @Test
    void adjustmentToSameAccountIsRejected() {
        AccountKey k = AccountKey.platform(SUSPENSE, "USD");
        assertThrows(LedgerException.class, () -> PostingRules.adjustment("x", "USD", k, k, 5, "no"));
    }

    // ── Hashing ──────────────────────────────────────────────────────────

    @Test
    void entryHashChangesWhenAnyLineChanges() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 15, 12, 0, 0);
        List<EntryHashing.HashLine> lines = List.of(
                new EntryHashing.HashLine(1, "A:1:USD", "DEBIT", 100),
                new EntryHashing.HashLine(2, "B:1:USD", "CREDIT", 100));
        String h1 = EntryHashing.hash("je_1", "k", "ADJUSTMENT", "USD", t, null, lines);
        String h2 = EntryHashing.hash("je_1", "k", "ADJUSTMENT", "USD", t, null, List.of(
                new EntryHashing.HashLine(1, "A:1:USD", "DEBIT", 101),
                new EntryHashing.HashLine(2, "B:1:USD", "CREDIT", 101)));
        assertNotEquals(h1, h2);
        assertEquals(h1, EntryHashing.hash("je_1", "k", "ADJUSTMENT", "USD", t.withNano(5), null, List.of(lines.get(1), lines.get(0))));
    }

    @Test
    void sealHashDependsOnPreviousSealAndEveryRow() {
        String a = ChainHasher.sealHash(ChainHasher.GENESIS, 1, 3, List.of("x", "y", "z"));
        assertNotEquals(a, ChainHasher.sealHash(ChainHasher.GENESIS, 1, 3, List.of("x", "z")));
        assertNotEquals(a, ChainHasher.sealHash("1".repeat(64), 1, 3, List.of("x", "y", "z")));
        assertNotEquals(ChainHasher.contentHash("a", null), ChainHasher.contentHash("a", ""));
    }
}
