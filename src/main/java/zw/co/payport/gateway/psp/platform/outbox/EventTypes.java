package zw.co.payport.gateway.psp.platform.outbox;

/** Catalogue of PSP event types. Payloads are documented next to each publisher. */
public final class EventTypes {
    private EventTypes() {}

    // Payments
    public static final String PAYMENT_CONFIRMED = "payment.confirmed";
    public static final String PAYMENT_FAILED = "payment.failed";
    public static final String PAYMENT_OUTCOME_UNKNOWN = "payment.outcome_unknown";
    public static final String PAYMENT_COMPLETED = "payment.completed";
    public static final String PAYMENT_REFUNDED = "payment.refunded";

    // Ledger
    public static final String LEDGER_ENTRY_POSTED = "ledger.entry_posted";

    // Wallets & agents
    public static final String WALLET_TRANSFER_COMPLETED = "wallet.transfer_completed";
    public static final String AGENT_CASH_IN = "agent.cash_in";
    public static final String AGENT_CASH_OUT = "agent.cash_out";

    // Settlement & reconciliation
    public static final String SETTLEMENT_BATCH_APPROVED = "settlement.batch_approved";
    public static final String SETTLEMENT_INSTRUCTION_PAID = "settlement.instruction_paid";
    public static final String SETTLEMENT_INSTRUCTION_FAILED = "settlement.instruction_failed";
    public static final String RECON_BREAK_OPENED = "recon.break_opened";

    // Merchants & KYC
    public static final String MERCHANT_SUSPENDED = "merchant.suspended";
    public static final String MERCHANT_REINSTATED = "merchant.reinstated";
    public static final String KYC_TIER_CHANGED = "kyc.tier_changed";

    // Risk & compliance
    public static final String FRAUD_CASE_OPENED = "risk.fraud_case_opened";
    public static final String AML_ALERT_RAISED = "risk.aml_alert_raised";
    public static final String DISPUTE_OPENED = "dispute.opened";
    public static final String DISPUTE_RESOLVED = "dispute.resolved";

    // Notifications
    public static final String NOTIFICATION_REQUESTED = "notify.requested";
}
