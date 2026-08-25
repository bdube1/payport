package zw.co.payport.gateway.service.adaptor.esolutions.bulawayo;

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
 * City of Bulawayo (CoB) Bill Payment Adaptor
 *
 * ── e-Solutions API: merchant=BYOBILL, product=BYOBILL ──────────────────────
 *
 * Workflow:
 *   STEP 1 — customerInfo()  : validate account, get customer name
 *   STEP 2 — purchase()      : pay bill (processingCode U50000)
 *
 * No token issued — payment is confirmed via receiptNumber in response.
 *
 * FulfilmentRequest.recipientIdentifier = Bulawayo account number (alphanumeric e.g. 45232870W1)
 * FulfilmentRequest.currency            = USD
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BulawayoBillAdaptor implements FulfilmentProviderAdaptor {

    private final ESolutionsClient client;
    private final ObjectMapper     mapper = new ObjectMapper();

    private static final String MERCHANT = "BYOBILL";
    private static final String PRODUCT  = "BYOBILL";

    @Override
    public String getProviderCode() { return "BULAWAYO"; }

    public BulawayoCustomerInfo getCustomerInfo(String accountNumber, String currencyCode) throws Exception {
        String vendorRef = buildVendorRef("BCI");
        log.info("[BULAWAYO] Customer info for account {}", maskAccount(accountNumber));

        ESolutionsResponse resp = client.customerInfo(
                MERCHANT, PRODUCT, accountNumber.trim(), currencyCode, 0, vendorRef);

        if (!resp.isSuccess()) {
            throw new RuntimeException(resp.getFailureMessage());
        }

        return new BulawayoCustomerInfo(
            resp.getCustomerData(),
            accountNumber.trim()
        );
    }

    @Override
    public FulfilmentResult deliver(FulfilmentRequest request) throws Exception {
        String accountNumber = request.getRecipientIdentifier();
        String vendorRef     = buildVendorRef(request.getPayportReference());

        log.info("[BULAWAYO] Bill payment for account {} ref={} amount={}",
                maskAccount(accountNumber), vendorRef, request.getAmountCents());

        ESolutionsResponse resp;
        try {
            resp = client.purchase(
                    MERCHANT, PRODUCT,
                    accountNumber.trim(),
                    null,
                    request.getAmountCents(),
                    request.getCurrency(),
                    vendorRef,
                    null
            );
        } catch (java.net.http.HttpTimeoutException | java.net.ConnectException e) {
            return timeoutResult(vendorRef, e.getMessage());
        }

        if (resp.isInProgress()) {
            return FulfilmentResult.builder()
                    .success(false)
                    .fulfilmentReference(vendorRef)
                    .message("TIMEOUT_REQUIRES_RESEND:" + vendorRef)
                    .rawResponse(resp.getRawJson())
                    .build();
        }

        if (!resp.isSuccess()) {
            log.warn("[BULAWAYO] Payment failed for ref={}: {}", vendorRef, resp.getFailureMessage());
            return FulfilmentResult.builder()
                    .success(false)
                    .message(resp.getFailureMessage())
                    .rawResponse(resp.getRawJson())
                    .build();
        }

        log.info("[BULAWAYO] Payment SUCCESS for ref={}, receipt={}", vendorRef, resp.getReceiptNumber());

        Map<String, Object> data = new HashMap<>();
        data.put("type",          "BULAWAYO_BILL");
        data.put("receiptNumber", resp.getReceiptNumber());
        data.put("transactionRef",resp.getTransactionRef());
        data.put("merchantName",  resp.getMerchantName());
        data.put("utilityAccount",resp.getUtilityAccount());
        data.put("narrative",     resp.getNarrative());
        data.put("currencyCode",  resp.getCurrencyCode());

        return FulfilmentResult.builder()
                .success(true)
                .fulfilmentReference(resp.getTransactionRef() != null
                        ? resp.getTransactionRef() : vendorRef)
                .fulfilmentData(mapper.writeValueAsString(data))
                .message("Bulawayo bill payment successful. Receipt: " + resp.getReceiptNumber())
                .rawResponse(resp.getRawJson())
                .build();
    }

    @Override
    public FulfilmentStatusResult queryDelivery(String originalVendorRef) throws Exception {
        log.info("[BULAWAYO] Resend lookup for ref={}", originalVendorRef);
        String newVendorRef = buildVendorRef("BRS");
        ESolutionsResponse resp = client.resend(
                MERCHANT, PRODUCT, null, 0, "USD", originalVendorRef, newVendorRef);

        if (resp.isInProgress()) return FulfilmentStatusResult.builder()
                .status("PENDING").fulfilmentReference(originalVendorRef)
                .message("Still in progress — retry in 180 seconds").build();

        if (resp.isSuccess()) return FulfilmentStatusResult.builder()
                .status("DELIVERED").fulfilmentReference(resp.getTransactionRef())
                .message("Receipt: " + resp.getReceiptNumber()).build();

        return FulfilmentStatusResult.builder()
                .status("FAILED").fulfilmentReference(originalVendorRef)
                .message(resp.getFailureMessage()).build();
    }

    private FulfilmentResult timeoutResult(String vendorRef, String error) {
        return FulfilmentResult.builder()
                .success(false)
                .fulfilmentReference(vendorRef)
                .message("TIMEOUT_REQUIRES_RESEND:" + vendorRef)
                .rawResponse("TIMEOUT: " + error)
                .build();
    }

    private String buildVendorRef(String prefix) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String p  = prefix.replaceAll("[^A-Za-z0-9]", "").substring(0, Math.min(prefix.length(), 4));
        return (p + ts).substring(0, Math.min(p.length() + ts.length(), 20));
    }

    private String maskAccount(String acc) {
        if (acc == null || acc.length() < 4) return "****";
        return acc.substring(0, 2) + "****" + acc.substring(acc.length() - 2);
    }

    public record BulawayoCustomerInfo(String customerName, String accountNumber) {}
}
