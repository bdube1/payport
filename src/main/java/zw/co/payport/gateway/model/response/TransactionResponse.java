package zw.co.payport.gateway.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import zw.co.payport.gateway.entity.Transaction;
import zw.co.payport.gateway.entity.TransactionAuditLog;
import zw.co.payport.gateway.enums.TransactionStatus;

import java.time.LocalDateTime;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TransactionResponse {

    private String reference;
    private TransactionStatus status;
    private String channel;
    private String productTypeCode;
    private String productName;
    private String paymentMethodCode;
    private String recipientIdentifier;
    private Long amountCents;
    private String amountFormatted;
    private String currency;
    private String customerPhone;
    private String providerReference;
    private String fulfilmentReference;
    private String fulfilmentData;
    private Integer retryCount;
    private String failureReason;
    /** The customer-facing outcome: REQUIRES_PAYMENT, PROCESSING, SUCCEEDED, FAILED, TIMED_OUT, NO_RESPONSE, REFUNDED, CANCELLED. */
    private String outcome;
    /** The provider's own message for FAILED; the timeout / no-response wording otherwise. */
    private String outcomeMessage;
    /** Payment attempts made on this transaction (a new attempt is allowed after a definite failure). */
    private Integer paymentAttempts;
    private LocalDateTime initiatedAt;
    private LocalDateTime paymentConfirmedAt;
    private LocalDateTime completedAt;
    private String refundReference;
    private String refundMethod;
    private LocalDateTime refundedAt;
    private List<AuditEntry> timeline;

    /**
     * ZimSwitch / OPPWa card widget fields.
     *
     * widgetJsUrl  — the JS URL the frontend must load as a <script> tag
     *                e.g. https://eu-test.oppwa.com/v1/paymentWidgets.js?checkoutId=ABC123
     *                Only present when paymentMethodCode = ZIMSWITCH and status = PAYMENT_PENDING
     *
     * paymentCorrelator — the OPPWa checkoutId. Stored here so the frontend
     *                     can reference it when building the widget form action URL.
     */
    private String widgetJsUrl;
    private String paymentCorrelator;

    /**
     * True only for Omari, only right after submission -- Omari's flow
     * doesn't use a phone-side PIN push like EcoCash/NetOne/OneMoney.
     * Instead it texts the customer a one-time code that has to be
     * entered back into Payport's own UI to actually confirm the
     * payment. This tells the frontend to show that entry screen instead
     * of the normal "check your phone" waiting state.
     */
    private boolean requiresOtpEntry;

    /**
     * Per-transaction access token, returned ONCE in the POST /initiate response and
     * never again. The client sends it back as the X-Transaction-Token header to get
     * the full view of this transaction (see TransactionAccessService).
     */
    private String accessToken;

    /**
     * What an unauthenticated caller without the access token may see (R3 containment).
     * Keeps everything the payment screens need to progress (status, amount, card-widget
     * and OTP fields) and drops personal and delivery data: phone numbers, recipient,
     * provider references, the ZESA token / fulfilment data, refund details and the
     * internal audit timeline. The customer still receives any token by SMS.
     */
    public static TransactionResponse publicView(TransactionResponse full) {
        return TransactionResponse.builder()
                .reference(full.getReference())
                .status(full.getStatus())
                .channel(full.getChannel())
                .productTypeCode(full.getProductTypeCode())
                .productName(full.getProductName())
                .paymentMethodCode(full.getPaymentMethodCode())
                .amountCents(full.getAmountCents())
                .amountFormatted(full.getAmountFormatted())
                .currency(full.getCurrency())
                .failureReason(full.getFailureReason())
                .outcome(full.getOutcome())
                .outcomeMessage(full.getOutcomeMessage())
                .paymentAttempts(full.getPaymentAttempts())
                .initiatedAt(full.getInitiatedAt())
                .paymentConfirmedAt(full.getPaymentConfirmedAt())
                .completedAt(full.getCompletedAt())
                .widgetJsUrl(full.getWidgetJsUrl())
                .paymentCorrelator(full.getPaymentCorrelator())
                .requiresOtpEntry(full.isRequiresOtpEntry())
                .build();
    }
    public static TransactionResponse from(Transaction tx) {
        TransactionResponse r = TransactionResponse.builder()
                .reference(tx.getReference())
                .status(tx.getStatus())
                .channel(tx.getChannel().name())
                .productTypeCode(tx.getProductType() != null ? tx.getProductType().getCode() : null)
                .productName(tx.getProductType() != null ? tx.getProductType().getName() : null)
                .paymentMethodCode(tx.getPaymentMethod() != null ? tx.getPaymentMethod().getCode() : null)
                .recipientIdentifier(tx.getRecipientIdentifier())
                .amountCents(tx.getAmountCents())
                .amountFormatted(tx.getAmountFormatted())
                .currency(tx.getCurrency())
                .customerPhone(tx.getCustomerPhone())
                .providerReference(tx.getProviderReference())
                .fulfilmentReference(tx.getFulfilmentReference())
                .fulfilmentData(tx.getFulfilmentData())
                .retryCount(tx.getRetryCount())
                .failureReason(tx.getFailureReason())
                .initiatedAt(tx.getInitiatedAt())
                .paymentConfirmedAt(tx.getPaymentConfirmedAt())
                .completedAt(tx.getCompletedAt())
                .refundReference(tx.getRefundReference())
                .refundMethod(tx.getRefundMethod())
                .refundedAt(tx.getRefundedAt())
                .build();

        // Expose widget URL and correlator only for ZimSwitch transactions
        if ("ZIMSWITCH".equals(tx.getPaymentProviderCode())) {
            // paymentProviderMessage stores the widget JS URL (set in TransactionOrchestratorService)
            r.setWidgetJsUrl(tx.getPaymentProviderMessage());
            r.setPaymentCorrelator(tx.getPaymentCorrelator());
        }

        // Computed from current state, not set once and forgotten -- this
        // needs to be correct on every poll (Processing.jsx only ever
        // sees GET /{reference} responses, never the original POST /pay
        // response), and it needs to turn itself back off once the
        // payment leaves PAYMENT_PENDING, whichever way it resolves.
        if ("OMARI".equals(tx.getPaymentProviderCode()) && tx.getStatus() == zw.co.payport.gateway.enums.TransactionStatus.PAYMENT_PENDING) {
            r.setRequiresOtpEntry(true);
        }

        return r;
    }

    public static TransactionResponse fromWithTimeline(Transaction tx, List<TransactionAuditLog> logs) {
        TransactionResponse response = from(tx);
        response.setTimeline(logs.stream()
                .map(log -> AuditEntry.builder()
                        .fromStatus(log.getFromStatus())
                        .toStatus(log.getToStatus())
                        .actor(log.getActor())
                        .notes(log.getNotes())
                        .createdAt(log.getCreatedAt())
                        .build())
                .toList());
        return response;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AuditEntry {
        private TransactionStatus fromStatus;
        private TransactionStatus toStatus;
        private String actor;
        private String notes;
        private LocalDateTime createdAt;
    }
}