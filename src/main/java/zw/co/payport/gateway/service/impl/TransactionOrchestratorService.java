package zw.co.payport.gateway.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.entity.*;
import zw.co.payport.gateway.enums.TransactionChannel;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.exception.*;
import zw.co.payport.gateway.model.request.*;
import zw.co.payport.gateway.model.response.TransactionResponse;
import zw.co.payport.gateway.repository.*;
import zw.co.payport.gateway.service.*;
import zw.co.payport.gateway.service.adaptor.PaymentProviderAdaptor;
import zw.co.payport.gateway.service.adaptor.FulfilmentProviderAdaptor;
import zw.co.payport.gateway.service.adaptor.econet.EconetAirtimeAdaptor;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionOrchestratorService {

    private final TransactionRepository        transactionRepository;
    private final ProductTypeRepository        productTypeRepository;
    private final PaymentMethodRepository      paymentMethodRepository;
    private final TransactionAuditLogRepository auditLogRepository;
    private final AuditLogService              auditLogService;
    private final AdaptorRegistry              adaptorRegistry;
    private final FulfilmentQueueService       fulfilmentQueueService;
    private final SmsNotificationService       smsNotificationService;
    private final ObjectMapper                 objectMapper;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final zw.co.payport.gateway.service.adaptor.omari.OmariPaymentAdaptor omariPaymentAdaptor;

    /**
     * Published on every status transition so a linked CheckoutSession
     * (if one exists) can be kept in sync -- fixes a real bug where a
     * session's own stored status only ever updated when something
     * happened to poll it (a customer's checkout page still open, or a
     * merchant viewing that one session directly). A merchant's
     * transaction LIST never triggered this, so completed payments could
     * sit shown as PENDING indefinitely, and the webhook that's supposed
     * to notify the merchant never fired either. Deliberately an event,
     * not a direct call, to avoid a circular dependency between this
     * service and CheckoutSessionService.
     */
    public record TransactionStatusChangedEvent(String transactionReference, String newStatus) {}
    private final BundleService                bundleService;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;
    private final zw.co.payport.gateway.payments.PaymentAttemptStore attemptStore;
    private final zw.co.payport.gateway.payments.PaymentCheckStore   checkStore;
    private final jakarta.persistence.EntityManager entityManager;
    private final zw.co.payport.gateway.recon.ReconExceptionStore reconExceptions;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // Helper method to safely convert Long to int
    private int toInt(Long value) {
        return value != null ? value.intValue() : 0;
    }

    @Transactional
    public TransactionResponse initiateTransaction(InitiateTransactionRequest dto, String ipAddress) {
        log.info("[ORCHESTRATOR] Initiating transaction: {}", dto.getReference());

        if (transactionRepository.existsByReference(dto.getReference())) {
            throw new DuplicateReferenceException(dto.getReference());
        }

        ProductType product = productTypeRepository
                .findByCodeAndIsActiveTrue(dto.getProductTypeCode())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Product type not found or inactive: " + dto.getProductTypeCode()));

        product.validateAmount(dto.getAmountCents());

        Transaction tx = Transaction.builder()
                .reference(dto.getReference())
                .status(TransactionStatus.INITIATED)
                .channel(TransactionChannel.fromString(dto.getChannel()))
                .productType(product)
                .recipientIdentifier(dto.getRecipientIdentifier())
                .amountCents(dto.getAmountCents())
                .currency(dto.getCurrency() != null ? dto.getCurrency() : "USD")
                .customerPhone(dto.getCustomerPhone())
                .customerEmail(dto.getCustomerEmail())
                .ipAddress(ipAddress)
                .retryCount(0)
                .omariOtpAttempts(0)
                .build();

        if (dto.getProductConfig() != null && !dto.getProductConfig().isEmpty()) {
            try {
                String productConfigJson = objectMapper.writeValueAsString(dto.getProductConfig());
                tx.setFulfilmentData(productConfigJson);
                log.info("[ORCHESTRATOR] Stored productConfig for {}: {}", dto.getReference(), productConfigJson);
            } catch (Exception e) {
                log.error("[ORCHESTRATOR] Failed to store productConfig: {}", e.getMessage());
            }
        }

        transactionRepository.save(tx);
        auditLogService.log(tx.getReference(), null, TransactionStatus.INITIATED,
                "SYSTEM", "Transaction created", null);

        log.info("[ORCHESTRATOR] Transaction created: {} status=INITIATED", dto.getReference());
        return TransactionResponse.from(tx);
    }

    /**
     * Merchant checkout test-mode simulation. Reuses the exact same
     * PAYMENT_PENDING transition submitPayment() uses, then feeds a
     * simulated event through the real handlePaymentCallback() path --
     * so state transitions, audit logging, and fulfilment enqueueing all
     * go through the same code as a genuine payment. The only thing
     * skipped is the actual call to a real EcoCash/OneMoney adaptor,
     * which is the entire point: a TEST-mode API key must be structurally
     * incapable of moving real money, not just trusted not to.
     */
    @Transactional
    public TransactionResponse simulateTestPayment(String reference, boolean simulateSuccess) {
        return simulateTestPayment(reference, simulateSuccess, null);
    }

    /** Test mode only: a simulated provider answer (failureMessage = the simulated provider's words on a decline). */
    @Transactional
    public TransactionResponse simulateTestPayment(String reference, boolean simulateSuccess, String failureMessage) {
        log.info("[ORCHESTRATOR] TEST MODE: simulating {} payment for: {}", simulateSuccess ? "successful" : "failed", reference);

        Transaction tx = loadTransaction(reference);
        assertTransition(tx, TransactionStatus.PAYMENT_PENDING);

        transitionStatus(tx, TransactionStatus.PAYMENT_PENDING,
                "TEST_MODE", "Simulated payment (merchant test API key)", null);
        tx.setPaymentSubmittedAt(LocalDateTime.now());
        transactionRepository.save(tx);

        PaymentProviderAdaptor.CallbackEvent simulatedEvent = PaymentProviderAdaptor.CallbackEvent.builder()
                .payportReference(tx.getReference())
                .providerReference("TEST_SIM_" + System.currentTimeMillis())
                .status(simulateSuccess ? "SUCCESS" : "FAILED")
                .amountCents(toInt(tx.getAmountCents()))
                .currency(tx.getCurrency())
                .rawPayload("{\"status\":\"TEST_MODE\",\"message\":\"Simulated by merchant test API key, no real provider was called\"}")
                .failureMessage(simulateSuccess ? null : failureMessage)
                .build();

        handlePaymentCallback(simulatedEvent);
        return TransactionResponse.from(loadTransaction(reference));
    }

    /**
     * Sends a payment to the provider, safely under failures (Phase 2, ADR-0010).
     *
     * 1. tx1 (committed BEFORE the provider call): lock the payment, move it to
     *    PAYMENT_PENDING, record the attempt as SENT and open a resolver check. Two
     *    identical /pay requests serialise on the row lock; the second finds the payment
     *    already pending and is refused, so the customer gets exactly one push.
     * 2. The provider call runs outside any database transaction.
     * 3. tx2 records what came back:
     *    - ACCEPTED: provider reference stored, "pending" SMS sent;
     *    - REJECTED (the provider definitely did not take the payment): PAYMENT_FAILED;
     *    - UNKNOWN (read timeout, 5xx, broken connection...): the customer may have been
     *      charged, so the payment stays PENDING with no failure SMS, and the resolver
     *      settles it by status query. It is never re-sent.
     *    A callback that arrived while the call was in flight has already moved the
     *    payment on; tx2 then records the attempt and leaves the payment alone.
     */
    public TransactionResponse submitPayment(PayTransactionRequest dto) throws Exception {
        log.info("[ORCHESTRATOR] Submitting payment for: {}", dto.getReference());

        PreparedSubmission p = requiresNew().execute(s -> prepareSubmission(dto));
        PaymentProviderAdaptor adaptor = adaptorRegistry.getPaymentAdaptor(p.providerCode());

        PaymentProviderAdaptor.PaymentSubmitResult result;
        try {
            result = adaptor.submitPayment(p.request());
            if (result == null) {
                result = PaymentProviderAdaptor.PaymentSubmitResult.builder()
                        .submitted(false).outcomeUnknown(true).message("Provider adaptor returned no result").build();
            }
        } catch (Exception e) {
            boolean unknown = zw.co.payport.gateway.payments.SubmissionErrors.isOutcomeUnknown(e);
            log.error("[ORCHESTRATOR] {} submit for {} threw {} -- outcome {}", p.providerCode(), p.reference(),
                    e.getClass().getSimpleName(), unknown ? "UNKNOWN" : "NOT SENT");
            result = PaymentProviderAdaptor.PaymentSubmitResult.builder()
                    .submitted(false).outcomeUnknown(unknown)
                    .message(unknown ? p.providerCode() + " call failed: " + e.getMessage()
                            : "Could not reach " + p.providerCode() + ". No money was taken.")
                    .rawResponse(e.getClass().getName() + ": " + e.getMessage())
                    .build();
        }

        final PaymentProviderAdaptor.PaymentSubmitResult outcome = result;
        Transaction tx = requiresNew().execute(s -> recordSubmissionOutcome(p, outcome));

        log.info("[ORCHESTRATOR] Payment submitted: {} attempt={} outcome={} status={}", tx.getReference(), p.attemptNo(),
                outcome.isSubmitted() ? "ACCEPTED" : outcome.isOutcomeUnknown() ? "UNKNOWN" : "REJECTED", tx.getStatus());

        TransactionResponse response = TransactionResponse.from(tx);
        // ZimSwitch: result.getMessage() IS the widget script URL (see ZimSwitchAdaptor.submitPayment),
        // saved as paymentProviderMessage; the frontend needs it to load the card form.
        if (outcome.isSubmitted() && "ZIMSWITCH".equalsIgnoreCase(p.providerCode())) {
            response.setWidgetJsUrl(outcome.getMessage());
            response.setPaymentCorrelator(outcome.getProviderReference());
        }
        return response;
    }

    private record PreparedSubmission(String reference, String providerCode, int attemptNo, String attemptReference,
                                      PaymentProviderAdaptor.PaymentRequest request) {}

    @org.springframework.beans.factory.annotation.Value("${payport.payments.max-attempts:3}")
    private int maxPaymentAttempts;

    private org.springframework.transaction.support.TransactionTemplate requiresNew() {
        org.springframework.transaction.support.TransactionTemplate t =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        t.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }

    /**
     * Starts an attempt on the payment intent. The first attempt is sent with the payment
     * reference; a new attempt is allowed only after the previous one DEFINITELY failed
     * (PAYMENT_FAILED), and is sent as reference-N because providers reject a reused reference.
     * A payment that timed out or got no response cannot be attempted again: the first
     * attempt might still be charged.
     */
    private PreparedSubmission prepareSubmission(PayTransactionRequest dto) {
        Transaction tx = lockTransaction(dto.getReference());
        assertTransition(tx, TransactionStatus.PAYMENT_PENDING);

        PaymentMethod paymentMethod = paymentMethodRepository
                .findByCodeAndIsActiveTrue(dto.getPaymentMethodCode())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Payment method not found or inactive: " + dto.getPaymentMethodCode()));
        String providerCode = paymentMethod.getCode().toUpperCase();
        adaptorRegistry.getPaymentAdaptor(providerCode); // fail before anything is recorded if there is no adaptor

        int attemptNo = attemptStore.nextAttemptNo(tx.getReference());
        if (attemptNo > maxPaymentAttempts) {
            throw new InvalidStateTransitionException(tx.getReference(), tx.getStatus(), TransactionStatus.PAYMENT_PENDING);
        }
        String attemptReference = zw.co.payport.gateway.payments.PaymentAttemptStore.attemptReference(tx.getReference(), attemptNo);

        tx.setPaymentMethod(paymentMethod);
        if (dto.getCustomerPhone() != null && !dto.getCustomerPhone().isBlank()) {
            tx.setCustomerPhone(dto.getCustomerPhone());
        }

        LocalDateTime now = LocalDateTime.now();
        transitionStatus(tx, TransactionStatus.PAYMENT_PENDING,
                "SYSTEM", "Payment attempt " + attemptNo + " submitted to " + providerCode + " as " + attemptReference, null);
        tx.setPaymentSubmittedAt(now);
        tx.setPaymentProviderCode(providerCode);
        tx.setPaymentProviderStatus("SENDING");
        tx.setPaymentAttemptReference(attemptReference);
        tx.setPaymentAttemptCount(attemptNo);
        tx.setPaymentOutcome(null);
        tx.setFailureReason(null);
        tx.setFailureDetails(null);
        tx.setFailedAt(null);
        if (attemptNo > 1) {
            tx.setProviderReference(null);
            tx.setPaymentCorrelator(null);
        }
        transactionRepository.save(tx);

        attemptStore.recordSent(tx.getReference(), attemptNo, attemptReference, providerCode, now);
        checkStore.open(tx.getReference(), providerCode, now,
                now.plus(zw.co.payport.gateway.scheduler.PaymentReconciliationScheduler.GRACE));

        PaymentProviderAdaptor.PaymentRequest req = PaymentProviderAdaptor.PaymentRequest.builder()
                .payportReference(attemptReference)
                .amountCents(toInt(tx.getAmountCents()))
                .currency(tx.getCurrency())
                .customerPhone(tx.getCustomerPhone())
                .description("Payport " + tx.getProductType().getName())
                .build();
        return new PreparedSubmission(tx.getReference(), providerCode, attemptNo, attemptReference, req);
    }

    private Transaction recordSubmissionOutcome(PreparedSubmission p, PaymentProviderAdaptor.PaymentSubmitResult result) {
        LocalDateTime now = LocalDateTime.now();
        Transaction tx = lockTransaction(p.reference());
        boolean stillPending = tx.getStatus() == TransactionStatus.PAYMENT_PENDING
                && p.attemptReference().equals(tx.getPaymentAttemptReference());

        if (result.isSubmitted()) {
            attemptStore.recordOutcome(p.reference(), p.attemptNo(), zw.co.payport.gateway.payments.PaymentAttemptStore.Status.ACCEPTED,
                    result.getProviderReference(), null, result.getMessage(), now);
            if (tx.getProviderReference() == null || stillPending) {
                tx.setProviderReference(result.getProviderReference());
            }
            if (tx.getPaymentCorrelator() == null || stillPending) {
                tx.setPaymentCorrelator(result.getProviderReference());
            }
            if (stillPending) {
                tx.setPaymentProviderStatus("SUBMITTED");
                if (result.getMessage() != null && !result.getMessage().isBlank()) {
                    tx.setPaymentProviderMessage(result.getMessage());
                }
                if (result.getRawResponse() != null) {
                    tx.setPaymentProviderRawResponse(truncate(result.getRawResponse(), 4000));
                }
            }
            transactionRepository.save(tx);
            return tx;
        }

        if (result.isOutcomeUnknown()) {
            attemptStore.recordOutcome(p.reference(), p.attemptNo(), zw.co.payport.gateway.payments.PaymentAttemptStore.Status.UNKNOWN,
                    null, "UNKNOWN_OUTCOME", result.getMessage(), now);
            if (stillPending) {
                tx.setPaymentProviderStatus("UNKNOWN");
                tx.setPaymentProviderMessage(truncate("Outcome unknown, confirming with the provider: " + result.getMessage(), 500));
                transactionRepository.save(tx);
                checkStore.checkSoon(p.reference(), now.plus(zw.co.payport.gateway.scheduler.PaymentReconciliationScheduler.GRACE));
            }
            log.warn("[ORCHESTRATOR] {} outcome UNKNOWN for {} -- left pending for the resolver, not failed and not re-sent",
                    p.providerCode(), p.attemptReference());
            return tx;
        }

        attemptStore.recordOutcome(p.reference(), p.attemptNo(), zw.co.payport.gateway.payments.PaymentAttemptStore.Status.REJECTED,
                null, "REJECTED", result.getMessage(), now);
        if (!stillPending) {
            // A callback already moved the payment on (e.g. confirmed) although the submit call says rejected:
            // the provider contradicts itself. Never undo a confirmation here; flag it.
            log.error("[ORCHESTRATOR] CONTRADICTION for {}: submit was rejected ({}) but the payment is already {} -- left as is; review",
                    p.reference(), result.getMessage(), tx.getStatus());
            return tx;
        }
        transitionStatus(tx, TransactionStatus.PAYMENT_FAILED,
                p.providerCode() + "_API", "Provider rejected: " + result.getMessage(), result.getRawResponse());
        tx.setFailedAt(now);
        tx.setFailureReason(providerMessageOr(result.getMessage(), p.providerCode() + " declined the payment."));
        tx.setFailureDetails(result.getRawResponse());
        tx.setPaymentProviderStatus("REJECTED");
        transactionRepository.save(tx);
        checkStore.close(p.reference(), zw.co.payport.gateway.payments.PaymentCheckStore.Status.RESOLVED, "rejected at submit", now);
        return tx;
    }

    /** The provider's own wording when there is one (owner decision: never replace it with ours). */
    static String providerMessageOr(String providerMessage, String fallback) {
        if (providerMessage == null || providerMessage.isBlank()) return fallback;
        return truncate(providerMessage.trim(), 500);
    }

    /** Outcome shown to the customer when the payment window closes without a definite provider answer. */
    public enum ExpiryOutcome { TIMED_OUT, NO_RESPONSE }

    /**
     * Closes the payment window: PAYMENT_PENDING -> PAYMENT_TIMEOUT with the outcome the
     * customer is told (TIMED_OUT or NO_RESPONSE), under the row lock and only if the payment
     * is still pending on the same attempt (a confirmation that just landed is never
     * overwritten). Background checks continue for late-payment detection; a late success is
     * then handled by the late-payment policy, never silently.
     */
    @Transactional
    public boolean expirePayment(String reference, String attemptReference, ExpiryOutcome outcome, String providerMessage) {
        Transaction tx = lockTransactionOrNull(reference);
        if (tx == null || tx.getStatus() != TransactionStatus.PAYMENT_PENDING) return false;
        if (attemptReference != null && !attemptReference.equals(tx.getPaymentAttemptReference())) return false;
        transitionStatus(tx, TransactionStatus.PAYMENT_TIMEOUT, "EXPIRY",
                outcome == ExpiryOutcome.NO_RESPONSE ? "No response from " + tx.getPaymentProviderCode() + " within the payment window"
                        : "Payment not completed within the payment window", providerMessage);
        tx.setPaymentOutcome(outcome.name());
        tx.setFailureReason(outcome == ExpiryOutcome.NO_RESPONSE
                ? "No response from " + providerName(tx) + ". The transaction timed out."
                : providerMessageOr(providerMessage, "The transaction timed out."));
        tx.setFailureDetails(providerMessage);
        transactionRepository.save(tx);
        return true;
    }

    private static String providerName(Transaction tx) {
        return tx.getPaymentMethod() != null && tx.getPaymentMethod().getName() != null ? tx.getPaymentMethod().getName()
                : String.valueOf(tx.getPaymentProviderCode());
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * Omari doesn't confirm via phone-side PIN push or an inbound
     * callback like every other provider -- it texts the customer a
     * one-time code that has to come back through Payport's own UI. This
     * is that second step: takes the code, submits it to Omari via
     * callPayment(), and on either outcome hands off to
     * handlePaymentCallback() with a normalized CallbackEvent -- the
     * exact same status-transition, SMS, and fulfilment-enqueue logic
     * every other provider's real callback already goes through, rather
     * than a second, parallel copy of that logic that could drift out of
     * sync with it over time.
     *
     * Omari-confirmed policy (direct from their team, not guessed): the
     * customer gets up to 3 OTP attempts, and Omari themselves enforce a
     * 5-minute validity window -- Payport deliberately runs no timer of
     * its own for that; an attempt after the code has expired just comes
     * back as another Omari error and is handled the same as any other
     * wrong one. A wrong OTP with attempts still remaining does NOT fail
     * the transaction -- it stays at PAYMENT_PENDING so the customer can
     * simply try again on the same screen, matching what Omari actually
     * allows rather than killing the whole payment on the first typo.
     */
    @Transactional
    public TransactionResponse confirmOmariOtp(String reference, String otp) throws Exception {
        if (otp == null || otp.isBlank()) {
            throw new IllegalArgumentException("The code is required");
        }

        Transaction tx = lockTransaction(reference);

        if (!"OMARI".equalsIgnoreCase(tx.getPaymentProviderCode())) {
            throw new IllegalStateException("This transaction isn't an Omari payment");
        }
        if (tx.getStatus() != TransactionStatus.PAYMENT_PENDING) {
            throw new IllegalStateException("This payment is no longer awaiting confirmation (current status: " + tx.getStatus() + ")");
        }

        zw.co.payport.gateway.model.omari.OmariPaymentRequest omariRequest =
                zw.co.payport.gateway.model.omari.OmariPaymentRequest.builder()
                        .msisdn(tx.getCustomerPhone())
                        .reference(tx.getProviderReference())
                        .otp(otp.trim())
                        .build();

        zw.co.payport.gateway.model.omari.OmariPaymentResponse omariResponse = omariPaymentAdaptor.callPayment(omariRequest);
        String rawPayload = objectMapper.writeValueAsString(omariResponse);

        boolean wrongOtp = omariResponse.isError() && isWrongOtpMessage(omariResponse.getMessage());
        String outOfAttemptsMessage = null;

        if (wrongOtp) {
            int attempts = (tx.getOmariOtpAttempts() == null ? 0 : tx.getOmariOtpAttempts()) + 1;
            tx.setOmariOtpAttempts(attempts);
            transactionRepository.save(tx);

            int remaining = 3 - attempts;
            if (remaining > 0) {
                // Still within Omari's allowed 3 attempts -- transaction
                // stays exactly as it was (PAYMENT_PENDING), no callback
                // fired at all. The customer just tries again.
                throw new IllegalStateException("The code entered was incorrect. You have " + remaining +
                        " attempt" + (remaining == 1 ? "" : "s") + " remaining.");
            }
            // Third wrong attempt -- Omari's own limit is exhausted, this
            // falls through to the normal failure path below, but with a
            // message that doesn't misleadingly invite another try.
            outOfAttemptsMessage = "The code entered was incorrect too many times. This payment can no longer be completed -- please start a new payment.";
        }

        PaymentProviderAdaptor.CallbackEvent event = PaymentProviderAdaptor.CallbackEvent.builder()
                .payportReference(PaymentStatusVerifier.attemptReference(tx))
                .providerReference(omariResponse.getPaymentReference() != null ? omariResponse.getPaymentReference() : tx.getProviderReference())
                .status(omariResponse.isError() ? "FAILED" : "SUCCESS")
                .amountCents(toInt(tx.getAmountCents()))
                .currency(tx.getCurrency())
                .timestamp(java.time.LocalDateTime.now().toString())
                .rawPayload(rawPayload)
                .failureMessage(omariResponse.isError()
                        ? providerMessageOr(omariResponse.getMessage(), outOfAttemptsMessage != null ? outOfAttemptsMessage : "O'mari declined the payment.")
                        : null)
                .build();

        handlePaymentCallback(event);

        return getTransaction(reference);
    }

    /** Matches Omari's observed "OTP invalid" wording -- deliberately narrow, since only a wrong-code error is retryable; a different failure (e.g. exceeds limit) genuinely ends the payment on the first attempt, retrying with a different code wouldn't fix that. */
    private boolean isWrongOtpMessage(String message) {
        if (message == null) return false;
        String m = message.toLowerCase();
        return m.contains("otp") && (m.contains("invalid") || m.contains("incorrect") || m.contains("wrong"));
    }

    /** What applying a provider event did. */
    public enum CallbackOutcome {
        /** The payment moved to CONFIRMED or FAILED. */
        APPLIED,
        /** Nothing to do: already in that state, or the event is not a final answer (PENDING). */
        NO_CHANGE,
        /** The event contradicts what is recorded (success for a failed/timed-out payment, failure for a paid one, wrong amount, a superseded attempt). Recorded as a reconciliation exception for a person. */
        CONFLICT,
        /** No such payment. */
        UNKNOWN_REF
    }

    /**
     * Applies a definite provider answer. Idempotent and safe under concurrency: the row is
     * locked, so of several identical or racing events exactly one changes the payment, and
     * a contradicting event is recorded as a reconciliation exception instead of applied or
     * silently ignored.
     *
     * The event's payportReference may carry an attempt suffix (reference-N); it must match
     * the current attempt. Only the provider's own wording is shown to the customer.
     */
    @Transactional
    public CallbackOutcome handlePaymentCallback(PaymentProviderAdaptor.CallbackEvent event) {
        String sentReference = event.getPayportReference();
        String reference = zw.co.payport.gateway.payments.PaymentAttemptStore.intentReference(sentReference);
        log.info("[ORCHESTRATOR] Processing payment callback for reference: {}", sentReference);

        Transaction tx = lockTransactionOrNull(reference);
        if (tx == null) {
            log.warn("[ORCHESTRATOR] Callback received for unknown reference: {}", sentReference);
            return CallbackOutcome.UNKNOWN_REF;
        }

        boolean success = "SUCCESS".equals(event.getStatus());
        boolean failed = "FAILED".equals(event.getStatus());
        TransactionStatus current = tx.getStatus();
        String provider = tx.getPaymentProviderCode();

        // A provider reference that carries an attempt suffix must belong to the current attempt.
        boolean suffixed = sentReference != null && !sentReference.equals(reference);
        String currentAttempt = tx.getPaymentAttemptReference() != null ? tx.getPaymentAttemptReference() : tx.getReference();
        if (sentReference != null && (suffixed || tx.getPaymentAttemptCount() != null && tx.getPaymentAttemptCount() > 1)
                && !sentReference.equals(currentAttempt)) {
            if (success) {
                reconExceptions.raise("PAID_ON_SUPERSEDED_ATTEMPT", provider, tx.getReference(), event.getProviderReference(),
                        tx.getAmountCents(), event.getAmountCents(), tx.getCurrency(),
                        "Provider confirmed attempt " + sentReference + " but the current attempt is " + currentAttempt, "LIFECYCLE");
                log.error("[ORCHESTRATOR] CONFLICT for {}: success for superseded attempt {} (current {})", reference, sentReference, currentAttempt);
                return CallbackOutcome.CONFLICT;
            }
            return CallbackOutcome.NO_CHANGE;
        }

        if (!success && !failed) {
            return CallbackOutcome.NO_CHANGE;
        }

        if (current != TransactionStatus.PAYMENT_PENDING && current != TransactionStatus.PAYMENT_TIMEOUT) {
            boolean paid = !(current == TransactionStatus.INITIATED || current == TransactionStatus.PAYMENT_FAILED
                    || current == TransactionStatus.CANCELLED);
            if ((success && !paid && current != TransactionStatus.INITIATED) || (failed && paid)) {
                reconExceptions.raise(success ? "PAID_AFTER_FAILURE" : "FAILED_AFTER_PAID", provider, tx.getReference(),
                        event.getProviderReference(), tx.getAmountCents(), event.getAmountCents(), tx.getCurrency(),
                        "Provider says " + event.getStatus() + " but the payment is " + current, "LIFECYCLE");
                log.error("[ORCHESTRATOR] CONFLICT for {}: provider says {} but the payment is {} -- not changed; needs review",
                        tx.getReference(), event.getStatus(), current);
                return CallbackOutcome.CONFLICT;
            }
            log.info("[ORCHESTRATOR] Duplicate callback for {} - current status: {} - ignoring", tx.getReference(), current);
            return CallbackOutcome.NO_CHANGE;
        }

        if (event.getAmountCents() > 0 && event.getAmountCents() != toInt(tx.getAmountCents())) {
            reconExceptions.raise("AMOUNT_MISMATCH", provider, tx.getReference(), event.getProviderReference(),
                    tx.getAmountCents(), event.getAmountCents(), tx.getCurrency(),
                    "Provider reported " + event.getAmountCents() + " for a payment of " + tx.getAmountCents(), "LIFECYCLE");
            log.error("[ORCHESTRATOR] AMOUNT MISMATCH for {} - expected {} cents got {} cents - rejecting callback",
                    tx.getReference(), tx.getAmountCents(), event.getAmountCents());
            return CallbackOutcome.CONFLICT;
        }

        if (success && current == TransactionStatus.PAYMENT_TIMEOUT) {
            // Owner rule: once the customer has been told the transaction timed out, it is NEVER fulfilled -- not
            // automatically and not by a person. The money the provider took must be refunded.
            reconExceptions.raise("PAID_AFTER_TIMEOUT", provider, tx.getReference(), event.getProviderReference(),
                    tx.getAmountCents(), event.getAmountCents() > 0 ? event.getAmountCents() : tx.getAmountCents(), tx.getCurrency(),
                    "Provider confirmed payment after the customer was told " + tx.getPaymentOutcome() + ": refund required", "LIFECYCLE");
            log.error("[ORCHESTRATOR] LATE PAYMENT for {}: provider confirmed after the customer was told {} -- not fulfilled; refund required",
                    tx.getReference(), tx.getPaymentOutcome());
            return CallbackOutcome.CONFLICT;
        }

        if (event.getProviderReference() != null && !event.getProviderReference().isBlank()) {
            tx.setProviderReference(event.getProviderReference());
        }
        if (tx.getPaymentProviderCode() == null) {
            tx.setPaymentProviderCode(tx.getPaymentMethod() != null ? tx.getPaymentMethod().getCode() : "UNKNOWN");
        }

        if (success) {
            confirmPayment(tx, "PAYMENT_CALLBACK", "Payment confirmed by provider", event);
        } else {
            transitionStatus(tx, TransactionStatus.PAYMENT_FAILED,
                    "PAYMENT_CALLBACK", "Payment failed by provider", event.getRawPayload());
            tx.setFailedAt(LocalDateTime.now());
            tx.setPaymentOutcome(null);
            tx.setFailureReason(providerMessageOr(event.getFailureMessage(), providerName(tx) + " declined the payment."));
            tx.setFailureDetails(event.getRawPayload());
            tx.setPaymentProviderStatus("FAILED");
            if (event.getRawPayload() != null) {
                tx.setPaymentProviderRawResponse(truncate(event.getRawPayload(), 4000));
            }
            transactionRepository.save(tx);
            checkStore.close(tx.getReference(), zw.co.payport.gateway.payments.PaymentCheckStore.Status.RESOLVED, "FAILED", LocalDateTime.now());
            log.info("[ORCHESTRATOR] Payment failed for: {}", tx.getReference());
        }
        return CallbackOutcome.APPLIED;
    }

    private void confirmPayment(Transaction tx, String actor, String notes, PaymentProviderAdaptor.CallbackEvent event) {
        transitionStatus(tx, TransactionStatus.PAYMENT_CONFIRMED, actor, notes, event.getRawPayload());
        tx.setPaymentConfirmedAt(LocalDateTime.now());
        tx.setPaymentCorrelator(tx.getReference());
        tx.setPaymentProviderStatus("CONFIRMED");
        tx.setPaymentOutcome(null);
        tx.setFailureReason(null);
        tx.setPaymentConfirmedAmountCents(event.getAmountCents());
        tx.setPaymentConfirmedCurrency(event.getCurrency());
        if (event.getRawPayload() != null) {
            tx.setPaymentProviderRawResponse(truncate(event.getRawPayload(), 4000));
        }
        transactionRepository.save(tx);
        checkStore.close(tx.getReference(), zw.co.payport.gateway.payments.PaymentCheckStore.Status.RESOLVED, "SUCCESS", LocalDateTime.now());
        // No SMS here: the customer gets one SMS, when the transaction completes (owner decision).
        fulfilmentQueueService.enqueue(tx.getReference());
        log.info("[ORCHESTRATOR] Payment confirmed and fulfilment enqueued for: {}", tx.getReference());
    }

    /** What a delivery run did. retryAt is set when a definite failure has an automatic retry scheduled. */
    public record FulfilmentOutcome(Kind kind, LocalDateTime retryAt, String detail) {
        /** NOT_STARTED: the payment is still PAYMENT_CONFIRMED, so no delivery was ever sent; safe to queue again. */
        public enum Kind { DELIVERED, FAILED, UNKNOWN, SKIPPED, NOT_STARTED }
        static FulfilmentOutcome of(Kind k, String detail) { return new FulfilmentOutcome(k, null, detail); }
    }

    /**
     * Automatic retry of delivery failures whose provider code the specification marks as
     * temporary (DeliveryOutcomeRules). Providers without a code table are never retried.
     */
    @org.springframework.beans.factory.annotation.Value("${payport.fulfilment.auto-retry:true}")
    private boolean autoRetryDefiniteFailures = true;

    private static final int MAX_DELIVERY_ATTEMPTS = 4;
    /** Error code for a delivery request that provably never left PayPort (connection refused, unknown host). */
    private static final String NOT_SENT = "NOT_SENT";
    private static final int[] RETRY_DELAYS_SECONDS = {30, 120, 300, 600};

    private record PreparedDelivery(String reference, String providerCode, String label,
                                    FulfilmentProviderAdaptor.FulfilmentRequest request, String notSupported) {}

    /**
     * Delivers the service for a confirmed payment (Phase 2, same pattern as submitPayment):
     * FULFILMENT_PENDING is committed before the provider is called; the provider call runs
     * outside any database transaction; the outcome is recorded afterwards.
     *
     * A delivery whose outcome is unknown (timeout, broken connection, crash) is NEVER
     * delivered again automatically: the payment stays in FULFILMENT_PENDING and
     * resolveUnknownFulfilment() asks the provider (queryDelivery) or hands it to a person.
     * Called by FulfilmentWorker for a claimed job.
     */
    public FulfilmentOutcome executeFulfilment(String reference) throws Exception {
        log.info("[ORCHESTRATOR] Executing fulfilment for: {}", reference);

        PreparedDelivery p = requiresNew().execute(s -> prepareDelivery(reference));
        if (p == null) {
            return FulfilmentOutcome.of(FulfilmentOutcome.Kind.SKIPPED, "not in a deliverable state");
        }

        FulfilmentProviderAdaptor.FulfilmentResult result;
        if (p.notSupported() != null) {
            result = FulfilmentProviderAdaptor.FulfilmentResult.builder().success(false).message(p.notSupported()).build();
        } else {
            log.info("[ORCHESTRATOR] Calling {} adaptor.deliver for {} ({})", p.providerCode(), reference, p.label());
            try {
                result = adaptorRegistry.getFulfilmentAdaptor(p.providerCode()).deliver(p.request());
                if (result == null) {
                    result = FulfilmentProviderAdaptor.FulfilmentResult.builder().success(false).outcomeUnknown(true)
                            .message("Provider adaptor returned no result").build();
                }
            } catch (Exception e) {
                boolean unknown = zw.co.payport.gateway.payments.SubmissionErrors.isOutcomeUnknown(e);
                log.error("[ORCHESTRATOR] {} deliver for {} threw {} -- outcome {}", p.providerCode(), reference,
                        e.getClass().getSimpleName(), unknown ? "UNKNOWN" : "NOT DELIVERED");
                result = FulfilmentProviderAdaptor.FulfilmentResult.builder().success(false).outcomeUnknown(unknown)
                        .errorCode(unknown ? null : NOT_SENT)
                        .message(unknown ? p.providerCode() + " call failed: " + e.getMessage()
                                : "Could not reach " + p.providerCode() + "; the service was not delivered.")
                        .rawResponse(e.getClass().getName() + ": " + e.getMessage()).build();
            }
        }

        final FulfilmentProviderAdaptor.FulfilmentResult outcome = result;
        try {
            return requiresNew().execute(s -> recordDeliveryOutcome(p, outcome));
        } catch (RuntimeException e) {
            // The provider answered but we could not record it: treat as unknown, never deliver again.
            log.error("[ORCHESTRATOR] Could not record delivery outcome for {} (provider said success={}): {}",
                    reference, outcome.isSuccess(), e.getMessage(), e);
            return FulfilmentOutcome.of(FulfilmentOutcome.Kind.UNKNOWN, "outcome not recorded: " + e.getMessage());
        }
    }

    private PreparedDelivery prepareDelivery(String reference) {
        Transaction tx = lockTransactionOrNull(reference);
        if (tx == null) return null;
        if (tx.getStatus() != TransactionStatus.PAYMENT_CONFIRMED && tx.getStatus() != TransactionStatus.FULFILMENT_RETRY) {
            log.warn("[ORCHESTRATOR] Fulfilment attempted on transaction in status {}: {}", tx.getStatus(), reference);
            return null;
        }
        // FULFILMENT_RETRY is already "actively attempting again"; only a first attempt passes through FULFILMENT_PENDING.
        if (tx.getStatus() == TransactionStatus.PAYMENT_CONFIRMED) {
            transitionStatus(tx, TransactionStatus.FULFILMENT_PENDING, "SYSTEM", "Fulfilment started", null);
        }
        tx.setFulfilmentStartedAt(LocalDateTime.now());
        transactionRepository.save(tx);

        String productCode = tx.getProductType().getCode();
        String providerCode = deliveryProvider(tx);
        String label = productCode.startsWith("DATA_") ? "data bundle" : productCode.contains("AIRTIME") ? "airtime" : "service";
        String notSupported = null;
        if (productCode.startsWith("DATA_") && !"ECONET".equals(providerCode)) {
            notSupported = "Data bundles not supported for provider: " + tx.getProductType().getProviderCode();
        } else if (!adaptorRegistry.hasFulfilmentAdaptor(providerCode)) {
            notSupported = "No fulfilment adaptor for provider: " + providerCode;
        }

        FulfilmentProviderAdaptor.FulfilmentRequest req = FulfilmentProviderAdaptor.FulfilmentRequest.builder()
                .payportReference(tx.getReference())
                .recipientIdentifier(tx.getRecipientIdentifier())
                .amountCents(toInt(tx.getAmountCents()))
                .currency(tx.getCurrency())
                .productTypeCode(productCode)
                .productConfig(tx.getFulfilmentData())
                .build();
        return new PreparedDelivery(tx.getReference(), providerCode, label, req, notSupported);
    }

    private String deliveryProvider(Transaction tx) {
        String providerCode = tx.getProductType().getProviderCode();
        return providerCode == null ? "" : providerCode;
    }

    private FulfilmentOutcome recordDeliveryOutcome(PreparedDelivery p, FulfilmentProviderAdaptor.FulfilmentResult result) {
        Transaction tx = lockTransaction(p.reference());
        if (tx.getStatus() != TransactionStatus.FULFILMENT_PENDING && tx.getStatus() != TransactionStatus.FULFILMENT_RETRY) {
            log.error("[ORCHESTRATOR] Delivery outcome for {} (success={}) arrived but the payment is {} -- not changed; review",
                    p.reference(), result.isSuccess(), tx.getStatus());
            return FulfilmentOutcome.of(FulfilmentOutcome.Kind.SKIPPED, "payment is " + tx.getStatus());
        }
        if (result.isSuccess()) {
            completeDelivery(tx, p.providerCode(), capitalise(p.label()) + " delivered successfully",
                    result.getFulfilmentReference(), result.getFulfilmentData(), result.getRawResponse());
            return FulfilmentOutcome.of(FulfilmentOutcome.Kind.DELIVERED, result.getFulfilmentReference());
        }
        String code = zw.co.payport.gateway.payments.DeliveryOutcomeRules.code(p.providerCode(), result.getErrorCode(), result.getRawResponse());
        zw.co.payport.gateway.payments.DeliveryOutcomeRules.Verdict verdict =
                zw.co.payport.gateway.payments.DeliveryOutcomeRules.classify(p.providerCode(), code);
        if (result.isOutcomeUnknown() || verdict == zw.co.payport.gateway.payments.DeliveryOutcomeRules.Verdict.UNKNOWN) {
            tx.setFailureDetails(truncate(result.getRawResponse(), 4000));
            transactionRepository.save(tx);
            auditLogService.log(tx.getReference(), tx.getStatus(), tx.getStatus(), p.providerCode() + "_API",
                    "Delivery outcome UNKNOWN (" + result.getMessage() + "); confirming with the provider, will not re-deliver",
                    result.getRawResponse());
            log.warn("[ORCHESTRATOR] Delivery outcome UNKNOWN for {} -- not retried; resolving by query", p.reference());
            return FulfilmentOutcome.of(FulfilmentOutcome.Kind.UNKNOWN, result.getMessage());
        }
        LocalDateTime retryAt = handleFulfilmentFailure(tx, result.getMessage(), result.getRawResponse(), code,
                verdict == zw.co.payport.gateway.payments.DeliveryOutcomeRules.Verdict.RETRYABLE || NOT_SENT.equals(code));
        return new FulfilmentOutcome(FulfilmentOutcome.Kind.FAILED, retryAt, (code != null ? "[" + code + "] " : "") + result.getMessage());
    }

    private void completeDelivery(Transaction tx, String providerCode, String notes, String fulfilmentReference,
                                  String fulfilmentData, String raw) {
        transitionStatus(tx, TransactionStatus.COMPLETED, providerCode + "_API", notes, raw);
        if (fulfilmentReference != null) tx.setFulfilmentReference(fulfilmentReference);
        if (fulfilmentData != null) tx.setFulfilmentData(fulfilmentData);
        tx.setCompletedAt(LocalDateTime.now());
        transactionRepository.save(tx);
        smsNotificationService.sendDeliverySuccess(tx);
        log.info("[ORCHESTRATOR] COMPLETED: {} -> {} delivered to {}", tx.getReference(),
                tx.getProductType().getName(), maskPhone(tx.getRecipientIdentifier()));
    }

    /**
     * Resolves a delivery whose outcome is unknown by asking the provider. Never delivers.
     * Returns DELIVERED/FAILED when the provider gives a definite answer, UNKNOWN otherwise.
     */
    public FulfilmentOutcome resolveUnknownFulfilment(String reference) {
        Transaction snapshot = transactionRepository.findByReference(reference).orElse(null);
        if (snapshot == null) return FulfilmentOutcome.of(FulfilmentOutcome.Kind.SKIPPED, "no such payment");
        if (snapshot.getStatus() == TransactionStatus.PAYMENT_CONFIRMED) {
            // FULFILMENT_PENDING is committed before any delivery is sent, so nothing went out.
            return FulfilmentOutcome.of(FulfilmentOutcome.Kind.NOT_STARTED, "delivery was never sent");
        }
        if (snapshot.getStatus() != TransactionStatus.FULFILMENT_PENDING && snapshot.getStatus() != TransactionStatus.FULFILMENT_RETRY) {
            return FulfilmentOutcome.of(FulfilmentOutcome.Kind.SKIPPED, "payment is " + snapshot.getStatus());
        }
        String providerCode = deliveryProvider(snapshot);
        FulfilmentProviderAdaptor.FulfilmentStatusResult q;
        try {
            if (!adaptorRegistry.hasFulfilmentAdaptor(providerCode)) {
                return FulfilmentOutcome.of(FulfilmentOutcome.Kind.UNKNOWN, "no adaptor to query for " + providerCode);
            }
            String queryRef = snapshot.getFulfilmentReference() != null ? snapshot.getFulfilmentReference() : reference;
            q = adaptorRegistry.getFulfilmentAdaptor(providerCode).queryDelivery(queryRef, snapshot.getCurrency());
        } catch (Exception e) {
            return FulfilmentOutcome.of(FulfilmentOutcome.Kind.UNKNOWN, "delivery query failed: " + e.getMessage());
        }
        String status = q == null || q.getStatus() == null ? "UNKNOWN" : q.getStatus().toUpperCase();
        String detail = providerCode + " delivery query: " + status + (q != null && q.getMessage() != null ? " (" + q.getMessage() + ")" : "");
        if (!"DELIVERED".equals(status) && !"FAILED".equals(status) && !"NOT_FOUND".equals(status)) {
            return FulfilmentOutcome.of(FulfilmentOutcome.Kind.UNKNOWN, detail);
        }
        final FulfilmentProviderAdaptor.FulfilmentStatusResult answer = q;
        return requiresNew().execute(s -> {
            Transaction tx = lockTransaction(reference);
            if (tx.getStatus() != TransactionStatus.FULFILMENT_PENDING && tx.getStatus() != TransactionStatus.FULFILMENT_RETRY) {
                return FulfilmentOutcome.of(FulfilmentOutcome.Kind.SKIPPED, "payment is " + tx.getStatus());
            }
            if ("DELIVERED".equals(status)) {
                completeDelivery(tx, providerCode, "Delivery confirmed by provider query", answer.getFulfilmentReference(), null, detail);
                return FulfilmentOutcome.of(FulfilmentOutcome.Kind.DELIVERED, detail);
            }
            // NOT_FOUND: the provider never received the request, so sending it is safe. FAILED: the original
            // request failed; the spec (e.g. e-Solutions 4.2.2) says conclude, so no automatic retry.
            LocalDateTime retryAt = handleFulfilmentFailure(tx, answer.getMessage(), detail, null, "NOT_FOUND".equals(status));
            return new FulfilmentOutcome(FulfilmentOutcome.Kind.FAILED, retryAt, detail);
        });
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Records a DEFINITE delivery failure (the provider said no). Returns when to retry, or
     * null if no automatic retry is scheduled (ops decide: retry or refund).
     *
     * Retried automatically only when the provider's code is temporary per its specification
     * (DeliveryOutcomeRules.RETRYABLE) or the provider says it never received the request,
     * with back-off and at most MAX_DELIVERY_ATTEMPTS deliveries. The customer sees the
     * provider's own message; no SMS is sent for failures (owner decision).
     */
    private LocalDateTime handleFulfilmentFailure(Transaction tx, String providerMessage, String rawResponse, String code, boolean retryable) {
        String reference = tx.getReference();
        tx.setRetryCount((tx.getRetryCount() == null ? 0 : tx.getRetryCount()) + 1);
        tx.setFailureReason(providerMessageOr(providerMessage, "The service could not be delivered."));
        tx.setFailureDetails(rawResponse);

        transitionStatus(tx, TransactionStatus.FULFILMENT_FAILED, "SYSTEM",
                "Delivery attempt " + tx.getRetryCount() + " failed" + (code != null ? " [" + code + "]" : "") + ": " + providerMessage, rawResponse);

        if (autoRetryDefiniteFailures && retryable && tx.getRetryCount() < MAX_DELIVERY_ATTEMPTS) {
            int delaySeconds = RETRY_DELAYS_SECONDS[Math.min(tx.getRetryCount() - 1, RETRY_DELAYS_SECONDS.length - 1)];
            transitionStatus(tx, TransactionStatus.FULFILMENT_RETRY, "SYSTEM",
                    "Automatic retry in " + delaySeconds + "s (provider code " + code + " is temporary per its specification)", null);
            transactionRepository.save(tx);
            log.warn("[ORCHESTRATOR] Fulfilment failed for {} (attempt {}, code {}), retry in {}s",
                    reference, tx.getRetryCount(), code, delaySeconds);
            return LocalDateTime.now().plusSeconds(delaySeconds);
        }

        transactionRepository.save(tx);
        log.error("[ORCHESTRATOR] Fulfilment failed for {} (attempt {}, code {}); no automatic retry. Reason: {}. Ops: retry or refund.",
                reference, tx.getRetryCount(), code, providerMessage);
        return null;
    }

    @Transactional
    public TransactionResponse manualRetryFulfilment(String reference, String operatorUsername) {
        log.info("[ORCHESTRATOR] Manual retry requested for {} by {}", reference, operatorUsername);

        Transaction tx = lockTransaction(reference);

        if (tx.getStatus() != TransactionStatus.FULFILMENT_FAILED
                && tx.getStatus() != TransactionStatus.MANUAL_FULFILMENT) {
            throw new IllegalArgumentException(
                    "Manual retry only allowed on FULFILMENT_FAILED or MANUAL_FULFILMENT transactions");
        }

        transitionStatus(tx, TransactionStatus.FULFILMENT_RETRY,
                "OPS:" + operatorUsername,
                "Manual retry initiated by ops", null);
        transactionRepository.save(tx);

        fulfilmentQueueService.enqueuePriority(reference);

        auditLogService.log(reference, TransactionStatus.FULFILMENT_RETRY, TransactionStatus.FULFILMENT_RETRY,
                "OPS:" + operatorUsername, "Manual retry queued", null);

        return TransactionResponse.from(tx);
    }

    @Transactional
    public TransactionResponse initiateRefund(String reference, String operatorUsername, String reason) {
        log.info("[ORCHESTRATOR] Refund initiated for {} by {} reason: {}", reference, operatorUsername, reason);

        Transaction tx = loadTransaction(reference);

        if (!tx.getStatus().getAllowedTransitions().contains(TransactionStatus.REFUND_PENDING)) {
            throw new IllegalArgumentException(
                    "Refund not allowed for transaction in status: " + tx.getStatus());
        }

        transitionStatus(tx, TransactionStatus.REFUND_PENDING,
                "OPS:" + operatorUsername, "Refund initiated: " + reason, null);
        transactionRepository.save(tx);

        return TransactionResponse.from(tx);
    }

    /**
     * Actually completes a refund -- previously, initiateRefund() above
     * only ever moved a transaction to REFUND_PENDING, and nothing
     * anywhere ever finished the job; every refund sat permanently
     * unresolved. This is that missing final step: ops records how the
     * refund was actually paid back to the customer (bank transfer,
     * EcoCash, OneMoney, Innbucks, Omari -- done manually, outside this
     * system, not an automatic provider-side reversal) and the
     * transaction is marked REFUNDED for good.
     *
     * Callable directly from FULFILMENT_FAILED/MANUAL_FULFILMENT/COMPLETED
     * too, not only from an already-REFUND_PENDING transaction -- ops
     * doing this as one action ("here's proof I refunded them") rather
     * than two separate steps.
     */
    @Transactional
    public TransactionResponse completeManualRefund(String reference, String operatorUsername,
                                                       String refundReference, String refundMethod, String reason) {
        log.info("[ORCHESTRATOR] Manual refund completion for {} by {} via {} ref {}",
                reference, operatorUsername, refundMethod, refundReference);

        if (refundReference == null || refundReference.isBlank()) {
            throw new IllegalArgumentException("A refund reference is required");
        }

        Transaction tx = loadTransaction(reference);

        if (tx.getStatus() != TransactionStatus.REFUND_PENDING) {
            if (!tx.getStatus().getAllowedTransitions().contains(TransactionStatus.REFUND_PENDING)) {
                throw new IllegalArgumentException("Refund not allowed for transaction in status: " + tx.getStatus());
            }
            transitionStatus(tx, TransactionStatus.REFUND_PENDING,
                    "OPS:" + operatorUsername, "Refund initiated: " + (reason != null ? reason : "Ops-initiated refund"), null);
        }

        tx.setRefundReference(refundReference.trim());
        tx.setRefundMethod(refundMethod);
        tx.setRefundedAt(LocalDateTime.now());

        transitionStatus(tx, TransactionStatus.REFUNDED,
                "OPS:" + operatorUsername,
                "Refund completed via " + (refundMethod != null ? refundMethod : "manual transfer") + ", reference: " + refundReference,
                null);
        transactionRepository.save(tx);

        return TransactionResponse.from(tx);
    }

    @Transactional(readOnly = true)
    public TransactionResponse getTransaction(String reference) {
        Transaction tx = loadTransaction(reference);
        List<TransactionAuditLog> timeline = auditLogRepository
                .findByTransactionReferenceOrderByCreatedAtAsc(reference);
        return TransactionResponse.fromWithTimeline(tx, timeline);
    }

    private Transaction loadTransaction(String reference) {
        return transactionRepository.findByReference(reference)
                .orElseThrow(() -> new TransactionNotFoundException(reference));
    }

    private Transaction lockTransaction(String reference) {
        Transaction tx = lockTransactionOrNull(reference);
        if (tx == null) throw new TransactionNotFoundException(reference);
        return tx;
    }

    /**
     * SELECT ... FOR UPDATE, then refresh: if the entity was already in this persistence
     * context, the lock query alone would hand back its possibly stale in-memory state.
     * (The lock query auto-flushes pending changes first, so refresh loses nothing.)
     */
    private Transaction lockTransactionOrNull(String reference) {
        if (reference == null) return null;
        Transaction tx = transactionRepository.lockByReference(reference).orElse(null);
        if (tx != null) entityManager.refresh(tx);
        return tx;
    }

    private void assertTransition(Transaction tx, TransactionStatus newStatus) {
        TransactionStatus currentStatus = tx.getStatus();
        if (!currentStatus.canTransitionTo(newStatus)) {
            throw new InvalidStateTransitionException(tx.getReference(), currentStatus, newStatus);
        }
    }

    private void transitionStatus(Transaction tx,
                                  TransactionStatus newStatus,
                                  String actor,
                                  String notes,
                                  String payloadSnapshot) {

        TransactionStatus currentStatus = tx.getStatus();

        if (!currentStatus.canTransitionTo(newStatus)) {
            throw new InvalidStateTransitionException(tx.getReference(), currentStatus, newStatus);
        }

        int updated = transactionRepository.updateStatusOptimistic(
                tx.getReference(), currentStatus, newStatus, LocalDateTime.now());

        if (updated == 0) {
            log.warn("[ORCHESTRATOR] Optimistic lock conflict on {} - status may have changed", tx.getReference());
            throw new InvalidStateTransitionException(tx.getReference(), currentStatus, newStatus);
        }

        tx.setStatus(newStatus);
        auditLogService.log(tx.getReference(), currentStatus, newStatus, actor, notes, payloadSnapshot);
        eventPublisher.publishEvent(new TransactionStatusChangedEvent(tx.getReference(), newStatus.toString()));
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) return "****";
        return phone.substring(0, 4) + "****" + phone.substring(phone.length() - 3);
    }
}
