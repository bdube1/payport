package zw.co.payport.gateway.psp.ledger.domain;

/**
 * PayPort chart of accounts. One ledger account exists per
 * (category, owner, currency): USD and ZiG never share an account.
 *
 * allowNegative = false means the ledger refuses any posting that would take
 * the account below zero. It is set on the buckets PayPort controls (merchant
 * funds, customer funds in transit). It is left true on accounts that
 * record facts reported by third parties (rails, banks, billers), because the
 * ledger must record what actually happened even when it is unwelcome.
 */
public enum AccountCategory {

    // ── Assets: what PayPort holds or is owed ───────────────────────────────
    /** Money a payment rail (EcoCash, OneMoney, ZimSwitch...) has collected for us but not yet settled to our bank. Owner = provider code. */
    PROVIDER_RECEIVABLE(AccountType.ASSET, OwnerKind.PROVIDER, true),
    /** Trust / settlement bank accounts and PayPort's own wallets at rails. Owner = cash account key, e.g. TRUST_CBZ_USD, ECOCASH_WALLET. */
    PLATFORM_CASH(AccountType.ASSET, OwnerKind.CASH_ACCOUNT, true),
    /** Pre-funded float held at billers / telcos (e-Solutions, Econet airtime...). Owner = provider code. */
    VENDOR_FLOAT(AccountType.ASSET, OwnerKind.PROVIDER, true),

    // ── Liabilities: what PayPort owes others ──────────────────────────────
    /** Collected for a merchant, not yet cleared for payout (T+N hold). */
    MERCHANT_PENDING(AccountType.LIABILITY, OwnerKind.MERCHANT, false),
    /** Cleared and payable to the merchant on the next settlement run. */
    MERCHANT_AVAILABLE(AccountType.LIABILITY, OwnerKind.MERCHANT, false),
    /** Rolling reserve withheld against refunds / disputes. */
    MERCHANT_RESERVE(AccountType.LIABILITY, OwnerKind.MERCHANT, false),
    /** Included in an approved settlement instruction, awaiting proof of payment. */
    SETTLEMENT_PAYABLE(AccountType.LIABILITY, OwnerKind.MERCHANT, false),
    /** A consumer has paid for a bill/airtime product that has not yet been delivered. */
    CUSTOMER_FUNDS_IN_TRANSIT(AccountType.LIABILITY, OwnerKind.PLATFORM, false),
    /** Refunds owed to customers and not yet paid out. */
    CUSTOMER_REFUND_PAYABLE(AccountType.LIABILITY, OwnerKind.PLATFORM, false),
    /** Taxes collected and owed to ZIMRA. Owner = tax code (VAT, IMTT). */
    TAX_PAYABLE(AccountType.LIABILITY, OwnerKind.TAX, true),
    /** Customer e-money wallet balance. Owner = customer ref. */
    CUSTOMER_WALLET(AccountType.LIABILITY, OwnerKind.CUSTOMER, false),
    /** E-money float owed to an agent (decreases on cash-in, increases on cash-out). Owner = agent id. */
    AGENT_FLOAT(AccountType.LIABILITY, OwnerKind.AGENT, false),
    /** Commissions earned by agents, not yet paid. */
    AGENT_COMMISSION_PAYABLE(AccountType.LIABILITY, OwnerKind.AGENT, false),
    /** Unexplained differences parked while a reconciliation break is investigated. Never a permanent home. */
    SUSPENSE(AccountType.LIABILITY, OwnerKind.PLATFORM, true),

    /** Currency position from ZiG/USD conversions, one per currency. Revalued and managed by treasury. */
    FX_POSITION(AccountType.ASSET, OwnerKind.PLATFORM, true),

    // ── Equity ─────────────────────────────────────────────────────────────
    /** Counter-side for balances brought in from the pre-ledger system at cutover. */
    OPENING_BALANCE_EQUITY(AccountType.EQUITY, OwnerKind.PLATFORM, true),

    // ── Revenue ────────────────────────────────────────────────────────────
    /** MDR / processing fees charged to merchants. */
    FEE_REVENUE(AccountType.REVENUE, OwnerKind.PLATFORM, true),
    /** Discount / commission earned from billers and telcos on consumer purchases. Owner = provider code. */
    COMMISSION_REVENUE(AccountType.REVENUE, OwnerKind.PROVIDER, true),
    /** Spread earned on currency conversions. */
    FX_REVENUE(AccountType.REVENUE, OwnerKind.PLATFORM, true),

    // ── Expenses ───────────────────────────────────────────────────────────
    /** Fees charged to PayPort by rails and banks. Owner = provider / bank code. */
    PROVIDER_FEE_EXPENSE(AccountType.EXPENSE, OwnerKind.PROVIDER, true),
    /** Refunds PayPort absorbed (e.g. product delivered but customer refunded anyway). */
    REFUND_LOSS_EXPENSE(AccountType.EXPENSE, OwnerKind.PLATFORM, true),
    /** Commissions PayPort pays agents for cash-in / cash-out. */
    AGENT_COMMISSION_EXPENSE(AccountType.EXPENSE, OwnerKind.PLATFORM, true),
    /** Chargeback / dispute losses PayPort could not recover from a merchant. */
    DISPUTE_LOSS_EXPENSE(AccountType.EXPENSE, OwnerKind.PLATFORM, true);

    public enum OwnerKind { PLATFORM, MERCHANT, AGENT, CUSTOMER, PROVIDER, CASH_ACCOUNT, TAX }

    private final AccountType type;
    private final OwnerKind ownerKind;
    private final boolean allowNegative;

    AccountCategory(AccountType type, OwnerKind ownerKind, boolean allowNegative) {
        this.type = type;
        this.ownerKind = ownerKind;
        this.allowNegative = allowNegative;
    }

    public AccountType type() { return type; }
    public OwnerKind ownerKind() { return ownerKind; }
    public boolean allowNegative() { return allowNegative; }
}
