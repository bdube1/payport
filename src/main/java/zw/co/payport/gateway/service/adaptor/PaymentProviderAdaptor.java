package zw.co.payport.gateway.service.adaptor;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.Map;

/**
 * PaymentProvider Interface
 *
 * Every payment method adaptor MUST implement this interface.
 * Adding a new payment method = build a new adaptor class implementing this.
 * Zero core gateway code changes required.
 */
public interface PaymentProviderAdaptor {

    /** Provider code matching payment_methods.code e.g. "ECOCASH" */
    String getProviderCode();

    /**
     * Submit a payment request to the provider.
     * Returns immediately with a provider reference - actual confirmation comes via callback.
     */
    PaymentSubmitResult submitPayment(PaymentRequest request) throws Exception;

    /**
     * Validate an incoming callback from the provider.
     * Checks: IP whitelist, HMAC signature, structure integrity.
     */
    CallbackValidationResult validateCallback(Map<String, String> headers, String rawBody);

    /**
     * Parse a validated callback payload into a normalised CallbackEvent.
     */
    CallbackEvent parseCallback(String rawBody) throws Exception;

    /**
     * Directly query the provider for a payment status.
     * Used by timeout watchdog and reconciliation.
     */
    PaymentStatusResult queryStatus(String providerReference) throws Exception;

    // ─── Normalised DTOs ──────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    class PaymentRequest {
        private String payportReference;    // PP-WEB-20260315-000042 - used as external_ref
        private long   amountCents;
        private String currency;
        private String customerPhone;       // E.164 - receives USSD push
        private String description;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    class PaymentSubmitResult {
        private boolean submitted;
        private String  providerReference;
        private String  message;
        private String  rawResponse;        // stored verbatim for audit
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    class CallbackValidationResult {
        private boolean valid;
        private String  failureReason;
    }

    /**
     * Normalised callback event - same structure regardless of which provider sent it.
     * This decouples the gateway core from provider-specific payload formats.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    class CallbackEvent {
        private String  payportReference;   // Our reference - extracted from callback
        private String  providerReference;  // Provider's own reference
        private String  status;             // SUCCESS | FAILED | PENDING
        private long    amountCents;
        private String  currency;
        private String  timestamp;          // ISO-8601
        private String  rawPayload;         // stored verbatim for audit
        /**
         * Customer-facing reason for a FAILED status -- e.g. Omari's
         * "This payment exceeds your wallet's transaction limit for
         * today." (translated from their raw "EXCEEDS_LIMIT" code).
         * Optional: was previously nonexistent on this event entirely,
         * so every single provider's real failure reason -- however
         * specific and however correctly translated upstream -- was
         * discarded, and the customer always saw the exact same generic
         * "declined by the provider" text no matter what actually
         * happened. Null falls back to that same generic text, so a
         * caller that doesn't have a specific reason yet loses nothing.
         */
        private String  failureMessage;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    class PaymentStatusResult {
        private String  status;             // SUCCESS | FAILED | PENDING | UNKNOWN
        private long    amountCents;
        private String  providerReference;
        private String  message;
    }
}
