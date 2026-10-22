package zw.co.payport.gateway.ledger.templates;

import zw.co.payport.gateway.ledger.AccountKey;
import zw.co.payport.gateway.ledger.Book;
import zw.co.payport.gateway.ledger.JournalRequest;

import java.time.LocalDate;
import java.util.Locale;

import static zw.co.payport.gateway.ledger.LedgerCategory.*;

/**
 * Versioned posting templates for gateway events (PHASE1_DESIGN.md section 5).
 *
 * Pure functions: same input, same journal request, same idempotency key. A changed rule
 * gets a new version constant; entries already posted keep the version that produced them.
 *
 * Merchant figures deliberately reproduce the legacy arithmetic (SettlementService /
 * CheckoutSessionService.balanceByCurrency: per-session pro-rating with Math.round on a
 * double ratio), so the shadow ledger agrees with the legacy figures by construction and
 * any difference the comparison report finds is a real one. Rounding differences between
 * independently rounded legacy figures are absorbed by FEE_REVENUE, never by a merchant.
 */
public final class PostingTemplates {

    public static final String MERCHANT_COLLECTION = "MERCHANT_COLLECTION";
    public static final String MERCHANT_REFUND = "MERCHANT_REFUND";
    public static final String SETTLEMENT_CREATED = "SETTLEMENT_CREATED";
    public static final String SETTLEMENT_PAID = "SETTLEMENT_PAID";
    public static final String CONSUMER_PAYMENT = "CONSUMER_PAYMENT";
    public static final String CONSUMER_FULFILMENT = "CONSUMER_FULFILMENT";
    public static final String CONSUMER_REFUND_DUE = "CONSUMER_REFUND_DUE";
    public static final String CONSUMER_REFUND_PAID = "CONSUMER_REFUND_PAID";
    public static final int V1 = 1;

    /** Product line owning merchant checkout fees. */
    public static final String FEE_LINE_CHECKOUT = "MERCHANT_CHECKOUT";
    /** Single owner for pooled customer liabilities. */
    public static final String ALL = "ALL";

    private static final String ACTOR = "SYSTEM:shadow-ledger";

    private PostingTemplates() {}

    // ── Inputs (snapshots of gateway rows) ─────────────────────────────

    /** A checkout session that reached PAID. fee/net are the values the gateway froze at payment time. */
    public record Collection(long sessionId, long merchantId, boolean livemode, String currency, long grossCents,
                             Long commissionCents, Long netCents, String rail, LocalDate paidOn) {}

    /**
     * A COMPLETED gateway refund. refundedBefore = the session's COMPLETED refunds with a
     * lower id (the legacy order); used to reproduce the legacy pro-rated figures exactly.
     */
    public record Refund(long refundId, long sessionId, long merchantId, boolean livemode, String currency, long sessionGrossCents,
                         Long sessionCommissionCents, Long sessionNetCents, long refundedBeforeCents, long amountCents, LocalDate refundedOn) {}

    public record Settlement(long settlementId, long merchantId, String currency, long netCents, LocalDate on) {}

    public record ConsumerPurchase(String reference, String currency, long amountCents, String rail, String vendor, LocalDate on) {}

    // ── Merchant checkout ───────────────────────────────────────────────

    /**
     * Dr PROVIDER_RECEIVABLE[rail] gross · Cr MERCHANT_PAYABLE[merchant] net · Cr FEE_REVENUE[checkout] gross - net.
     * VAT is not split out in v1: whether PayPort's commission is VAT-inclusive is a tax
     * question awaiting advice (PHASE1_DESIGN.md section 7); v2 will add the TAX_PAYABLE line.
     */
    public static JournalRequest merchantCollection(String entity, Collection c) {
        Book book = Book.of(c.livemode());
        long net = legacyNet(c.grossCents(), c.netCents());
        long fee = c.grossCents() - net;
        JournalRequest.Builder b = base(entity, book, MERCHANT_COLLECTION, "session", c.sessionId(), c.paidOn())
                .description("Checkout session " + c.sessionId() + " paid")
                .debit(acct(entity, book, PROVIDER_RECEIVABLE, rail(c.rail()), c.currency()), c.grossCents(), "gross collected")
                .credit(acct(entity, book, MERCHANT_PAYABLE, c.merchantId(), c.currency()), net, "owed to merchant");
        if (fee > 0) b.credit(acct(entity, book, FEE_REVENUE, FEE_LINE_CHECKOUT, c.currency()), fee, "processing fee");
        return b.build();
    }

    /**
     * Moves what the merchant is owed back towards the customer:
     * Dr MERCHANT_PAYABLE (legacy net before - legacy net after) · Dr FEE_REVENUE (rest of the refund)
     * · Cr CUSTOMER_REFUND_PAYABLE (refund amount).
     */
    public static JournalRequest merchantRefund(String entity, Refund r) {
        Book book = Book.of(r.livemode());
        long fullNet = legacyNet(r.sessionGrossCents(), r.sessionNetCents());
        long before = legacyProRata(fullNet, r.sessionGrossCents(), r.sessionGrossCents() - r.refundedBeforeCents());
        long after = legacyProRata(fullNet, r.sessionGrossCents(), r.sessionGrossCents() - r.refundedBeforeCents() - r.amountCents());
        long fromMerchant = before - after;
        long fromFee = r.amountCents() - fromMerchant;
        JournalRequest.Builder b = base(entity, book, MERCHANT_REFUND, "refund", r.refundId(), r.refundedOn())
                .description("Refund " + r.refundId() + " on checkout session " + r.sessionId())
                .credit(acct(entity, book, CUSTOMER_REFUND_PAYABLE, ALL, r.currency()), r.amountCents(), "owed to customer");
        if (fromMerchant > 0) b.debit(acct(entity, book, MERCHANT_PAYABLE, r.merchantId(), r.currency()), fromMerchant, "merchant share");
        if (fromFee > 0) b.debit(acct(entity, book, FEE_REVENUE, FEE_LINE_CHECKOUT, r.currency()), fromFee, "fee returned");
        if (fromFee < 0) b.credit(acct(entity, book, FEE_REVENUE, FEE_LINE_CHECKOUT, r.currency()), -fromFee, "rounding");
        return b.build();
    }

    /** Dr MERCHANT_PAYABLE · Cr SETTLEMENT_PAYABLE (legacy settlements are always live money). */
    public static JournalRequest settlementCreated(String entity, Settlement s) {
        return base(entity, Book.LIVE, SETTLEMENT_CREATED, "settlement", s.settlementId(), s.on())
                .description("Settlement " + s.settlementId() + " created")
                .debit(acct(entity, Book.LIVE, MERCHANT_PAYABLE, s.merchantId(), s.currency()), s.netCents(), "earmarked")
                .credit(acct(entity, Book.LIVE, SETTLEMENT_PAYABLE, s.merchantId(), s.currency()), s.netCents(), "to pay out")
                .build();
    }

    /** Dr SETTLEMENT_PAYABLE · Cr BANK_CASH[settlement account]. */
    public static JournalRequest settlementPaid(String entity, Settlement s, String bankAccountCode) {
        return base(entity, Book.LIVE, SETTLEMENT_PAID, "settlement", s.settlementId(), s.on())
                .description("Settlement " + s.settlementId() + " paid")
                .debit(acct(entity, Book.LIVE, SETTLEMENT_PAYABLE, s.merchantId(), s.currency()), s.netCents(), "paid out")
                .credit(acct(entity, Book.LIVE, BANK_CASH, bankAccountCode, s.currency()), s.netCents(), "bank payout")
                .build();
    }

    // ── Consumer purchases (airtime, data, bills) ──────────────────────

    /** Payment confirmed: Dr PROVIDER_RECEIVABLE[rail] · Cr CUSTOMER_ORDER_OBLIGATION. */
    public static JournalRequest consumerPayment(String entity, ConsumerPurchase p) {
        return base(entity, Book.LIVE, CONSUMER_PAYMENT, "transaction", p.reference(), p.on())
                .description("Consumer payment " + p.reference())
                .debit(acct(entity, Book.LIVE, PROVIDER_RECEIVABLE, rail(p.rail()), p.currency()), p.amountCents(), "collected")
                .credit(acct(entity, Book.LIVE, CUSTOMER_ORDER_OBLIGATION, ALL, p.currency()), p.amountCents(), "to deliver")
                .build();
    }

    /**
     * Delivered: Dr CUSTOMER_ORDER_OBLIGATION face · Cr VENDOR_FLOAT[vendor] face - commission
     * · Cr COMMISSION_REVENUE[vendor] commission (commission = face x bps / 10000, half-up).
     */
    public static JournalRequest consumerFulfilment(String entity, ConsumerPurchase p, int vendorCommissionBps) {
        long commission = bps(p.amountCents(), vendorCommissionBps);
        String vendor = owner(p.vendor());
        JournalRequest.Builder b = base(entity, Book.LIVE, CONSUMER_FULFILMENT, "transaction", p.reference(), p.on())
                .description("Consumer purchase " + p.reference() + " delivered")
                .debit(acct(entity, Book.LIVE, CUSTOMER_ORDER_OBLIGATION, ALL, p.currency()), p.amountCents(), "delivered")
                .credit(acct(entity, Book.LIVE, VENDOR_FLOAT, vendor, p.currency()), p.amountCents() - commission, "vendor float used");
        if (commission > 0) b.credit(acct(entity, Book.LIVE, COMMISSION_REVENUE, vendor, p.currency()), commission, "commission earned");
        return b.build();
    }

    /** Not delivered, refund owed: Dr CUSTOMER_ORDER_OBLIGATION · Cr CUSTOMER_REFUND_PAYABLE. */
    public static JournalRequest consumerRefundDue(String entity, ConsumerPurchase p) {
        return base(entity, Book.LIVE, CONSUMER_REFUND_DUE, "transaction", p.reference(), p.on())
                .description("Consumer purchase " + p.reference() + " to be refunded")
                .debit(acct(entity, Book.LIVE, CUSTOMER_ORDER_OBLIGATION, ALL, p.currency()), p.amountCents(), "not delivered")
                .credit(acct(entity, Book.LIVE, CUSTOMER_REFUND_PAYABLE, ALL, p.currency()), p.amountCents(), "refund owed")
                .build();
    }

    /** Refund paid back through the rail: Dr CUSTOMER_REFUND_PAYABLE · Cr PROVIDER_RECEIVABLE[rail]. */
    public static JournalRequest consumerRefundPaid(String entity, ConsumerPurchase p) {
        return base(entity, Book.LIVE, CONSUMER_REFUND_PAID, "transaction", p.reference(), p.on())
                .description("Consumer refund " + p.reference() + " paid")
                .debit(acct(entity, Book.LIVE, CUSTOMER_REFUND_PAYABLE, ALL, p.currency()), p.amountCents(), "refund paid")
                .credit(acct(entity, Book.LIVE, PROVIDER_RECEIVABLE, rail(p.rail()), p.currency()), p.amountCents(), "returned via rail")
                .build();
    }

    // ── Legacy arithmetic (reproduced exactly) ──────────────────────────

    /** Legacy: netAmountCents if frozen, else the gross (no commission applied). */
    public static long legacyNet(long grossCents, Long netCents) {
        return netCents != null ? netCents : grossCents;
    }

    /** Legacy: Math.round(full * ((double) remaining / gross)); 0 when gross is 0. Same expression, same result. */
    public static long legacyProRata(long full, long grossCents, long remainingCents) {
        if (remainingCents <= 0) return 0;
        double ratio = grossCents == 0 ? 0 : (double) remainingCents / grossCents;
        return Math.round(full * ratio);
    }

    /** amount x bps / 10000, rounded half-up, exact integer arithmetic. */
    public static long bps(long amount, int bps) {
        if (bps <= 0 || amount <= 0) return 0;
        return Math.addExact(Math.multiplyExact(amount, (long) bps), 5_000L) / 10_000L;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** The idempotency key: one financial effect per (template, version, source record). */
    public static String key(String template, String sourceType, Object sourceId) {
        return template + ":v" + V1 + ":" + sourceType + ":" + sourceId;
    }

    private static JournalRequest.Builder base(String entity, Book book, String template, String sourceType, Object sourceId, LocalDate on) {
        return JournalRequest.builder(key(template, sourceType, sourceId) + (book == Book.TEST ? ":test" : ""))
                .entity(entity).book(book).template(template, V1).effectiveDate(on == null ? LocalDate.now() : on)
                .source(sourceType, sourceId).postedBy(ACTOR);
    }

    private static AccountKey acct(String entity, Book book, zw.co.payport.gateway.ledger.LedgerCategory c, Object owner, String ccy) {
        return AccountKey.of(entity, book, c, owner(String.valueOf(owner)), ccy);
    }

    private static String rail(String rail) {
        return owner(rail == null || rail.isBlank() ? "UNKNOWN" : rail);
    }

    /** Account owners are upper-case, without ':' (reserved as the code separator). */
    private static String owner(String s) {
        return s == null || s.isBlank() ? "UNKNOWN" : s.trim().toUpperCase(Locale.ROOT).replace(':', '_');
    }
}
