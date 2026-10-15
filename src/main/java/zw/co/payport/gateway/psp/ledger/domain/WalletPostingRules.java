package zw.co.payport.gateway.psp.ledger.domain;

import static zw.co.payport.gateway.psp.ledger.domain.AccountCategory.*;

/**
 * Posting rules for e-money wallets, the agent network, FX conversion and
 * disputes. Same guarantees as {@link PostingRules}: balanced, validated,
 * keyed on the business reference.
 *
 * E-money safeguarding identity (checked by the safeguarding report):
 *   SUM(CUSTOMER_WALLET) + SUM(AGENT_FLOAT) + SUM(AGENT_COMMISSION_PAYABLE)
 *   must be covered by PLATFORM_CASH held in trust.
 */
public final class WalletPostingRules {

    private WalletPostingRules() {}

    /** Customer funded their wallet through a rail (EcoCash, card, bank). */
    public static JournalDraft topUp(String paymentRef, String customerRef, String providerCode, String currency, long amountMinor) {
        return JournalDraft.builder(EntryType.WALLET_TOPUP, "COLLECT:" + paymentRef, currency)
                .description("Wallet top-up " + paymentRef + " via " + providerCode)
                .source("TRANSACTION", paymentRef)
                .debit(AccountKey.provider(PROVIDER_RECEIVABLE, providerCode, currency), amountMinor)
                .credit(AccountKey.customer(customerRef, currency), amountMinor)
                .build();
    }

    /** Person-to-person transfer. Sender pays the fee and any transfer tax on top. */
    public static JournalDraft p2p(String transferRef, String fromCustomer, String toCustomer, String currency,
                                   long amountMinor, long feeMinor, long taxMinor) {
        if (fromCustomer.equalsIgnoreCase(toCustomer)) throw new LedgerException("Cannot transfer to the same wallet");
        return JournalDraft.builder(EntryType.WALLET_TRANSFER, "P2P:" + transferRef, currency)
                .description("Wallet transfer " + transferRef)
                .source("WALLET_TRANSFER", transferRef)
                .debit(AccountKey.customer(fromCustomer, currency), Math.addExact(amountMinor, Math.addExact(feeMinor, taxMinor)))
                .credit(AccountKey.customer(toCustomer, currency), amountMinor)
                .credit(AccountKey.platform(FEE_REVENUE, currency), feeMinor, "Transfer fee")
                .credit(AccountKey.tax(PostingRules.TAX_TRANSFER, currency), taxMinor, "IMTT")
                .build();
    }

    /** Customer pays a merchant from their wallet (QR, USSD merchant code, POS). Merchant fee as for any collection. */
    public static JournalDraft merchantPayment(String paymentRef, String customerRef, long merchantId, String currency, FeeQuote q) {
        return JournalDraft.builder(EntryType.WALLET_MERCHANT_PAYMENT, "COLLECT:" + paymentRef, currency)
                .description("Wallet payment " + paymentRef + " to merchant " + merchantId)
                .source("WALLET_TRANSFER", paymentRef)
                .debit(AccountKey.customer(customerRef, currency), q.grossMinor())
                .credit(AccountKey.merchant(MERCHANT_PENDING, merchantId, currency), q.netToMerchantMinor())
                .credit(AccountKey.platform(FEE_REVENUE, currency), q.feeMinor())
                .credit(AccountKey.tax(PostingRules.TAX_VAT, currency), q.vatOnFeeMinor())
                .credit(AccountKey.tax(PostingRules.TAX_TRANSFER, currency), q.transferTaxMinor())
                .build();
    }

    /** Withdrawal from wallet to a bank account or another mobile-money wallet via a payout rail. */
    public static JournalDraft withdrawal(String transferRef, String customerRef, String currency, long amountMinor,
                                          long feeMinor, String paidFromCashAccount) {
        return JournalDraft.builder(EntryType.WALLET_WITHDRAWAL, "WITHDRAW:" + transferRef, currency)
                .description("Wallet withdrawal " + transferRef)
                .source("WALLET_TRANSFER", transferRef)
                .debit(AccountKey.customer(customerRef, currency), Math.addExact(amountMinor, feeMinor))
                .credit(AccountKey.cash(paidFromCashAccount, currency), amountMinor)
                .credit(AccountKey.platform(FEE_REVENUE, currency), feeMinor, "Withdrawal fee")
                .build();
    }

    // ── Agent network ─────────────────────────────────────────────────────

    /**
     * Cash-in: customer hands cash to the agent, agent's e-float moves to the
     * customer's wallet. The agent earns a commission paid by PayPort.
     */
    public static JournalDraft cashIn(String transferRef, long agentId, String customerRef, String currency,
                                      long amountMinor, long agentCommissionMinor) {
        return JournalDraft.builder(EntryType.AGENT_CASH_IN, "CASHIN:" + transferRef, currency)
                .description("Cash-in " + transferRef + " at agent " + agentId)
                .source("WALLET_TRANSFER", transferRef)
                .debit(AccountKey.agent(AGENT_FLOAT, agentId, currency), amountMinor, "Agent float used")
                .credit(AccountKey.customer(customerRef, currency), amountMinor)
                .debit(AccountKey.platform(AGENT_COMMISSION_EXPENSE, currency), agentCommissionMinor)
                .credit(AccountKey.agent(AGENT_COMMISSION_PAYABLE, agentId, currency), agentCommissionMinor)
                .build();
    }

    /**
     * Cash-out: customer's e-value moves to the agent's float and the agent
     * hands over cash. Customer pays the cash-out fee; the agent's commission
     * comes out of PayPort's fee revenue.
     */
    public static JournalDraft cashOut(String transferRef, long agentId, String customerRef, String currency,
                                       long amountMinor, long customerFeeMinor, long agentCommissionMinor) {
        return JournalDraft.builder(EntryType.AGENT_CASH_OUT, "CASHOUT:" + transferRef, currency)
                .description("Cash-out " + transferRef + " at agent " + agentId)
                .source("WALLET_TRANSFER", transferRef)
                .debit(AccountKey.customer(customerRef, currency), Math.addExact(amountMinor, customerFeeMinor))
                .credit(AccountKey.agent(AGENT_FLOAT, agentId, currency), amountMinor, "Agent float earned")
                .credit(AccountKey.platform(FEE_REVENUE, currency), customerFeeMinor, "Cash-out fee")
                .debit(AccountKey.platform(AGENT_COMMISSION_EXPENSE, currency), agentCommissionMinor)
                .credit(AccountKey.agent(AGENT_COMMISSION_PAYABLE, agentId, currency), agentCommissionMinor)
                .build();
    }

    /** Agent deposited cash into PayPort's trust account and receives the same e-float. */
    public static JournalDraft agentFloatPurchase(String depositRef, long agentId, String currency, long amountMinor, String trustCashAccount) {
        return JournalDraft.builder(EntryType.AGENT_FLOAT_PURCHASE, "FLOAT_IN:" + depositRef, currency)
                .description("Agent " + agentId + " float purchase " + depositRef)
                .source("AGENT_FLOAT", depositRef)
                .debit(AccountKey.cash(trustCashAccount, currency), amountMinor)
                .credit(AccountKey.agent(AGENT_FLOAT, agentId, currency), amountMinor)
                .build();
    }

    /** Agent sells e-float back to PayPort for a bank transfer. */
    public static JournalDraft agentFloatRedemption(String redemptionRef, long agentId, String currency, long amountMinor, String trustCashAccount) {
        return JournalDraft.builder(EntryType.AGENT_FLOAT_REDEMPTION, "FLOAT_OUT:" + redemptionRef, currency)
                .description("Agent " + agentId + " float redemption " + redemptionRef)
                .source("AGENT_FLOAT", redemptionRef)
                .debit(AccountKey.agent(AGENT_FLOAT, agentId, currency), amountMinor)
                .credit(AccountKey.cash(trustCashAccount, currency), amountMinor)
                .build();
    }

    /** Super-agent moves e-float to one of its agents (rebalancing the network). */
    public static JournalDraft agentFloatTransfer(String transferRef, long fromAgentId, long toAgentId, String currency, long amountMinor) {
        if (fromAgentId == toAgentId) throw new LedgerException("Cannot transfer float to the same agent");
        return JournalDraft.builder(EntryType.WALLET_TRANSFER, "FLOAT_XFER:" + transferRef, currency)
                .description("Float transfer " + transferRef + " from agent " + fromAgentId + " to " + toAgentId)
                .source("AGENT_FLOAT", transferRef)
                .debit(AccountKey.agent(AGENT_FLOAT, fromAgentId, currency), amountMinor)
                .credit(AccountKey.agent(AGENT_FLOAT, toAgentId, currency), amountMinor)
                .build();
    }

    /** Earned commission is paid into the agent's float. */
    public static JournalDraft agentCommissionPaid(String payRef, long agentId, String currency, long amountMinor) {
        return JournalDraft.builder(EntryType.AGENT_COMMISSION_PAID, "COMMISSION_PAID:" + payRef, currency)
                .description("Agent " + agentId + " commission payout " + payRef)
                .source("AGENT_COMMISSION", payRef)
                .debit(AccountKey.agent(AGENT_COMMISSION_PAYABLE, agentId, currency), amountMinor)
                .credit(AccountKey.agent(AGENT_FLOAT, agentId, currency), amountMinor)
                .build();
    }

    // ── FX (ZiG <-> USD) ─────────────────────────────────────────────────

    public record FxLegs(JournalDraft sell, JournalDraft buy) {}

    /**
     * A conversion is two single-currency entries posted in one transaction:
     *   sell leg (from ccy): Dr CUSTOMER_WALLET[from] / Cr FX_POSITION[from]
     *   buy leg  (to ccy):   Dr FX_POSITION[to] at mid / Cr CUSTOMER_WALLET[to] at customer rate / Cr FX_REVENUE[to] spread
     */
    public static FxLegs conversion(String convRef, String customerRef, String fromCcy, long fromAmountMinor,
                                    String toCcy, long toAmountAtMidMinor, long toAmountToCustomerMinor) {
        if (AccountKey.normaliseCurrency(fromCcy).equals(AccountKey.normaliseCurrency(toCcy))) throw new LedgerException("FX needs two currencies");
        if (toAmountToCustomerMinor > toAmountAtMidMinor) throw new LedgerException("Customer amount exceeds mid-rate amount (negative spread)");
        JournalDraft sell = JournalDraft.builder(EntryType.FX_CONVERSION, "FX_SELL:" + convRef, fromCcy)
                .description("FX " + convRef + " sell " + fromCcy)
                .source("FX_CONVERSION", convRef)
                .debit(AccountKey.customer(customerRef, fromCcy), fromAmountMinor)
                .credit(AccountKey.platform(FX_POSITION, fromCcy), fromAmountMinor)
                .build();
        JournalDraft buy = JournalDraft.builder(EntryType.FX_CONVERSION, "FX_BUY:" + convRef, toCcy)
                .description("FX " + convRef + " buy " + toCcy)
                .source("FX_CONVERSION", convRef)
                .debit(AccountKey.platform(FX_POSITION, toCcy), toAmountAtMidMinor)
                .credit(AccountKey.customer(customerRef, toCcy), toAmountToCustomerMinor)
                .credit(AccountKey.platform(FX_REVENUE, toCcy), toAmountAtMidMinor - toAmountToCustomerMinor, "FX spread")
                .build();
        return new FxLegs(sell, buy);
    }

    // ── Disputes / chargebacks ────────────────────────────────────────────

    /** A dispute is opened: the disputed amount is moved from the merchant's AVAILABLE (then PENDING) into RESERVE. */
    public static JournalDraft disputeHold(String disputeRef, long merchantId, String currency, long fromAvailable, long fromPending) {
        return JournalDraft.builder(EntryType.DISPUTE_HOLD, "DISPUTE_HOLD:" + disputeRef, currency)
                .description("Dispute " + disputeRef + " hold")
                .source("DISPUTE", disputeRef)
                .debit(AccountKey.merchant(MERCHANT_AVAILABLE, merchantId, currency), fromAvailable)
                .debit(AccountKey.merchant(MERCHANT_PENDING, merchantId, currency), fromPending)
                .credit(AccountKey.merchant(MERCHANT_RESERVE, merchantId, currency), Math.addExact(fromAvailable, fromPending))
                .build();
    }

    /** Merchant won: held funds go back to AVAILABLE. */
    public static JournalDraft disputeRelease(String disputeRef, long merchantId, String currency, long amountMinor) {
        return JournalDraft.builder(EntryType.DISPUTE_RELEASE, "DISPUTE_RELEASE:" + disputeRef, currency)
                .description("Dispute " + disputeRef + " released to merchant")
                .source("DISPUTE", disputeRef)
                .debit(AccountKey.merchant(MERCHANT_RESERVE, merchantId, currency), amountMinor)
                .credit(AccountKey.merchant(MERCHANT_AVAILABLE, merchantId, currency), amountMinor)
                .build();
    }

    /**
     * Customer won: held funds become a refund owed to the customer. Any part
     * PayPort could not hold from the merchant is PayPort's loss.
     */
    public static JournalDraft chargeback(String disputeRef, long merchantId, String currency, long fromReserveMinor, long absorbedMinor) {
        return JournalDraft.builder(EntryType.CHARGEBACK, "CHARGEBACK:" + disputeRef, currency)
                .description("Dispute " + disputeRef + " decided for customer")
                .source("DISPUTE", disputeRef)
                .debit(AccountKey.merchant(MERCHANT_RESERVE, merchantId, currency), fromReserveMinor)
                .debit(AccountKey.platform(DISPUTE_LOSS_EXPENSE, currency), absorbedMinor)
                .credit(AccountKey.platform(CUSTOMER_REFUND_PAYABLE, currency), Math.addExact(fromReserveMinor, absorbedMinor))
                .build();
    }
}
