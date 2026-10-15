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
    private final CustomerMessageTranslator     customerMessageTranslator;

    // When true, skips the real payment provider for ZESA/TelOne/Bulawayo/ZOL/
    // Nyaradzo/Moonlight bills and Econet airtime/data, simulating a successful
    // payment and proceeding straight to fulfilment. For end-to-end testing
    // with unfunded test accounts only -- must be false in production.
    private static final boolean TEST_BYPASS_BILL_PAYMENTS = false;

    private static final List<String> BYPASS_PRODUCT_CODES = List.of(
            "ZESA_TOKEN",
            "TELONE_BILL",
            "BULAWAYO_BILL",
            "ZOL_PAYMENT",
            "NYARADZO_PAYMENT",
            "MOONLIGHT_PAYMENT",
            "AIRTIME_ECONET",
            "DATA_ECONET"
    );

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
        log.info("[ORCHESTRATOR] TEST MODE: simulating {} payment for: {}", simulateSuccess ? "successful" : "failed", reference);

        Transaction tx = loadTransaction(reference);
        assertTransition(tx, TransactionStatus.PAYMENT_PENDING);

        transitionStatus(tx, TransactionStatus.PAYMENT_PENDING,
                "TEST_MODE", "Simulated payment (merchant test API key)", null);
        tx.setPaymentSubmittedAt(LocalDateTime.now());
        // PSP: marks the payment as simulated so the ledger, resolver and reports never treat it as real money.
        tx.setPaymentProviderCode("TEST_MODE");
        transactionRepository.save(tx);

        PaymentProviderAdaptor.CallbackEvent simulatedEvent = PaymentProviderAdaptor.CallbackEvent.builder()
                .payportReference(tx.getReference())
                .providerReference("TEST_SIM_" + System.currentTimeMillis())
                .status(simulateSuccess ? "SUCCESS" : "FAILED")
                .amountCents(toInt(tx.getAmountCents()))
                .currency(tx.getCurrency())
                .rawPayload("{\"status\":\"TEST_MODE\",\"message\":\"Simulated by merchant test API key, no real provider was called\"}")
                .build();

        handlePaymentCallback(simulatedEvent);
        return TransactionResponse.from(loadTransaction(reference));
    }

    @Transactional
    public TransactionResponse submitPayment(PayTransactionRequest dto) throws Exception {
        log.info("[ORCHESTRATOR] Submitting payment for: {}", dto.getReference());

        Transaction tx = loadTransaction(dto.getReference());
        assertTransition(tx, TransactionStatus.PAYMENT_PENDING);

        PaymentMethod paymentMethod = paymentMethodRepository
                .findByCodeAndIsActiveTrue(dto.getPaymentMethodCode())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Payment method not found or inactive: " + dto.getPaymentMethodCode()));

        tx.setPaymentMethod(paymentMethod);

        if (dto.getCustomerPhone() != null && !dto.getCustomerPhone().isBlank()) {
            tx.setCustomerPhone(dto.getCustomerPhone());
        }

        String productCode = tx.getProductType().getCode();
        boolean isBypassProduct = BYPASS_PRODUCT_CODES.contains(productCode);

        if (TEST_BYPASS_BILL_PAYMENTS && isBypassProduct) {
            log.info("[TEST_BYPASS] Skipping actual payment for product: {} reference: {}",
                    productCode, tx.getReference());

            transitionStatus(tx, TransactionStatus.PAYMENT_PENDING,
                    "TEST_BYPASS", "Test bypass - moving to pending", null);
            tx.setPaymentSubmittedAt(LocalDateTime.now());
            tx.setPaymentProviderCode("TEST_BYPASS");
            transactionRepository.save(tx);

            PaymentProviderAdaptor.CallbackEvent simulatedEvent = PaymentProviderAdaptor.CallbackEvent.builder()
                    .payportReference(tx.getReference())
                    .providerReference("TEST_BYPASS_" + System.currentTimeMillis())
                    .status("SUCCESS")
                    .amountCents(toInt(tx.getAmountCents()))
                    .currency(tx.getCurrency())
                    .rawPayload("{\"status\":\"TEST_BYPASS\", \"message\":\"Test bypass - payment simulated\"}")
                    .build();

            handlePaymentCallback(simulatedEvent);
            log.info("[TEST_BYPASS] Payment bypass completed for: {}", tx.getReference());
            return TransactionResponse.from(tx);
        }

        PaymentProviderAdaptor adaptor = adaptorRegistry.getPaymentAdaptor(dto.getPaymentMethodCode());

        PaymentProviderAdaptor.PaymentRequest paymentReq = PaymentProviderAdaptor.PaymentRequest.builder()
                .payportReference(tx.getReference())
                .amountCents(toInt(tx.getAmountCents()))
                .currency(tx.getCurrency())
                .customerPhone(tx.getCustomerPhone())
                .description("Payport " + tx.getProductType().getName())
                .build();

        transitionStatus(tx, TransactionStatus.PAYMENT_PENDING,
                "SYSTEM", "Payment submitted to " + dto.getPaymentMethodCode(), null);

        tx.setPaymentSubmittedAt(LocalDateTime.now());
        transactionRepository.save(tx);

        PaymentProviderAdaptor.PaymentSubmitResult result = adaptor.submitPayment(paymentReq);

        if (!result.isSubmitted()) {
            transitionStatus(tx, TransactionStatus.PAYMENT_FAILED,
                    paymentMethod.getCode() + "_API",
                    "Provider rejected: " + result.getMessage(),
                    result.getRawResponse());
            tx.setFailedAt(LocalDateTime.now());
            String customerMessage = customerMessageTranslator.forPaymentFailure(result.getMessage());
            tx.setFailureReason(customerMessage);
            tx.setFailureDetails(result.getRawResponse());
            transactionRepository.save(tx);
            smsNotificationService.sendPaymentFailed(tx, customerMessage);
        } else {
            tx.setProviderReference(result.getProviderReference());
            tx.setPaymentCorrelator(result.getProviderReference());
            tx.setPaymentProviderCode(dto.getPaymentMethodCode());
            tx.setPaymentProviderStatus("SUBMITTED");
            if (result.getMessage() != null && !result.getMessage().isBlank()) {
                tx.setPaymentProviderMessage(result.getMessage());
            }
            if (result.getRawResponse() != null) {
                String raw = result.getRawResponse();
                tx.setPaymentProviderRawResponse(raw.length() > 4000 ? raw.substring(0, 4000) : raw);
            }
            transactionRepository.save(tx);
            smsNotificationService.sendPaymentPending(tx, dto.getPaymentMethodCode());
        }

        log.info("[ORCHESTRATOR] Payment submitted: {} status={}", tx.getReference(), tx.getStatus());

        TransactionResponse response = TransactionResponse.from(tx);
        // TransactionResponse.from(tx) only pulls from persisted entity
        // columns, and there's no dedicated "widget URL" column -- for
        // ZimSwitch specifically, result.getMessage() IS that widget
        // script URL (see ZimSwitchAdaptor.submitPayment), already saved
        // as paymentProviderMessage above. Surfacing it here is what lets
        // the frontend actually load the card form; without this, the
        // field exists on the DTO but was never being filled in.
        if (result.isSubmitted() && "ZIMSWITCH".equalsIgnoreCase(dto.getPaymentMethodCode())) {
            response.setWidgetJsUrl(tx.getPaymentProviderMessage());
            response.setPaymentCorrelator(tx.getPaymentCorrelator());
        }
        return response;
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

        Transaction tx = loadTransaction(reference);

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
                .payportReference(tx.getReference())
                .providerReference(omariResponse.getPaymentReference() != null ? omariResponse.getPaymentReference() : tx.getProviderReference())
                .status(omariResponse.isError() ? "FAILED" : "SUCCESS")
                .amountCents(toInt(tx.getAmountCents()))
                .currency(tx.getCurrency())
                .timestamp(java.time.LocalDateTime.now().toString())
                .rawPayload(rawPayload)
                .failureMessage(omariResponse.isError()
                        ? (outOfAttemptsMessage != null ? outOfAttemptsMessage : customerMessageTranslator.forPaymentFailure(omariResponse.getMessage()))
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

    @Transactional
    public void handlePaymentCallback(PaymentProviderAdaptor.CallbackEvent event) {
        log.info("[ORCHESTRATOR] Processing payment callback for reference: {}", event.getPayportReference());

        Transaction tx = transactionRepository.findByReference(event.getPayportReference()).orElse(null);
        if (tx == null) {
            log.warn("[ORCHESTRATOR] Callback received for unknown reference: {}", event.getPayportReference());
            return;
        }

        if (tx.getStatus() != TransactionStatus.PAYMENT_PENDING && tx.getStatus() != TransactionStatus.PAYMENT_TIMEOUT) {
            log.info("[ORCHESTRATOR] Duplicate callback for {} - current status: {} - ignoring",
                    tx.getReference(), tx.getStatus());
            return;
        }

        if (event.getProviderReference() != null && !event.getProviderReference().isBlank()) {
            tx.setProviderReference(event.getProviderReference());
        }

        if (tx.getPaymentProviderCode() == null) {
            tx.setPaymentProviderCode(tx.getPaymentMethod() != null ? tx.getPaymentMethod().getCode() : "UNKNOWN");
        }

        if (event.getAmountCents() > 0 && event.getAmountCents() != toInt(tx.getAmountCents())) {
            log.error("[ORCHESTRATOR] AMOUNT MISMATCH for {} - expected {} cents got {} cents - rejecting callback",
                    tx.getReference(), tx.getAmountCents(), event.getAmountCents());
            return;
        }

        if ("SUCCESS".equals(event.getStatus())) {
            transitionStatus(tx, TransactionStatus.PAYMENT_CONFIRMED,
                    "PAYMENT_CALLBACK", "Payment confirmed by provider", event.getRawPayload());

            tx.setPaymentConfirmedAt(LocalDateTime.now());
            tx.setPaymentCorrelator(event.getPayportReference());
            tx.setPaymentProviderStatus("CONFIRMED");
            tx.setPaymentConfirmedAmountCents(event.getAmountCents());
            tx.setPaymentConfirmedCurrency(event.getCurrency());
            if (event.getRawPayload() != null) {
                String raw = event.getRawPayload();
                tx.setPaymentProviderRawResponse(raw.length() > 4000 ? raw.substring(0, 4000) : raw);
            }
            transactionRepository.save(tx);

            smsNotificationService.sendPaymentConfirmed(tx);
            fulfilmentQueueService.enqueue(tx.getReference());

            log.info("[ORCHESTRATOR] Payment confirmed and fulfilment enqueued for: {}", tx.getReference());

        } else if ("FAILED".equals(event.getStatus())) {
            transitionStatus(tx, TransactionStatus.PAYMENT_FAILED,
                    "PAYMENT_CALLBACK", "Payment failed by provider", event.getRawPayload());

            tx.setFailedAt(LocalDateTime.now());
            tx.setFailureReason(
                    event.getFailureMessage() != null && !event.getFailureMessage().isBlank()
                            ? event.getFailureMessage()
                            : "Your payment was declined by the provider." +
                                (event.getProviderReference() != null ? " Reference: " + event.getProviderReference() : "")
            );
            tx.setFailureDetails(event.getRawPayload());
            tx.setPaymentProviderStatus("FAILED");

            if (event.getRawPayload() != null) {
                String raw = event.getRawPayload();
                tx.setPaymentProviderRawResponse(raw.length() > 4000 ? raw.substring(0, 4000) : raw);
            }
            transactionRepository.save(tx);

            smsNotificationService.sendPaymentFailed(tx, "Payment was not approved");
            log.info("[ORCHESTRATOR] Payment failed for: {}", tx.getReference());
        }
    }

    /**
     * PSP: a payment whose outcome PayPort does not know yet (no callback
     * within the timeout window). Moves PAYMENT_PENDING -> PAYMENT_TIMEOUT
     * through the audited state machine. This is deliberately NOT a failure:
     * the customer may have approved the payment, so the PSP
     * UncertainPaymentResolver keeps asking the provider until it gets a
     * definitive answer or a human resolves it with evidence.
     */
    @Transactional
    public void markPaymentOutcomeUnknown(String reference, String reason) {
        Transaction tx = loadTransaction(reference);
        if (tx.getStatus() != TransactionStatus.PAYMENT_PENDING) {
            return;
        }
        transitionStatus(tx, TransactionStatus.PAYMENT_TIMEOUT, "PSP_RESOLVER", reason, null);
        tx.setFailureReason("We're confirming this payment with your provider. You'll get an SMS as soon as it's confirmed.");
        tx.setFailureDetails(reason);
        transactionRepository.save(tx);
    }

    @Transactional
    public void executeFulfilment(String reference) throws Exception {
        log.info("[ORCHESTRATOR] Executing fulfilment for: {}", reference);

        Transaction tx = loadTransaction(reference);

        if (tx.getStatus() != TransactionStatus.PAYMENT_CONFIRMED
                && tx.getStatus() != TransactionStatus.FULFILMENT_RETRY) {
            log.warn("[ORCHESTRATOR] Fulfilment attempted on transaction in status {}: {}",
                    tx.getStatus(), reference);
            return;
        }

        // Was unconditional -- correct for the first attempt
        // (PAYMENT_CONFIRMED -> FULFILMENT_PENDING is valid), but
        // FULFILMENT_RETRY has no path to FULFILMENT_PENDING at all (it
        // only allows COMPLETED or FULFILMENT_FAILED directly), so every
        // single manual retry threw InvalidStateTransitionException here
        // before the fulfilment attempt itself ever ran -- the provider
        // was never actually contacted. FULFILMENT_RETRY already
        // correctly represents "actively attempting again"; it doesn't
        // need to pass through FULFILMENT_PENDING a second time.
        if (tx.getStatus() == TransactionStatus.PAYMENT_CONFIRMED) {
            transitionStatus(tx, TransactionStatus.FULFILMENT_PENDING,
                    "SYSTEM", "Fulfilment started", null);
        }
        tx.setFulfilmentStartedAt(LocalDateTime.now());
        transactionRepository.save(tx);

        String productCode = tx.getProductType().getCode();

        if (productCode.startsWith("DATA_")) {
            log.info("[ORCHESTRATOR] Processing DATA product: {}", productCode);
            executeDataBundleFulfilment(tx);
        } else if (productCode.contains("AIRTIME")) {
            log.info("[ORCHESTRATOR] Processing AIRTIME product: {}", productCode);
            executeAirtimeFulfilment(tx);
        } else {
            executeRegularFulfilment(tx);
        }
    }

    private void executeDataBundleFulfilment(Transaction tx) throws Exception {
        String reference = tx.getReference();
        String providerCode = tx.getProductType().getProviderCode();

        log.info("[ORCHESTRATOR] Executing data bundle fulfilment for: {} provider: {}", reference, providerCode);

        String productConfigJson = tx.getFulfilmentData();

        log.info("[ORCHESTRATOR] Data bundle - Amount: {} cents, Currency: {}, ProductConfig: {}",
                tx.getAmountCents(), tx.getCurrency(), productConfigJson);

        if ("ECONET".equals(providerCode)) {
            // Use the standard deliver method which now handles bundles
            FulfilmentProviderAdaptor adaptor = adaptorRegistry.getFulfilmentAdaptor("ECONET");

            FulfilmentProviderAdaptor.FulfilmentRequest request = FulfilmentProviderAdaptor.FulfilmentRequest.builder()
                    .payportReference(tx.getReference())
                    .recipientIdentifier(tx.getRecipientIdentifier())
                    .amountCents(toInt(tx.getAmountCents()))
                    .currency(tx.getCurrency())
                    .productTypeCode(tx.getProductType().getCode())
                    .productConfig(productConfigJson)
                    .build();

            log.info("[ORCHESTRATOR] Calling adaptor.deliver for data bundle: {}", tx.getReference());

            FulfilmentProviderAdaptor.FulfilmentResult result = adaptor.deliver(request);

            if (result.isSuccess()) {
                transitionStatus(tx, TransactionStatus.COMPLETED,
                        providerCode + "_API",
                        "Data bundle delivered successfully",
                        result.getRawResponse());

                tx.setFulfilmentReference(result.getFulfilmentReference());
                tx.setFulfilmentData(result.getFulfilmentData());
                tx.setCompletedAt(LocalDateTime.now());
                transactionRepository.save(tx);

                smsNotificationService.sendDeliverySuccess(tx);
                log.info("[ORCHESTRATOR] COMPLETED: {} → Data bundle delivered to {}",
                        tx.getReference(), maskPhone(tx.getRecipientIdentifier()));
            } else {
            handleFulfilmentFailure(tx, "Data bundle delivery failed: " + result.getMessage(), result.getRawResponse(), result.getCustomerMessage());
            }
        } else {
            handleFulfilmentFailure(tx, "Data bundles not supported for provider: " + providerCode, null);
        }
    }

    private void executeAirtimeFulfilment(Transaction tx) throws Exception {
        String providerCode = tx.getProductType().getProviderCode();
        FulfilmentProviderAdaptor adaptor = adaptorRegistry.getFulfilmentAdaptor(providerCode);

        FulfilmentProviderAdaptor.FulfilmentRequest req = FulfilmentProviderAdaptor.FulfilmentRequest.builder()
                .payportReference(tx.getReference())
                .recipientIdentifier(tx.getRecipientIdentifier())
                .amountCents(toInt(tx.getAmountCents()))
                .currency(tx.getCurrency())
                .productTypeCode(tx.getProductType().getCode())
                .productConfig(tx.getFulfilmentData())
                .build();

        log.info("[ORCHESTRATOR] Calling airtime adaptor.deliver for {}", tx.getReference());

        FulfilmentProviderAdaptor.FulfilmentResult result = adaptor.deliver(req);

        if (result.isSuccess()) {
            transitionStatus(tx, TransactionStatus.COMPLETED,
                    providerCode + "_API",
                    "Airtime delivered successfully",
                    result.getRawResponse());

            tx.setFulfilmentReference(result.getFulfilmentReference());
            tx.setFulfilmentData(result.getFulfilmentData());
            tx.setCompletedAt(LocalDateTime.now());
            transactionRepository.save(tx);

            smsNotificationService.sendDeliverySuccess(tx);
            log.info("[ORCHESTRATOR] COMPLETED: {} → Airtime delivered to {}",
                    tx.getReference(), maskPhone(tx.getRecipientIdentifier()));
        } else {
            handleFulfilmentFailure(tx, result.getMessage(), result.getRawResponse(), result.getCustomerMessage());
        }
    }

    private void executeRegularFulfilment(Transaction tx) throws Exception {
        String providerCode = tx.getProductType().getProviderCode();
        FulfilmentProviderAdaptor adaptor = adaptorRegistry.getFulfilmentAdaptor(providerCode);

        FulfilmentProviderAdaptor.FulfilmentRequest req = FulfilmentProviderAdaptor.FulfilmentRequest.builder()
                .payportReference(tx.getReference())
                .recipientIdentifier(tx.getRecipientIdentifier())
                .amountCents(toInt(tx.getAmountCents()))
                .currency(tx.getCurrency())
                .productTypeCode(tx.getProductType().getCode())
                .productConfig(tx.getFulfilmentData())
                .build();

        log.info("[ORCHESTRATOR] Calling adaptor.deliver for {}", tx.getReference());

        FulfilmentProviderAdaptor.FulfilmentResult result = adaptor.deliver(req);

        if (result.isSuccess()) {
            transitionStatus(tx, TransactionStatus.COMPLETED,
                    providerCode + "_API",
                    "Service delivered successfully",
                    result.getRawResponse());

            tx.setFulfilmentReference(result.getFulfilmentReference());
            tx.setFulfilmentData(result.getFulfilmentData());
            tx.setCompletedAt(LocalDateTime.now());
            transactionRepository.save(tx);

            smsNotificationService.sendDeliverySuccess(tx);
            log.info("[ORCHESTRATOR] COMPLETED: {} → {} delivered to {}",
                    tx.getReference(), tx.getProductType().getName(),
                    maskPhone(tx.getRecipientIdentifier()));
        } else {
            handleFulfilmentFailure(tx, result.getMessage(), result.getRawResponse(), result.getCustomerMessage());
        }
    }

    private void handleFulfilmentFailure(Transaction tx, String failureMessage, String rawResponse) {
        handleFulfilmentFailure(tx, failureMessage, rawResponse, null);
    }

    /**
     * customerMessage is the already-translated, code-specific text an
     * adaptor may supply (e.g. EconetAirtimeAdaptor.translateEconetStatusCode,
     * built directly from Econet's own published status code table) --
     * when present, it's used as-is rather than being re-run through the
     * generic text-pattern CustomerMessageTranslator, which doesn't know
     * about provider-specific numeric codes and would either mismatch it
     * against an unrelated generic pattern or fail to match at all and
     * fall back to the fully generic message, discarding a genuinely
     * more specific translation that already existed. Null for callers
     * that don't have one, which still fall back to the generic
     * translator exactly as before.
     */
    private void handleFulfilmentFailure(Transaction tx, String failureMessage, String rawResponse, String customerMessage) {
        String reference = tx.getReference();
        tx.setRetryCount(tx.getRetryCount() + 1);
        // failureReason is what the customer actually sees (in the app and in SMS) —
        // the raw provider text (e.g. Econet's "(8030) IN failure") stays available
        // to ops in failureDetails and every log line below, unchanged.
        tx.setFailureReason(customerMessage != null && !customerMessage.isBlank()
                ? customerMessage
                : customerMessageTranslator.forFulfilmentFailure(failureMessage));
        tx.setFailureDetails(rawResponse);

        int maxRetries = 4;
        if (tx.getRetryCount() < maxRetries) {
            transitionStatus(tx, TransactionStatus.FULFILMENT_FAILED,
                    "SYSTEM",
                    "Delivery attempt " + tx.getRetryCount() + " failed: " + failureMessage,
                    rawResponse);
            transactionRepository.save(tx);

            int[] delaysSeconds = {30, 120, 300, 600};
            int delaySeconds = delaysSeconds[Math.min(tx.getRetryCount() - 1, delaysSeconds.length - 1)];
            fulfilmentQueueService.enqueueWithDelay(reference, delaySeconds);
            log.warn("[ORCHESTRATOR] Fulfilment failed for {} (attempt {}), retry in {}s - Reason: {}",
                    reference, tx.getRetryCount(), delaySeconds, failureMessage);
        } else {
            transitionStatus(tx, TransactionStatus.FULFILMENT_FAILED,
                    "SYSTEM",
                    "All " + maxRetries + " delivery attempts exhausted. Last error: " + failureMessage,
                    rawResponse);
            transactionRepository.save(tx);

            smsNotificationService.sendFulfilmentDelayed(tx);
            log.error("[ORCHESTRATOR] MAX RETRIES EXHAUSTED for {}. Reason: {}. Manual intervention required.",
                    reference, failureMessage);
        }
    }

    @Transactional
    public TransactionResponse manualRetryFulfilment(String reference, String operatorUsername) {
        log.info("[ORCHESTRATOR] Manual retry requested for {} by {}", reference, operatorUsername);

        Transaction tx = loadTransaction(reference);

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

        smsNotificationService.sendRefundInitiated(tx);

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
