package zw.co.payport.gateway.psp.platform.approval;

/**
 * Executes one kind of maker-checker action once a second person approves it.
 * Implementations live in the module that owns the data (ledger adjustments in
 * recon, payout-account verification in merchant, manual FX rates in fx...).
 */
public interface ApprovalHandler {

    /** e.g. LEDGER_ADJUSTMENT, PAYOUT_ACCOUNT_VERIFY, FX_RATE_MANUAL */
    String actionType();

    /** Role the approver must hold (ADMIN, FINANCE, COMPLIANCE...). */
    String requiredRole();

    /** Optional pre-check when the request is raised, so obviously invalid requests are rejected at once. */
    default void validate(String targetRef, String payloadJson) {}

    /**
     * Runs inside the approval transaction: if it throws, the approval is not recorded.
     * @return short human-readable result stored on the request
     */
    String execute(String targetRef, String payloadJson, String requestedBy, String approvedBy);
}
