package zw.co.payport.gateway.service.adaptor.esolutions.moonlight;

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
import java.util.Map;

/**
 * Moonlight Life Assurance Payment Adaptor
 *
 * ── e-Solutions API: merchant=MOONLIGHT, product=MOONLIGHT ──────────────────
 *
 * SIMPLER than Nyaradzo (from spec section 5.13):
 *   • Allows payment of any amount within configured min/max limits
 *   • NO special customerData calculation required (no months × premium formula)
 *   • Customer Info response: customerData = POLICY_N# | HOLDER_NAME | ADDITIONAL
 *
 * FulfilmentRequest.recipientIdentifier = policy number
 * FulfilmentRequest.currency            = USD
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MoonlightAdaptor implements FulfilmentProviderAdaptor {

    private final ESolutionsClient client;
    private final ObjectMapper     mapper = new ObjectMapper();

    private static final String MERCHANT = "MOONLIGHT";
    private static final String PRODUCT  = "MOONLIGHT";

    @Override
    public String getProviderCode() { return "MOONLIGHT"; }

    public MoonlightCustomerInfo getCustomerInfo(String policyNumber, String sourcePhone) throws Exception {
        String vendorRef = buildVendorRef("MCI");
        log.info("[MOONLIGHT] Customer info for policy {}", maskPolicy(policyNumber));

        Map<String, Object> body = buildRequestBody(vendorRef, "310000", 100, "USD");
        body.put("merchantName",   MERCHANT);
        body.put("productName",    PRODUCT);
        body.put("utilityAccount", policyNumber.trim());
        if (sourcePhone != null) body.put("sourceMobile", sourcePhone);

        ESolutionsResponse resp = client.post("/billpayments/process", body);
        if (!resp.isSuccess()) {
            throw new RuntimeException(resp.getFailureMessage());
        }

        // customerData: POLICY_N# | HOLDER_NAME | ADDITIONAL_INFO
        String customerData = resp.getCustomerData();
        String[] parts = customerData != null ? customerData.split("\\|", -1) : new String[0];
        String policyRef     = parts.length > 0 ? parts[0].trim() : policyNumber;
        String holderName    = parts.length > 1 ? parts[1].trim() : "";
        String additionalInfo= parts.length > 2 ? parts[2].trim() : "";

        return new MoonlightCustomerInfo(holderName, policyRef, additionalInfo);
    }

    @Override
    public FulfilmentResult deliver(FulfilmentRequest request) throws Exception {
        String policyNumber = request.getRecipientIdentifier();
        String vendorRef    = buildVendorRef(request.getPayportReference());

        // Extract sourcePhone from productConfig if present
        String sourcePhone = extractSourcePhone(request.getProductConfig());

        log.info("[MOONLIGHT] Payment for policy {} ref={} amount={}",
                maskPolicy(policyNumber), vendorRef, request.getAmountCents());

        ESolutionsResponse resp;
        try {
            // Moonlight: no customerData needed in payment request
            resp = client.purchase(
                    MERCHANT, PRODUCT,
                    policyNumber.trim(),
                    sourcePhone,
                    request.getAmountCents(),
                    request.getCurrency(),
                    vendorRef,
                    null   // no customerData for Moonlight
            );
        } catch (java.net.http.HttpTimeoutException | java.net.ConnectException e) {
            log.warn("[MOONLIGHT] Timeout on payment for ref={}, resolving via resend", vendorRef);
            return resolveViaResend(vendorRef, policyNumber, request.getAmountCents(), request.getCurrency());
        }

        FulfilmentResult result = buildResult(resp, vendorRef, policyNumber);

        // Was returning "TIMEOUT_REQUIRES_RESEND:" straight to the
        // orchestrator as a plain failure -- this signal was produced
        // here but never consumed anywhere in the codebase. eSolutions'
        // resend() is documented as required for this whole family of
        // merchants before ever concluding failure, since a payment may
        // already have gone through even though this specific round-trip
        // never confirmed it.
        if (result.getMessage() != null && result.getMessage().startsWith("TIMEOUT_REQUIRES_RESEND:")) {
            return resolveViaResend(vendorRef, policyNumber, request.getAmountCents(), request.getCurrency());
        }

        return result;
    }

    private FulfilmentResult buildResult(ESolutionsResponse resp, String vendorRef, String policyNumber) throws Exception {
        if (resp.isInProgress()) {
            return FulfilmentResult.builder()
                    .success(false).fulfilmentReference(vendorRef)
                    .message("TIMEOUT_REQUIRES_RESEND:" + vendorRef)
                    .rawResponse(resp.getRawJson()).build();
        }

        if (!resp.isSuccess()) {
            log.warn("[MOONLIGHT] Payment failed for ref={}: {}", vendorRef, resp.getFailureMessage());
            return FulfilmentResult.builder()
                    .success(false).message(resp.getFailureMessage())
                    .rawResponse(resp.getRawJson()).build();
        }

        log.info("[MOONLIGHT] Payment SUCCESS for ref={}", vendorRef);

        Map<String, Object> data = new HashMap<>();
        data.put("type",           "MOONLIGHT_PAYMENT");
        data.put("transactionRef", resp.getTransactionRef());
        data.put("policyNumber",   policyNumber.trim());
        data.put("narrative",      resp.getNarrative());
        data.put("currencyCode",   resp.getCurrencyCode());

        return FulfilmentResult.builder()
                .success(true)
                .fulfilmentReference(resp.getTransactionRef() != null
                        ? resp.getTransactionRef() : vendorRef)
                .fulfilmentData(mapper.writeValueAsString(data))
                .message("Moonlight payment successful")
                .rawResponse(resp.getRawJson())
                .build();
    }

    /**
     * eSolutions' documented warning applies here too: never conclude
     * failure without a resend -- a payment may already have gone
     * through even though this specific round-trip never confirmed it.
     * Reuses buildResult() against the resend's response, since it's the
     * same ESolutionsResponse shape.
     */
    private FulfilmentResult resolveViaResend(String originalVendorRef, String policyNumber, long amountCents, String currency) throws Exception {
        String resendVendorRef = buildVendorRef("MRS");
        log.info("[MOONLIGHT] Resolving ambiguous outcome for {} via resend (new ref {})", originalVendorRef, resendVendorRef);

        ESolutionsResponse resendResp;
        try {
            resendResp = client.resend(
                    MERCHANT, PRODUCT, policyNumber.trim(), amountCents, currency,
                    originalVendorRef, resendVendorRef
            );
        } catch (Exception e) {
            log.error("[MOONLIGHT] Resend itself failed for {}: {}", originalVendorRef, e.getMessage(), e);
            return FulfilmentResult.builder()
                    .success(false)
                    .outcomeUnknown(zw.co.payport.gateway.payments.SubmissionErrors.isOutcomeUnknown(e))
                    .fulfilmentReference(originalVendorRef)
                    .message("Could not confirm whether this payment already went through -- needs manual verification before retrying.")
                    .rawResponse("Resend attempt failed: " + e.getMessage())
                    .build();
        }

        FulfilmentResult resendResult = buildResult(resendResp, originalVendorRef, policyNumber);

        if (resendResult.getMessage() != null && resendResult.getMessage().startsWith("TIMEOUT_REQUIRES_RESEND:")) {
            return FulfilmentResult.builder()
                    .success(false)
                    .fulfilmentReference(originalVendorRef)
                    .message("Still awaiting confirmation from eSolutions -- needs a manual check before retrying, to avoid a duplicate payment.")
                    .rawResponse(resendResult.getRawResponse())
                    .build();
        }

        return resendResult;
    }

    @Override
    public FulfilmentStatusResult queryDelivery(String originalVendorRef) throws Exception {
        log.info("[MOONLIGHT] Resend lookup for ref={}", originalVendorRef);
        String newVendorRef = buildVendorRef("MRS");
        ESolutionsResponse resp = client.resend(
                MERCHANT, PRODUCT, null, 0, "USD", originalVendorRef, newVendorRef);

        if (resp.isInProgress()) return FulfilmentStatusResult.builder()
                .status("PENDING").fulfilmentReference(originalVendorRef).build();
        if (resp.isSuccess()) return FulfilmentStatusResult.builder()
                .status("DELIVERED").fulfilmentReference(resp.getTransactionRef()).build();
        return FulfilmentStatusResult.builder()
                .status("FAILED").fulfilmentReference(originalVendorRef)
                .message(resp.getFailureMessage()).build();
    }

    private Map<String, Object> buildRequestBody(String vendorRef, String processingCode,
                                                    long amountCents, String currencyCode) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("mti",              "0200");
        m.put("vendorReference",  vendorRef);
        m.put("processingCode",   processingCode);
        m.put("transactionAmount", amountCents);
        m.put("transmissionDate", LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMddyyHHmmss")));
        m.put("vendorNumber",     client.getVendorNumber());
        m.put("currencyCode",     currencyCode);
        m.put("apiVersion",       "02");
        return m;
    }

    private String buildVendorRef(String prefix) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String p  = prefix.replaceAll("[^A-Za-z0-9]", "").substring(0, Math.min(prefix.length(), 4));
        return (p + ts).substring(0, Math.min(p.length() + ts.length(), 20));
    }

    private String maskPolicy(String p) {
        if (p == null || p.length() < 4) return "****";
        return p.substring(0, 3) + "****";
    }

    private String extractSourcePhone(String productConfig) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> cfg = new ObjectMapper().readValue(productConfig, Map.class);
            return (String) cfg.get("sourcePhone");
        } catch (Exception e) { return null; }
    }

    public record MoonlightCustomerInfo(
        String holderName, String policyNumber, String additionalInfo
    ) {}
}
