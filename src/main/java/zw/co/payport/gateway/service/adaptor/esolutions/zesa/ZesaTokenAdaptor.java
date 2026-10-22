package zw.co.payport.gateway.service.adaptor.esolutions.zesa;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.service.adaptor.FulfilmentProviderAdaptor;
import zw.co.payport.gateway.service.adaptor.esolutions.ESolutionsClient;
import zw.co.payport.gateway.service.adaptor.esolutions.ESolutionsResponse;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ZesaTokenAdaptor implements FulfilmentProviderAdaptor {

    private final ESolutionsClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String MERCHANT = "ZETDC";
    private static final String PRODUCT = "ZETDC_PREPAID";
    private static final int METER_DIGITS = 11;

    @Override
    public String getProviderCode() { return "ZESA"; }

    @Override
    public boolean usesDeliveryReference() { return true; }

    public static String validateMeter(String meterNumber) {
        if (meterNumber == null) return "Meter number is required";
        String clean = meterNumber.trim().replaceAll("\\s+", "");
        if (!clean.matches("\\d+")) return "Meter number must contain digits only";
        if (clean.length() < METER_DIGITS) return "Meter number must be exactly 11 digits (too short)";
        if (clean.length() > METER_DIGITS) return "Meter number must be exactly 11 digits (too long)";
        return null;
    }

    public ZesaCustomerInfo getCustomerInfo(String meterNumber, String currencyCode) throws Exception {
        String validationError = validateMeter(meterNumber);
        if (validationError != null) {
            throw new IllegalArgumentException(validationError);
        }

        String vendorRef = buildVendorRef("CI");
        log.debug("[ZESA] ========== CUSTOMER INFO DEBUG ==========");
        log.info("[ZESA] Customer info for meter {} ({})", maskMeter(meterNumber), currencyCode);
        log.debug("[ZESA] Request currency code sent to eSolutions: {}", currencyCode);
        log.debug("[ZESA] Vendor reference: {}", vendorRef);

        ESolutionsResponse resp = client.customerInfo(
                MERCHANT, PRODUCT, meterNumber.trim(), currencyCode, 0, vendorRef);

        // DEBUG: Print RAW response before any processing
        String rawJson = resp.getRawJson();
        log.debug("[ZESA] ========== RAW eSolutions RESPONSE ==========");
        log.debug("[ZESA] Full JSON response: {}", rawJson);

        // DEBUG: Print ALL fields from the response
        log.debug("[ZESA] ========== PARSED RESPONSE FIELDS ==========");
        log.debug("[ZESA] responseCode: {}", resp.getResponseCode());
        log.debug("[ZESA] narrative: {}", resp.getNarrative());
        log.debug("[ZESA] currencyCode (from response): {}", resp.getCurrencyCode());
        log.debug("[ZESA] settlementCurrency: {}", resp.getSettlementCurrency());
        log.debug("[ZESA] customerData: {}", resp.getCustomerData());
        log.debug("[ZESA] utilityAccount: {}", resp.getUtilityAccount());
        log.debug("[ZESA] transactionRef: {}", resp.getTransactionRef());
        log.debug("[ZESA] merchantName: {}", resp.getMerchantName());
        log.debug("[ZESA] productName: {}", resp.getProductName());

        if (!resp.isSuccess()) {
            String errorMsg = buildErrorMessage(resp);
            log.error("[ZESA] Customer info failed for meter {}: {}", maskMeter(meterNumber), errorMsg);
            throw new RuntimeException(errorMsg);
        }

        String customerData = resp.getCustomerData();
        String customerName = "";
        String customerAddress = "";

        if (customerData != null && !customerData.isBlank()) {
            String[] lines = customerData.split("\n");
            if (lines.length > 0) {
                customerName = lines[0].trim();
            }
            if (lines.length > 1) {
                StringBuilder address = new StringBuilder();
                for (int i = 1; i < lines.length; i++) {
                    if (address.length() > 0) address.append(", ");
                    address.append(lines[i].trim());
                }
                customerAddress = address.toString();
            }
        }

        // Get the currency directly from response - NO MODIFICATION
        String meterCurrency = resp.getCurrencyCode();

        log.debug("[ZESA] ========== FINAL VALUES ==========");
        log.debug("[ZESA] customerName: {}", customerName);
        log.debug("[ZESA] customerAddress: {}", customerAddress);
        log.debug("[ZESA] meterCurrency (direct from resp.getCurrencyCode()): {}", meterCurrency);

        if (meterCurrency == null || meterCurrency.isBlank()) {
            log.warn("[ZESA] WARNING: No currency code in eSolutions response for meter {}", maskMeter(meterNumber));
            log.warn("[ZESA] This should not happen. Check eSolutions configuration.");
            meterCurrency = "USD";
            log.debug("[ZESA] Defaulting to: {}", meterCurrency);
        }

        log.debug("[ZESA] ========== END CUSTOMER INFO ==========");

        return new ZesaCustomerInfo(
                customerName,
                customerAddress,
                meterCurrency,
                meterNumber.trim()
        );
    }

    @Override
    public FulfilmentResult deliver(FulfilmentRequest request) throws Exception {
        String meterNumber = request.getRecipientIdentifier();

        String validationError = validateMeter(meterNumber);
        if (validationError != null) {
            return FulfilmentResult.builder()
                    .success(false)
                    .message(validationError)
                    .rawResponse(validationError)
                    .build();
        }

        String vendorRef = (request.getDeliveryReference() != null ? request.getDeliveryReference()
                : zw.co.payport.gateway.service.adaptor.esolutions.VendorReferences.forDelivery(request.getPayportReference(), 1));
        log.info("[ZESA] Token purchase for meter {} ref={} amount={} currency={}",
                maskMeter(meterNumber), vendorRef, request.getAmountCents(), request.getCurrency());

        ESolutionsResponse resp;
        try {
            resp = client.purchase(
                    MERCHANT, PRODUCT,
                    meterNumber.trim(),
                    null,
                    request.getAmountCents(),
                    request.getCurrency(),
                    vendorRef,
                    null
            );
        } catch (java.net.http.HttpTimeoutException | java.net.ConnectException e) {
            log.warn("[ZESA] Timeout on initial purchase for ref={}, resolving via resend. Error: {}",
                    vendorRef, e.getMessage());
            return resolveViaResend(vendorRef, request);
        }

        FulfilmentResult result = buildResult(resp, vendorRef, request);

        // buildResult() flags an in-progress response the same way, via
        // this message prefix -- previously returned straight to the
        // orchestrator as a plain failure, meaning ZESA's own explicit
        // warning above resend() was being violated: "Never conclude
        // failure without a resend for ZESA -- every generated token
        // draws vendor balance." A token may already exist and already
        // have drawn Payport's float even though this specific HTTP
        // round-trip never confirmed it.
        if (result.getMessage() != null && result.getMessage().startsWith("TIMEOUT_REQUIRES_RESEND:")) {
            return resolveViaResend(vendorRef, request);
        }

        return result;
    }

    /**
     * ZESA's own documented warning: never conclude failure without this
     * -- every generated token already draws vendor balance regardless
     * of whether Payport's HTTP round-trip confirmed it. Reuses
     * buildResult() against the resend's response, since it's the same
     * ESolutionsResponse shape -- no separate success/failure parsing
     * logic to keep in sync with the main path.
     */
    private FulfilmentResult resolveViaResend(String originalVendorRef, FulfilmentRequest request) throws Exception {
        String resendVendorRef = buildVendorRef("RS");
        log.info("[ZESA] Resolving ambiguous outcome for {} via resend (new ref {})", originalVendorRef, resendVendorRef);

        ESolutionsResponse resendResp;
        try {
            resendResp = client.resend(
                    MERCHANT, PRODUCT,
                    request.getRecipientIdentifier().trim(),
                    request.getAmountCents(),
                    request.getCurrency(),
                    originalVendorRef,
                    resendVendorRef
            );
        } catch (Exception e) {
            log.error("[ZESA] Resend itself failed for {}: {}", originalVendorRef, e.getMessage(), e);
            return FulfilmentResult.builder()
                    .success(false)
                    .outcomeUnknown(zw.co.payport.gateway.payments.SubmissionErrors.isOutcomeUnknown(e))
                    .fulfilmentReference(originalVendorRef)
                    .message("Could not confirm whether this token was already generated -- needs manual verification before retrying.")
                    .rawResponse("Resend attempt failed: " + e.getMessage())
                    .build();
        }

        FulfilmentResult resendResult = buildResult(resendResp, originalVendorRef, request);

        if (resendResult.getMessage() != null && resendResult.getMessage().startsWith("TIMEOUT_REQUIRES_RESEND:")) {
            // Still genuinely unresolved even after the resend -- don't
            // guess either way. Comes back as a failure so it lands in
            // the normal retry/attention-queue flow, with an honest
            // message rather than repeating the internal-only signal or
            // a false "failed".
            return FulfilmentResult.builder()
                    .success(false)
                    .fulfilmentReference(originalVendorRef)
                    .message("Still awaiting confirmation from ZESA -- needs a manual check before retrying, to avoid a duplicate token.")
                    .rawResponse(resendResult.getRawResponse())
                    .build();
        }

        return resendResult;
    }

    @Override
    public FulfilmentStatusResult queryDelivery(String originalVendorRef) throws Exception {
        return queryDelivery(originalVendorRef, "USD");
    }

    /** Resend/lookup in the payment's own currency (ZWG meters were looked up as USD before). */
    @Override
    public FulfilmentStatusResult queryDelivery(String originalVendorRef, String currency) throws Exception {
        log.info("[ZESA] Resend lookup for originalRef={} ({})", originalVendorRef, currency);
        String newVendorRef = buildVendorRef("RS");

        ESolutionsResponse resp = client.resend(
                MERCHANT, PRODUCT,
                null,
                0, currency == null || currency.isBlank() ? "USD" : currency,
                originalVendorRef,
                newVendorRef
        );

        if (resp.isInProgress()) {
            return FulfilmentStatusResult.builder()
                    .status("PENDING")
                    .fulfilmentReference(originalVendorRef)
                    .message("Transaction still in progress — retry in 180 seconds")
                    .build();
        }
        if (resp.isSuccess()) {
            return FulfilmentStatusResult.builder()
                    .status("DELIVERED")
                    .fulfilmentReference(resp.getTransactionRef())
                    .message(buildFulfilmentDataJson(resp))
                    .build();
        }
        return FulfilmentStatusResult.builder()
                .status("FAILED")
                .fulfilmentReference(originalVendorRef)
                .message(resp.getFailureMessage())
                .build();
    }

    private FulfilmentResult buildResult(ESolutionsResponse resp,
                                         String vendorRef,
                                         FulfilmentRequest request) {
        if (resp.isInProgress()) {
            return FulfilmentResult.builder()
                    .success(false)
                    .fulfilmentReference(vendorRef)
                    .message("TIMEOUT_REQUIRES_RESEND:" + vendorRef)
                    .rawResponse(resp.getRawJson())
                    .build();
        }

        if (!resp.isSuccess()) {
            String errorMessage = buildErrorMessage(resp);

            log.warn("[ZESA] Purchase failed for ref={}: {} (ResponseCode: {})",
                    vendorRef, errorMessage, resp.getResponseCode());

            return FulfilmentResult.builder()
                    .success(false)
                    .message(errorMessage)
                    .rawResponse(resp.getRawJson())
                    .build();
        }

        List<ESolutionsResponse.ZesaToken> tokens = resp.parseTokens();
        log.info("[ZESA] Token purchase SUCCESS for ref={}, {} token(s) received", vendorRef, tokens.size());
        tokens.forEach(t -> log.debug("[ZESA] Token: {}", t.getFormattedToken()));

        String fulfilmentJson = buildFulfilmentDataJson(resp);

        return FulfilmentResult.builder()
                .success(true)
                .fulfilmentReference(resp.getTransactionRef() != null ? resp.getTransactionRef() : vendorRef)
                .fulfilmentData(fulfilmentJson)
                .message("ZESA token delivered: " + tokens.size() + " token(s)")
                .rawResponse(resp.getRawJson())
                .build();
    }

    private String buildErrorMessage(ESolutionsResponse resp) {
        String responseCode = resp.getResponseCode();
        String narrative = resp.getNarrative();
        String rawMessage = resp.getFailureMessage();

        String baseMessage = (narrative != null && !narrative.isBlank()) ? narrative : rawMessage;

        if (responseCode != null) {
            switch (responseCode) {
                case "80":
                    return "Currency mismatch: This meter only accepts " +
                            (resp.getCurrencyCode() != null ? resp.getCurrencyCode() : "USD") +
                            ". Please pay in the correct currency.";
                case "14":
                    return "Invalid meter number. Please check and try again.";
                case "12":
                    return "Amount is below minimum or above maximum limit for this meter.";
                case "51":
                    return "Insufficient funds. Please recharge your wallet.";
                case "57":
                    return "Customer account has a reversal in progress. Please try again later.";
                case "63":
                    return "Security violation. Please contact support.";
                case "68":
                    return "Transaction timeout. Please check your transaction status before retrying.";
                case "94":
                    return "Duplicate transaction detected. Please check if your previous transaction was successful.";
                case "05":
                    if (baseMessage != null && baseMessage.contains("Currency mismatch")) {
                        return baseMessage;
                    }
                    return "Service temporarily unavailable. Please try again later.";
                default:
                    if (baseMessage != null && !baseMessage.isBlank()) {
                        return "[" + responseCode + "] " + baseMessage;
                    }
                    return "Transaction failed with code: " + responseCode;
            }
        }

        if (baseMessage != null && !baseMessage.isBlank()) {
            return baseMessage;
        }

        return "Transaction failed. Please try again or contact support.";
    }

    private String buildFulfilmentDataJson(ESolutionsResponse resp) {
        try {
            Map<String, Object> data = new HashMap<>();

            // Store the RAW eSolutions response fields - CRITICAL for SMS formatting
            if (resp.getToken() != null && !resp.getToken().isBlank()) {
                data.put("tokenRaw", resp.getToken());
                log.debug("[ZESA] Stored tokenRaw: {}", resp.getToken());

                // Parse token to extract correct values
                // Token format: token|units|rate|receipt|netAmountCents|taxAmountCents|taxRate
                String[] tokenParts = resp.getToken().split("\\|");
                if (tokenParts.length >= 6) {
                    // Energy is the NET AMOUNT (position 4), NOT the kWh value (position 1)
                    long netAmountCents = safeParseLong(tokenParts[4]);
                    double energyAmount = netAmountCents / 100.0;
                    data.put("energyAmount", energyAmount);
                    data.put("energyCents", netAmountCents);
                    log.debug("[ZESA] Parsed energy amount: {} from netAmountCents: {}", energyAmount, netAmountCents);

                    // VAT is tax amount (position 5)
                    long taxAmountCents = safeParseLong(tokenParts[5]);
                    double vatAmount = taxAmountCents / 100.0;
                    data.put("vatAmount", vatAmount);
                    data.put("vatCents", taxAmountCents);
                    log.debug("[ZESA] Parsed VAT amount: {} from taxAmountCents: {}", vatAmount, taxAmountCents);

                    // kWh is position 1 - for display only
                    if (tokenParts.length > 1) {
                        data.put("kwh", tokenParts[1]);
                    }
                }
            }

            if (resp.getFixedCharges() != null && !resp.getFixedCharges().isBlank()) {
                data.put("fixedChargesRaw", resp.getFixedCharges());
                String[] parts = resp.getFixedCharges().split("\\|");
                if (parts.length > 2) {
                    try {
                        int amountCents = Integer.parseInt(parts[2]);
                        double amount = amountCents / 100.0;
                        data.put("reaLevyAmount", amount);
                        data.put("reaLevyCents", amountCents);
                        log.debug("[ZESA] Parsed REA Levy: {}", amount);
                    } catch (NumberFormatException e) {
                        log.debug("Failed to parse REA Levy amount: {}", e.getMessage());
                    }
                }
            }

            // CRITICAL: Store arrears for debt display in SMS
            if (resp.getArrears() != null && !resp.getArrears().isBlank()) {
                data.put("arrears", resp.getArrears());
                String[] parts = resp.getArrears().split("\\|");
                if (parts.length > 2) {
                    try {
                        int debtCents = Integer.parseInt(parts[2]);
                        double debtAmount = debtCents / 100.0;
                        data.put("debtAmount", debtAmount);
                        data.put("debtCents", debtCents);
                        log.debug("[ZESA] Stored debt amount: {}", debtAmount);
                    } catch (NumberFormatException e) {
                        log.debug("Failed to parse debt amount: {}", e.getMessage());
                    }
                }
            }

            // Store other fields for reference
            data.put("type", "ZESA_TOKEN");
            data.put("merchantName", resp.getMerchantName());
            data.put("utilityAccount", resp.getUtilityAccount());
            data.put("receiptNumber", resp.getReceiptNumber());
            data.put("transactionRef", resp.getTransactionRef());
            data.put("settlementCurrency", resp.getSettlementCurrency());
            data.put("currencyCode", resp.getCurrencyCode());
            data.put("narrative", resp.getNarrative());
            data.put("responseCode", resp.getResponseCode());

            // Parse and store tokens for display
            List<ESolutionsResponse.ZesaToken> tokens = resp.parseTokens();
            data.put("tokenCount", tokens.size());
            if (!tokens.isEmpty()) {
                ESolutionsResponse.ZesaToken firstToken = tokens.get(0);
                data.put("tokenNumber", firstToken.tokenNumber());
                data.put("formattedToken", firstToken.getFormattedToken());
                data.put("units", firstToken.units());
            }

            return mapper.writeValueAsString(data);

        } catch (Exception e) {
            log.error("[ZESA] Failed to build fulfilmentData JSON: {}", e.getMessage());
            return "{\"type\":\"ZESA_TOKEN\",\"error\":\"Failed to parse token data\"}";
        }
    }

    private long safeParseLong(String value) {
        if (value == null || value.isBlank()) return 0L;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.debug("Failed to parse long value: {}", value);
            return 0L;
        }
    }

    private String buildVendorRef(String prefix) {
        return zw.co.payport.gateway.service.adaptor.esolutions.VendorReferences.lookup(prefix);
    }

    private String maskMeter(String meter) {
        if (meter == null || meter.length() < 6) return "****";
        return meter.substring(0, 4) + "****" + meter.substring(meter.length() - 3);
    }

    public record ZesaCustomerInfo(
            String customerName,
            String customerAddress,
            String meterCurrency,
            String meterNumber
    ) {
        public boolean isPaymentCurrencyAllowed(String paymentCurrency) {
            if ("USD".equalsIgnoreCase(meterCurrency)) {
                return "USD".equalsIgnoreCase(paymentCurrency);
            }
            return true;
        }

        public List<String> getAllowedPaymentCurrencies() {
            if ("USD".equalsIgnoreCase(meterCurrency)) {
                return List.of("USD");
            }
            return List.of("USD", "ZWG");
        }
    }
}