package zw.co.payport.gateway.psp.ledger.domain;

import static zw.co.payport.gateway.psp.ledger.domain.AccountCategory.*;

/**
 * The one place that decides which accounts move for each business event.
 * Every method returns a validated, balanced {@link JournalDraft} with a
 * deterministic idempotency key derived from the business reference, so
 * replaying the same event (duplicate callback, retried job, double click)
 * can never post twice.
 *
 * Money flow for a merchant collection (example: $100, 2.5% fee, 7-day reserve 5%):
 *
 *   collected:   Dr PROVIDER_RECEIVABLE[ECOCASH] 100.00
 *                  Cr MERCHANT_PENDING[m]          97.50
 *                  Cr FEE_REVENUE                   2.50
 *   released:    Dr MERCHANT_PENDING[m]          97.50
 *   (T+N)          Cr MERCHANT_AVAILABLE[m]        92.62
 *                  Cr MERCHANT_RESERVE[m]           4.88
 *   settled:     Dr MERCHANT_AVAILABLE[m]        92.62
 *   (batch)        Cr SETTLEMENT_PAYABLE[m]        92.62
 *   paid:        Dr SETTLEMENT_PAYABLE[m]        92.62
 *   (proof)        Cr PLATFORM_CASH[TRUST_USD]     92.62
 *   rail pays:   Dr PLATFORM_CASH[TRUST_USD]     99.00
 *                Dr PROVIDER_FEE_EXPENSE[ECOCASH] 1.00
 *                  Cr PROVIDER_RECEIVABLE[ECOCASH] 100.00
 */
public final class PostingRules {

    public static final String TAX_VAT = "VAT";
    public static final String TAX_TRANSFER = "IMTT";

    private PostingRules() {}

    // ── Collections ────────────────────────────────────────────────────────

    /** A payment for a merchant has been confirmed by the rail. Funds land in PENDING, not AVAILABLE. */
    public static JournalDraft merchantCollection(String paymentRef, long merchantId, String providerCode,
                                                  String currency, FeeQuote q) {
        return JournalDraft.builder(EntryType.PAYMENT_COLLECTED_MERCHANT, "COLLECT:" + paymentRef, currency)
                .description("Merchant collection " + paymentRef + " via " + providerCode
                        + (q.agreementRef() != null ? " (fees: " + q.agreementRef() + ")" : ""))
                .source("TRANSACTION", paymentRef)
                .debit(AccountKey.provider(PROVIDER_RECEIVABLE, providerCode, currency), q.grossMinor(), "Gross collected")
                .credit(AccountKey.merchant(MERCHANT_PENDING, merchantId, currency), q.netToMerchantMinor(), "Net to merchant")
                .credit(AccountKey.platform(FEE_REVENUE, currency), q.feeMinor(), "Processing fee")
                .credit(AccountKey.tax(TAX_VAT, currency), q.vatOnFeeMinor(), "VAT on fee")
                .credit(AccountKey.tax(TAX_TRANSFER, currency), q.transferTaxMinor(), "Transfer tax")
                .build();
    }

    /** A consumer has paid for a bill / airtime / data product that has not been delivered yet. */
    public static JournalDraft consumerCollection(String paymentRef, String providerCode, String currency, long grossMinor) {
        return JournalDraft.builder(EntryType.PAYMENT_COLLECTED_CONSUMER, "COLLECT:" + paymentRef, currency)
                .description("Consumer payment " + paymentRef + " via " + providerCode)
                .source("TRANSACTION", paymentRef)
                .debit(AccountKey.provider(PROVIDER_RECEIVABLE, providerCode, currency), grossMinor)
                .credit(AccountKey.platform(CUSTOMER_FUNDS_IN_TRANSIT, currency), grossMinor)
                .build();
    }

    /**
     * The biller / telco delivered. Their float is consumed at face value minus
     * our discount; the discount is PayPort's commission revenue.
     */
    public static JournalDraft consumerFulfilment(String paymentRef, String fulfilmentProvider, String currency,
                                                  long grossMinor, int commissionBps) {
        long commission = Money.applyBps(grossMinor, commissionBps);
        return JournalDraft.builder(EntryType.CONSUMER_FULFILMENT, "FULFIL:" + paymentRef, currency)
                .description("Delivered " + paymentRef + " via " + fulfilmentProvider)
                .source("TRANSACTION", paymentRef)
                .debit(AccountKey.platform(CUSTOMER_FUNDS_IN_TRANSIT, currency), grossMinor)
                .credit(AccountKey.provider(VENDOR_FLOAT, fulfilmentProvider, currency), grossMinor - commission, "Float consumed")
                .credit(AccountKey.provider(COMMISSION_REVENUE, fulfilmentProvider, currency), commission, "Biller commission")
                .build();
    }

    /**
     * Ops paid a consumer back (outside the system). If the product was never
     * delivered the money comes out of funds-in-transit; if it was delivered
     * anyway, PayPort absorbs the loss.
     */
    public static JournalDraft consumerRefundPaid(String paymentRef, String currency, long amountMinor,
                                                  boolean wasFulfilled, String paidFromCashAccount) {
        AccountKey debit = wasFulfilled
                ? AccountKey.platform(REFUND_LOSS_EXPENSE, currency)
                : AccountKey.platform(CUSTOMER_FUNDS_IN_TRANSIT, currency);
        return JournalDraft.builder(EntryType.CONSUMER_REFUND_PAID, "CONSUMER_REFUND:" + paymentRef, currency)
                .description("Consumer refund for " + paymentRef + (wasFulfilled ? " (after delivery, absorbed)" : ""))
                .source("TRANSACTION", paymentRef)
                .debit(debit, amountMinor)
                .credit(AccountKey.cash(paidFromCashAccount, currency), amountMinor)
                .build();
    }

    // ── Holds and reserves ────────────────────────────────────────────────

    public record Release(JournalDraft draft, long toAvailableMinor, long toReserveMinor) {}

    /** End of the T+N hold: pending moves to available, minus the rolling reserve. */
    public static Release releaseFunds(String holdRef, long merchantId, String currency, long amountMinor, int reserveBps) {
        long reserve = Money.applyBps(amountMinor, reserveBps);
        long available = amountMinor - reserve;
        JournalDraft d = JournalDraft.builder(EntryType.FUNDS_RELEASED, "RELEASE:" + holdRef, currency)
                .description("Release of " + holdRef + (reserve > 0 ? " with " + reserveBps + "bps reserve" : ""))
                .source("FUND_HOLD", holdRef)
                .debit(AccountKey.merchant(MERCHANT_PENDING, merchantId, currency), amountMinor)
                .credit(AccountKey.merchant(MERCHANT_AVAILABLE, merchantId, currency), available)
                .credit(AccountKey.merchant(MERCHANT_RESERVE, merchantId, currency), reserve)
                .build();
        return new Release(d, available, reserve);
    }

    public static JournalDraft releaseReserve(String holdRef, long merchantId, String currency, long amountMinor) {
        return JournalDraft.builder(EntryType.RESERVE_RELEASED, "RESERVE_RELEASE:" + holdRef, currency)
                .description("Reserve release for " + holdRef)
                .source("FUND_HOLD", holdRef)
                .debit(AccountKey.merchant(MERCHANT_RESERVE, merchantId, currency), amountMinor)
                .credit(AccountKey.merchant(MERCHANT_AVAILABLE, merchantId, currency), amountMinor)
                .build();
    }

    // ── Merchant refunds ──────────────────────────────────────────────────

    /**
     * A merchant refunds a customer. Funded from AVAILABLE first, then PENDING;
     * the ledger rejects the posting if together they are not enough.
     * Creates a liability to the customer until the refund is actually paid out.
     */
    public static JournalDraft merchantRefund(String refundRef, long merchantId, String currency,
                                              long fromAvailableMinor, long fromPendingMinor) {
        long total = Math.addExact(fromAvailableMinor, fromPendingMinor);
        return JournalDraft.builder(EntryType.MERCHANT_REFUND, "MREFUND:" + refundRef, currency)
                .description("Merchant refund " + refundRef)
                .source("REFUND", refundRef)
                .debit(AccountKey.merchant(MERCHANT_AVAILABLE, merchantId, currency), fromAvailableMinor)
                .debit(AccountKey.merchant(MERCHANT_PENDING, merchantId, currency), fromPendingMinor)
                .credit(AccountKey.platform(CUSTOMER_REFUND_PAYABLE, currency), total)
                .build();
    }

    public static JournalDraft refundPayout(String refundRef, String currency, long amountMinor, String paidFromCashAccount) {
        return JournalDraft.builder(EntryType.REFUND_PAYOUT, "MREFUND_PAID:" + refundRef, currency)
                .description("Refund " + refundRef + " paid to customer")
                .source("REFUND", refundRef)
                .debit(AccountKey.platform(CUSTOMER_REFUND_PAYABLE, currency), amountMinor)
                .credit(AccountKey.cash(paidFromCashAccount, currency), amountMinor)
                .build();
    }

    // ── Settlement ────────────────────────────────────────────────────────

    /** Funds are earmarked for an instruction the moment it is created, so they cannot be refunded or settled twice. */
    public static JournalDraft settlementReserved(String instructionRef, long merchantId, String currency, long amountMinor) {
        return JournalDraft.builder(EntryType.SETTLEMENT_RESERVED, "SETTLE_RESERVE:" + instructionRef, currency)
                .description("Settlement instruction " + instructionRef)
                .source("SETTLEMENT_INSTRUCTION", instructionRef)
                .debit(AccountKey.merchant(MERCHANT_AVAILABLE, merchantId, currency), amountMinor)
                .credit(AccountKey.merchant(SETTLEMENT_PAYABLE, merchantId, currency), amountMinor)
                .build();
    }

    /** Proof of payment received: the obligation is discharged and cash leaves the trust account. */
    public static JournalDraft settlementPaid(String instructionRef, long merchantId, String currency, long amountMinor,
                                              String paidFromCashAccount, long bankChargeMinor, String bankCode) {
        return JournalDraft.builder(EntryType.SETTLEMENT_PAID, "SETTLE_PAID:" + instructionRef, currency)
                .description("Settlement " + instructionRef + " paid")
                .source("SETTLEMENT_INSTRUCTION", instructionRef)
                .debit(AccountKey.merchant(SETTLEMENT_PAYABLE, merchantId, currency), amountMinor)
                .credit(AccountKey.cash(paidFromCashAccount, currency), amountMinor)
                .debit(AccountKey.provider(PROVIDER_FEE_EXPENSE, bankCode != null ? bankCode : "BANK", currency), bankChargeMinor, "Bank charge")
                .credit(AccountKey.cash(paidFromCashAccount, currency), bankChargeMinor, "Bank charge")
                .build();
    }

    /** Payout failed before money left: earmark is released back to AVAILABLE so the next batch picks it up. */
    public static JournalDraft settlementFailed(String instructionRef, long merchantId, String currency, long amountMinor) {
        return JournalDraft.builder(EntryType.SETTLEMENT_RETURNED, "SETTLE_FAILED:" + instructionRef, currency)
                .description("Settlement " + instructionRef + " failed, funds returned to available")
                .source("SETTLEMENT_INSTRUCTION", instructionRef)
                .debit(AccountKey.merchant(SETTLEMENT_PAYABLE, merchantId, currency), amountMinor)
                .credit(AccountKey.merchant(MERCHANT_AVAILABLE, merchantId, currency), amountMinor)
                .build();
    }

    /** Payout was sent and later bounced back by the bank (wrong account, closed account). */
    public static JournalDraft settlementReturnedAfterPayment(String instructionRef, long merchantId, String currency,
                                                              long amountMinor, String returnedToCashAccount) {
        return JournalDraft.builder(EntryType.SETTLEMENT_RETURNED, "SETTLE_BOUNCED:" + instructionRef, currency)
                .description("Settlement " + instructionRef + " returned by bank")
                .source("SETTLEMENT_INSTRUCTION", instructionRef)
                .debit(AccountKey.cash(returnedToCashAccount, currency), amountMinor)
                .credit(AccountKey.merchant(MERCHANT_AVAILABLE, merchantId, currency), amountMinor)
                .build();
    }

    // ── Rail settlement into our bank ─────────────────────────────────────

    public static JournalDraft providerSettlementReceived(String statementRef, String providerCode, String currency,
                                                          long grossClearedMinor, long providerFeesMinor,
                                                          String receivedIntoCashAccount) {
        if (providerFeesMinor > grossClearedMinor) {
            throw new LedgerException("Provider fees exceed the gross cleared amount");
        }
        return JournalDraft.builder(EntryType.PROVIDER_SETTLEMENT_RECEIVED, "PROVIDER_SETTLED:" + providerCode + ":" + statementRef, currency)
                .description(providerCode + " settlement " + statementRef)
                .source("PROVIDER_SETTLEMENT", statementRef)
                .debit(AccountKey.cash(receivedIntoCashAccount, currency), grossClearedMinor - providerFeesMinor)
                .debit(AccountKey.provider(PROVIDER_FEE_EXPENSE, providerCode, currency), providerFeesMinor)
                .credit(AccountKey.provider(PROVIDER_RECEIVABLE, providerCode, currency), grossClearedMinor)
                .build();
    }

    // ── Tax ──────────────────────────────────────────────────────────────

    /** PayPort paid collected tax (IMTT / VAT) over to ZIMRA. */
    public static JournalDraft taxRemittance(String remittanceRef, String taxCode, String currency, long amountMinor, String paidFromCashAccount) {
        return JournalDraft.builder(EntryType.ADJUSTMENT, "TAX_REMIT:" + taxCode + ":" + remittanceRef, currency)
                .description(taxCode + " remitted to ZIMRA, ref " + remittanceRef)
                .source("TAX_REMITTANCE", remittanceRef)
                .debit(AccountKey.tax(taxCode, currency), amountMinor)
                .credit(AccountKey.cash(paidFromCashAccount, currency), amountMinor)
                .build();
    }

    // ── Adjustments and cutover ───────────────────────────────────────────

    /** Manual correction. Only ever posted after a reconciliation break has been approved by a second person. */
    public static JournalDraft adjustment(String adjustmentRef, String currency, AccountKey debit, AccountKey credit,
                                          long amountMinor, String reason) {
        if (debit.equals(credit)) throw new LedgerException("Adjustment debit and credit accounts are the same");
        return JournalDraft.builder(EntryType.ADJUSTMENT, "ADJUST:" + adjustmentRef, currency)
                .description("Adjustment " + adjustmentRef + ": " + reason)
                .source("RECON_BREAK", adjustmentRef)
                .debit(debit, amountMinor)
                .credit(credit, amountMinor)
                .build();
    }

    /** One-time import of a merchant's balance from the pre-ledger system. */
    public static JournalDraft openingBalance(long merchantId, String currency, long amountMinor) {
        return JournalDraft.builder(EntryType.OPENING_BALANCE, "OPENING:" + merchantId + ":" + AccountKey.normaliseCurrency(currency), currency)
                .description("Opening balance brought forward from legacy checkout-session balance")
                .source("MERCHANT", Long.toString(merchantId))
                .debit(AccountKey.platform(OPENING_BALANCE_EQUITY, currency), amountMinor)
                .credit(AccountKey.merchant(MERCHANT_AVAILABLE, merchantId, currency), amountMinor)
                .build();
    }
}
