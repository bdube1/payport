package zw.co.payport.gateway.ledger;

/**
 * Chart of accounts categories. Mirrors the ledger_categories table (V33), which is the
 * source of truth for type, normal side and the no-negative rule; the database rejects
 * any category not in that table.
 */
public enum LedgerCategory {
    BANK_CASH,
    PROVIDER_RECEIVABLE,
    VENDOR_FLOAT,
    SUSPENSE,
    FX_CLEARING,
    MERCHANT_PAYABLE,
    SETTLEMENT_PAYABLE,
    CUSTOMER_ORDER_OBLIGATION,
    CUSTOMER_REFUND_PAYABLE,
    TAX_PAYABLE,
    FEE_REVENUE,
    COMMISSION_REVENUE,
    FX_GAIN_LOSS,
    PROVIDER_FEE_EXPENSE,
    LOSS_EXPENSE,
    OPENING_BALANCE_EQUITY,
    // V52 (Phase 8 wallets and agents)
    CUSTOMER_WALLET,
    AGENT_FLOAT,
    AGENT_COMMISSION_EXPENSE
}
