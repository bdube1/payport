package zw.co.payport.gateway.service.adaptor.esolutions.zol;

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
 * ZOL Internet Payment Adaptor
 *
 * ── e-Solutions API: merchant=ZOL, product=ZOL_DATA ─────────────────────────
 *
 * Workflow:
 *   STEP 1 — customerInfo()  : validate ZOL account number, get customer name
 *   STEP 2 — payment()       : pay (processingCode 520000)
 *
 * Response includes receiptNumber as proof of payment.
 *
 * FulfilmentRequest.recipientIdentifier = ZOL account number
 * FulfilmentRequest.currency            = USD
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ZolPaymentAdaptor implements FulfilmentProviderAdaptor {

    private final ESolutionsClient client;
    private final ObjectMapper     mapper = new ObjectMapper();

    private static final String MERCHANT = "ZOL";
    private static final String PRODUCT  = "ZOL_DATA";

    @Override
    public String getProviderCode() { return "ZOL"; }

    public ZolCustomerInfo getCustomerInfo(String accountNumber) throws Exception {
        String vendorRef = buildVendorRef("ZCI");
        log.info("[ZOL] Customer info for account {}", maskAccount(accountNumber));

        ESolutionsResponse resp = client.customerInfo(
                MERCHANT, PRODUCT, accountNumber.trim(), "USD", 0, vendorRef);

        if (!resp.isSuccess()) {
            throw new RuntimeException(resp.getFailureMessage());
        }

        return new ZolCustomerInfo(resp.getCustomerData(), accountNumber.trim());
    }

    @Override
    public FulfilmentResult deliver(FulfilmentRequest request) throws Exception {
        String accountNumber = request.getRecipientIdentifier();
        String vendorRef     = buildVendorRef(request.getPayportReference());
        String sourcePhone   = request.getProductConfig() != null
                ? extractSourcePhone(request.getProductConfig()) : null;

        log.info("[ZOL] Payment for account {} ref={} amount={}",
                maskAccount(accountNumber), vendorRef, request.getAmountCents());

        ESolutionsResponse resp;
        try {
            resp = client.payment(
                    MERCHANT, PRODUCT,
                    accountNumber.trim(),
                    sourcePhone,
                    request.getAmountCents(),
                    request.getCurrency(),
                    vendorRef,
                    null
            );
        } catch (java.net.http.HttpTimeoutException | java.net.ConnectException e) {
            log.warn("[ZOL] Timeout on payment for ref={}, resolving via resend", vendorRef);
            return resolveViaResend(vendorRef, accountNumber, request.getAmountCents(), request.getCurrency());
        }

        FulfilmentResult result = buildResult(resp, vendorRef, accountNumber);

        // Was returning "TIMEOUT_REQUIRES_RESEND:" straight to the
        // orchestrator as a plain failure -- this signal was produced
        // here but never consumed anywhere in the codebase. eSolutions'
        // resend() is documented as required for this whole family of
        // merchants before ever concluding failure, since a payment may
        // already have gone through even though this specific round-trip
        // never confirmed it.
        if (result.getMessage() != null && result.getMessage().startsWith("TIMEOUT_REQUIRES_RESEND:")) {
            return resolveViaResend(vendorRef, accountNumber, request.getAmountCents(), request.getCurrency());
        }

        return result;
    }

    private FulfilmentResult buildResult(ESolutionsResponse resp, String vendorRef, String accountNumber) throws Exception {
        if (resp.isInProgress()) {
            return FulfilmentResult.builder()
                    .success(false).fulfilmentReference(vendorRef)
                    .message("TIMEOUT_REQUIRES_RESEND:" + vendorRef)
                    .rawResponse(resp.getRawJson()).build();
        }

        if (!resp.isSuccess()) {
            log.warn("[ZOL] Payment failed for ref={}: {}", vendorRef, resp.getFailureMessage());
            return FulfilmentResult.builder()
                    .success(false).message(resp.getFailureMessage())
                    .rawResponse(resp.getRawJson()).build();
        }

        log.info("[ZOL] Payment SUCCESS for ref={}, receipt={}", vendorRef, resp.getReceiptNumber());

        Map<String, Object> data = new HashMap<>();
        data.put("type",          "ZOL_PAYMENT");
        data.put("receiptNumber", resp.getReceiptNumber());
        data.put("transactionRef",resp.getTransactionRef());
        data.put("merchantName",  MERCHANT);
        data.put("utilityAccount",accountNumber.trim());
        data.put("narrative",     resp.getNarrative());
        data.put("currencyCode",  resp.getCurrencyCode());

        return FulfilmentResult.builder()
                .success(true)
                .fulfilmentReference(resp.getTransactionRef() != null
                        ? resp.getTransactionRef() : vendorRef)
                .fulfilmentData(mapper.writeValueAsString(data))
                .message("ZOL payment successful. Receipt: " + resp.getReceiptNumber())
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
    private FulfilmentResult resolveViaResend(String originalVendorRef, String accountNumber, long amountCents, String currency) throws Exception {
        String resendVendorRef = buildVendorRef("ZRS");
        log.info("[ZOL] Resolving ambiguous outcome for {} via resend (new ref {})", originalVendorRef, resendVendorRef);

        ESolutionsResponse resendResp;
        try {
            resendResp = client.resend(
                    MERCHANT, PRODUCT, accountNumber.trim(), amountCents, currency,
                    originalVendorRef, resendVendorRef
            );
        } catch (Exception e) {
            log.error("[ZOL] Resend itself failed for {}: {}", originalVendorRef, e.getMessage(), e);
            return FulfilmentResult.builder()
                    .success(false)
                    .fulfilmentReference(originalVendorRef)
                    .message("Could not confirm whether this payment already went through -- needs manual verification before retrying.")
                    .rawResponse("Resend attempt failed: " + e.getMessage())
                    .build();
        }

        FulfilmentResult resendResult = buildResult(resendResp, originalVendorRef, accountNumber);

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
        log.info("[ZOL] Resend lookup for ref={}", originalVendorRef);
        String newVendorRef = buildVendorRef("ZRS");
        ESolutionsResponse resp = client.resend(
                MERCHANT, PRODUCT, null, 0, "USD", originalVendorRef, newVendorRef);

        if (resp.isInProgress()) return FulfilmentStatusResult.builder()
                .status("PENDING").fulfilmentReference(originalVendorRef)
                .message("Still in progress").build();
        if (resp.isSuccess()) return FulfilmentStatusResult.builder()
                .status("DELIVERED").fulfilmentReference(resp.getTransactionRef())
                .message("Receipt: " + resp.getReceiptNumber()).build();
        return FulfilmentStatusResult.builder()
                .status("FAILED").fulfilmentReference(originalVendorRef)
                .message(resp.getFailureMessage()).build();
    }

    private String buildVendorRef(String prefix) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String p  = prefix.replaceAll("[^A-Za-z0-9]", "").substring(0, Math.min(prefix.length(), 4));
        return (p + ts).substring(0, Math.min(p.length() + ts.length(), 20));
    }

    private String maskAccount(String acc) {
        if (acc == null || acc.length() < 4) return "****";
        return acc.substring(0, 2) + "****";
    }

    private String extractSourcePhone(String productConfig) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> cfg = new ObjectMapper().readValue(productConfig, Map.class);
            return (String) cfg.get("sourcePhone");
        } catch (Exception e) { return null; }
    }

    public record ZolCustomerInfo(String customerName, String accountNumber) {}
}
