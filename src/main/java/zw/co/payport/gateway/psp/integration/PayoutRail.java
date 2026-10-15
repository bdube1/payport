package zw.co.payport.gateway.psp.integration;

/**
 * Outbound money rail used by the settlement engine and wallet cash-outs:
 * ZIPIT (instant interbank), ZIPSS/RTGS (high value), bank host-to-host APIs,
 * and mobile-wallet B2C disbursement (EcoCash, OneMoney, InnBucks, O'mari).
 *
 * Contract: submit() must be idempotent on {@code instructionRef} (rails are
 * given our reference as their end-to-end id), and must return ACCEPTED only
 * when the rail has taken responsibility for the payment. UNKNOWN means
 * "we could not tell" and is treated like a payment timeout: never retried
 * blindly, always resolved by status() or a bank statement match.
 */
public interface PayoutRail {

    String code();

    boolean supports(String payoutMethod, String currency);

    PayoutResult submit(PayoutRequest request);

    PayoutResult status(String instructionRef, String railReference);

    record PayoutRequest(String instructionRef, String currency, long amountMinor, String beneficiaryName,
                         String bankName, String bankCode, String branchCode, String accountNumber,
                         String mobileNumber, String narrative) {}

    /** status: ACCEPTED | COMPLETED | REJECTED | UNKNOWN */
    record PayoutResult(String status, String railReference, String message) {
        public static PayoutResult unknown(String msg) { return new PayoutResult("UNKNOWN", null, msg); }
    }
}
