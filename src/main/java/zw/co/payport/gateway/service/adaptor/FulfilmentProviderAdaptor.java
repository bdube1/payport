package zw.co.payport.gateway.service.adaptor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FulfilmentProviderAdaptor Interface
 *
 * Every service provider adaptor MUST implement this interface.
 * Adding a new service = build a new adaptor. No gateway core changes.
 */
public interface FulfilmentProviderAdaptor {

    /** Provider code matching service_providers.code e.g. "ECONET" */
    String getProviderCode();

    /**
     * True if this provider identifies a delivery by a reference PayPort generates (eSolutions:
     * vendorReference) that must be recorded before sending, so an unknown outcome can be looked
     * up by exactly that reference.
     */
    default boolean usesDeliveryReference() { return false; }

    /**
     * Deliver the service to the recipient.
     * e.g. credit airtime, generate ZESA token, renew DStv subscription.
     */
    FulfilmentResult deliver(FulfilmentRequest request) throws Exception;

    /**
     * Query a previous delivery for its current status.
     * Used during manual recovery and reconciliation.
     */
    FulfilmentStatusResult queryDelivery(String fulfilmentReference) throws Exception;

    /** Currency-aware variant for providers whose credentials differ per currency (Econet). */
    default FulfilmentStatusResult queryDelivery(String fulfilmentReference, String currency) throws Exception {
        return queryDelivery(fulfilmentReference);
    }

    // ─── Normalised DTOs ──────────────────────────────────────────────────────

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    class FulfilmentRequest {
        private String  payportReference;
        private String  recipientIdentifier;    // phone, meter number, account
        private long    amountCents;
        private String  currency;
        private String  productTypeCode;
        private String  productConfig;           // JSON from product_types.fulfilment_config
        /** Set by the orchestrator for providers that use one (usesDeliveryReference): the reference to send, already recorded on the payment. */
        private String  deliveryReference;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    class FulfilmentResult {
        private boolean success;
        /**
         * True when success == false but the delivery MAY have happened (read timeout, broken
         * connection, unparseable response). The orchestrator then never re-delivers; it asks
         * queryDelivery() or hands the case to a person.
         */
        private boolean outcomeUnknown;
        /** The provider's own result/error code, when it has one (see DeliveryOutcomeRules). */
        private String  errorCode;
        private String  fulfilmentReference;     // provider's delivery reference
        private String  fulfilmentData;          // JSON - e.g. {"token":"1234-5678-9012"} for ZESA
        private String  message;
        private String  rawResponse;             // stored verbatim for audit
        /**
         * Set only when the adaptor itself has already produced a clean,
         * customer-ready translation (e.g. EconetAirtimeAdaptor mapping
         * its own documented numeric status codes) -- when present, the
         * orchestrator uses this directly instead of re-running the
         * generic text-pattern translator on it. Without this, an
         * already-translated message risked being silently discarded and
         * replaced with the generic fallback, since clean translated text
         * naturally doesn't contain the raw provider jargon (e.g.
         * "duplicate", "service unavailable") the generic matcher looks
         * for in the first place.
         */
        private String  customerMessage;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    class FulfilmentStatusResult {
        private String  status;                  // DELIVERED | FAILED | NOT_FOUND (never received) | PENDING | UNKNOWN
        private String  fulfilmentReference;
        private String  message;
    }
}
