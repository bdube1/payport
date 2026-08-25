package zw.co.payport.gateway.service.adaptor.ecocash;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import zw.co.payport.gateway.service.adaptor.PaymentProviderAdaptor;

import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EcoCash Payment Adaptor
 *
 * Integrates with the Econet EcoCash API for payments via USSD push.
 * Based on EcoCash Instant Payments (EIP) API Specification
 *
 * Supported Operations:
 * - Charge Request (Payment) - Section 1
 * - Refund Request (Reversal) - Section 2
 * - Query Transaction - Section 3
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EcoCashAdaptor implements PaymentProviderAdaptor {

    @Value("${payport.ecocash.base-url}")
    private String baseUrl;

    @Value("${payport.ecocash.notify-url}")
    private String notifyUrl;

    @Value("${payport.ecocash.merchant-code}")
    private String merchantCode;

    @Value("${payport.ecocash.merchant-pin}")
    private String merchantPin;

    @Value("${payport.ecocash.merchant-number}")
    private String merchantNumber;

    @Value("${payport.ecocash.country-code}")
    private String countryCode;

    @Value("${payport.ecocash.terminal-id}")
    private String terminalID;

    @Value("${payport.ecocash.location}")
    private String location;

    @Value("${payport.ecocash.super-merchant-name}")
    private String superMerchantName;

    @Value("${payport.ecocash.merchant-name}")
    private String merchantName;

    @Value("${payport.ecocash.username}")
    private String username;

    @Value("${payport.ecocash.password}")
    private String password;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // Transaction status descriptions
    private static final Map<String, String> TX_STATUS_DESCRIPTIONS = new LinkedHashMap<>();
    static {
        TX_STATUS_DESCRIPTIONS.put("CHARGED", "Initial status - USSD push sent, awaiting customer approval");
        TX_STATUS_DESCRIPTIONS.put("COMPLETED", "Transaction completed successfully - Customer approved payment");
        TX_STATUS_DESCRIPTIONS.put("FAILED", "Transaction failed - Customer rejected or system error");
        TX_STATUS_DESCRIPTIONS.put("PENDING", "Transaction pending - Waiting for customer action");
    }

    @Override
    public String getProviderCode() {
        return "ECOCASH";
    }

    // ============================================================
    // 1. CHARGE REQUEST (Payment) - Section 1
    // ============================================================

    @Override
    public PaymentSubmitResult submitPayment(PaymentRequest request) throws Exception {
        log.info("[ECOCASH] Submitting payment for reference: {}", request.getPayportReference());

        double amountInDollars = request.getAmountCents() / 100.0;
        String formattedPhone = normalisePhone(request.getCustomerPhone());

        Map<String, Object> payload = buildChargeRequestPayload(request, amountInDollars, formattedPhone);
        
        logEcoCashRequest(payload, amountInDollars, request.getCurrency());

        String jsonBody = objectMapper.writeValueAsString(payload);
        HttpHeaders headers = createAuthHeaders();
        HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);

        String url = baseUrl + "/transactions/amount";

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            
            Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), Map.class);
            String transactionStatus = (String) responseBody.get("transactionOperationStatus");
            String clientCorrelator = (String) responseBody.get("clientCorrelator");
            
            logEcoCashResponse(transactionStatus, clientCorrelator);

            boolean submitted = true;

            return PaymentSubmitResult.builder()
                    .submitted(submitted)
                    .providerReference(request.getPayportReference())
                    .message("EcoCash USSD push sent to " + maskPhone(request.getCustomerPhone()))
                    .rawResponse(response.getBody())
                    .build();

        } catch (Exception e) {
            log.error("[ECOCASH] Charge request failed: {}", e.getMessage());
            return PaymentSubmitResult.builder()
                    .submitted(false)
                    .message("EcoCash API call failed: " + e.getMessage())
                    .rawResponse(e.getMessage())
                    .build();
        }
    }

    // ============================================================
    // 2. REFUND REQUEST (Reversal) - Section 2
    // ============================================================

    public RefundResult submitRefund(RefundRequest request) throws Exception {
        log.info("[ECOCASH] Submitting refund for original transaction: {}", request.getOriginalEcocashReference());

        double amountInDollars = request.getAmountCents() / 100.0;

        Map<String, Object> payload = buildRefundRequestPayload(request, amountInDollars);
        
        logRefundRequest(payload, amountInDollars, request.getCurrency());

        String jsonBody = objectMapper.writeValueAsString(payload);
        HttpHeaders headers = createAuthHeaders();
        HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);

        String url = baseUrl + "/transactions/refund";

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            
            Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), Map.class);
            String transactionStatus = (String) responseBody.get("transactionOperationStatus");
            
            logRefundResponse(transactionStatus);

            return new RefundResult(true, transactionStatus, response.getBody());
        } catch (Exception e) {
            log.error("[ECOCASH] Refund request failed: {}", e.getMessage());
            return new RefundResult(false, e.getMessage(), null);
        }
    }

    // ============================================================
    // 3. QUERY TRANSACTION - Section 3
    // ============================================================

    public QueryResult queryTransaction(String clientCorrelator, String endUserId) throws Exception {
        log.info("[ECOCASH] Querying transaction for clientCorrelator: {}", clientCorrelator);

        // Normalizes internally now rather than requiring every caller to
        // remember to do it themselves before passing endUserId in --
        // submitPayment() always normalizes before building its own
        // payload, and a caller here (e.g. a reconciliation job querying
        // with a raw stored customer phone) shouldn't have to duplicate
        // that or risk getting the format wrong.
        String normalizedEndUserId = normalisePhone(endUserId);

        HttpHeaders headers = createAuthHeaders();
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        String url = baseUrl + "/" + normalizedEndUserId + "/transactions/amount/" + clientCorrelator;

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            
            Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), Map.class);
            String transactionStatus = (String) responseBody.get("transactionOperationStatus");
            String ecocashReference = (String) responseBody.get("ecocashReference");
            
            logQueryResponse(transactionStatus, ecocashReference);

            return new QueryResult(responseBody);
        } catch (Exception e) {
            log.error("[ECOCASH] Query transaction failed: {}", e.getMessage());
            throw e;
        }
    }

    // ============================================================
    // CALLBACK HANDLING
    // ============================================================

    @Override
    public CallbackValidationResult validateCallback(Map<String, String> headers, String rawBody) {
        try {
            if (rawBody == null || rawBody.isBlank()) {
                log.warn("[ECOCASH] Empty callback body");
                return CallbackValidationResult.builder()
                        .valid(false)
                        .failureReason("Empty callback body")
                        .build();
            }

            log.info("[ECOCASH] Callback received");
            return CallbackValidationResult.builder().valid(true).build();
        } catch (Exception e) {
            log.error("[ECOCASH] Callback validation error: {}", e.getMessage());
            return CallbackValidationResult.builder()
                    .valid(false)
                    .failureReason("Validation exception: " + e.getMessage())
                    .build();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public CallbackEvent parseCallback(String rawBody) throws Exception {
        Map<String, Object> payload = objectMapper.readValue(rawBody, Map.class);

        String payportRef = (String) payload.get("clientCorrelator");
        String txStatus = (String) payload.get("transactionOperationStatus");
        String ecocashRef = (String) payload.get("ecocashReference");
        String description = TX_STATUS_DESCRIPTIONS.getOrDefault(txStatus, "Unknown status");

        logCallbackResult(txStatus, description, ecocashRef);

        long amountCents = 0;
        try {
            Map<String, Object> paymentAmount = (Map<String, Object>) payload.get("paymentAmount");
            if (paymentAmount != null) {
                Map<String, Object> chargingInfo = (Map<String, Object>) paymentAmount.get("charginginformation");
                if (chargingInfo != null) {
                    Object amtObj = chargingInfo.get("amount");
                    if (amtObj instanceof Number) {
                        amountCents = Math.round(((Number) amtObj).doubleValue() * 100);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[ECOCASH] Could not extract amount from callback: {}", e.getMessage());
        }

        return CallbackEvent.builder()
                .payportReference(payportRef)
                .providerReference(ecocashRef != null ? ecocashRef : payportRef)
                .status(mapEcoCashStatus(txStatus))
                .amountCents(amountCents)
                .currency("USD")
                .timestamp(java.time.LocalDateTime.now().toString())
                .rawPayload(rawBody)
                .build();
    }

    @Override
    public PaymentStatusResult queryStatus(String providerReference) throws Exception {
        log.info("[ECOCASH] Querying status for reference: {}", providerReference);
        return PaymentStatusResult.builder()
                .status("UNKNOWN")
                .providerReference(providerReference)
                .message("Use queryTransaction method for detailed status")
                .build();
    }

    // ============================================================
    // PRIVATE METHODS
    // ============================================================

    private Map<String, Object> buildChargeRequestPayload(PaymentRequest request, double amount, String formattedPhone) {
        Map<String, Object> payload = new LinkedHashMap<>();
        
        payload.put("clientCorrelator", request.getPayportReference());
        payload.put("notifyUrl", notifyUrl);
        payload.put("referenceCode", request.getPayportReference());
        payload.put("tranType", "MER");
        payload.put("endUserId", formattedPhone);
        payload.put("remarks", request.getDescription() != null ? request.getDescription() : "PayPort Payment");
        payload.put("transactionOperationStatus", "Charged");

        // Payment amount structure
        Map<String, Object> paymentAmount = new LinkedHashMap<>();
        
        Map<String, Object> chargingInfo = new LinkedHashMap<>();
        chargingInfo.put("amount", amount);
        chargingInfo.put("currency", request.getCurrency());
        chargingInfo.put("description", "PayPort Payment");
        paymentAmount.put("charginginformation", chargingInfo);

        Map<String, Object> chargeMetaData = new LinkedHashMap<>();
        chargeMetaData.put("channel", "WEB");
        chargeMetaData.put("purchaseCategoryCode", "Online Payment");
        chargeMetaData.put("onBeHalfOf", merchantName);
        paymentAmount.put("chargeMetaData", chargeMetaData);
        
        payload.put("paymentAmount", paymentAmount);

        // Merchant details
        payload.put("merchantCode", merchantCode);
        payload.put("merchantPin", merchantPin);
        payload.put("merchantNumber", merchantNumber);
        payload.put("currencyCode", request.getCurrency());
        payload.put("countryCode", countryCode);
        payload.put("terminalID", terminalID);
        payload.put("location", location);
        payload.put("superMerchantName", superMerchantName);
        payload.put("merchantName", merchantName);

        return payload;
    }

    private Map<String, Object> buildRefundRequestPayload(RefundRequest request, double amount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        
        payload.put("clientCorrelator", request.getClientCorrelator());
        payload.put("referenceCode", request.getReferenceCode());
        payload.put("endUserId", normalisePhone(request.getEndUserId()));
        payload.put("originalEcocashReference", request.getOriginalEcocashReference());
        payload.put("tranType", "REF");
        payload.put("remark", request.getRemark());

        // Payment amount structure
        Map<String, Object> paymentAmount = new LinkedHashMap<>();
        
        Map<String, Object> chargingInfo = new LinkedHashMap<>();
        chargingInfo.put("amount", amount);
        chargingInfo.put("currency", request.getCurrency());
        chargingInfo.put("description", "Refund Payment");
        paymentAmount.put("charginginformation", chargingInfo);

        Map<String, Object> chargeMetaData = new LinkedHashMap<>();
        chargeMetaData.put("channel", "WEB");
        chargeMetaData.put("purchaseCategoryCode", "Online Refund");
        chargeMetaData.put("onBeHalfOf", merchantName);
        paymentAmount.put("chargeMetaData", chargeMetaData);
        
        payload.put("paymentAmount", paymentAmount);

        payload.put("merchantCode", merchantCode);
        payload.put("merchantPin", merchantPin);
        payload.put("merchantNumber", merchantNumber);
        payload.put("currencyCode", request.getCurrency());
        payload.put("countryCode", countryCode);
        payload.put("terminalID", terminalID);
        payload.put("location", location);
        payload.put("superMerchantName", superMerchantName);
        payload.put("merchantName", merchantName);

        return payload;
    }

    private HttpHeaders createAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        String auth = username + ":" + password;
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
        headers.set("Authorization", "Basic " + encodedAuth);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
        return headers;
    }

    // ============================================================
    // LOGGING METHODS
    // ============================================================

    private void logEcoCashRequest(Map<String, Object> payload, double amount, String currency) {
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    ECOCASH CHARGE REQUEST                        ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ clientCorrelator: {}", payload.get("clientCorrelator"));
        log.info("║ endUserId:        {}", payload.get("endUserId"));
        log.info("║ amount:           {} {}", amount, currency);
        log.info("║ currency:         {}", payload.get("currencyCode"));
        log.info("║ merchantCode:     {}", maskMerchantCode(payload.get("merchantCode")));
        log.info("║ merchantNumber:   {}", maskMerchantNumber(payload.get("merchantNumber")));
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logEcoCashResponse(String transactionStatus, String clientCorrelator) {
        String statusIcon = "CHARGED".equals(transactionStatus) ? "⏳ PENDING" :
                           "COMPLETED".equals(transactionStatus) ? "✅ SUCCESS" :
                           "FAILED".equals(transactionStatus) ? "❌ FAILED" : "❓ UNKNOWN";
        String description = TX_STATUS_DESCRIPTIONS.getOrDefault(transactionStatus, "Unknown status");
        
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    ECOCASH CHARGE RESPONSE                       ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Status:        {} ({})", statusIcon, transactionStatus);
        log.info("║ clientCorrelator: {}", clientCorrelator);
        log.info("║ Description:   {}", description);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logRefundRequest(Map<String, Object> payload, double amount, String currency) {
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    ECOCASH REFUND REQUEST                        ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ clientCorrelator:     {}", payload.get("clientCorrelator"));
        log.info("║ originalEcocashReference: {}", payload.get("originalEcocashReference"));
        log.info("║ refundAmount:         {} {}", amount, currency);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logRefundResponse(String transactionStatus) {
        String statusIcon = "COMPLETED".equals(transactionStatus) ? "✅ SUCCESS" : "⏳ PENDING";
        String description = "COMPLETED".equals(transactionStatus) ? "Refund processed successfully" : "Refund pending";
        
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    ECOCASH REFUND RESPONSE                       ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Status:        {} ({})", statusIcon, transactionStatus);
        log.info("║ Description:   {}", description);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logQueryResponse(String transactionStatus, String ecocashReference) {
        String description = TX_STATUS_DESCRIPTIONS.getOrDefault(transactionStatus, "Unknown status");
        
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    ECOCASH QUERY RESPONSE                        ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Status:        {} - {}", transactionStatus, description);
        log.info("║ EcoCash Ref:   {}", ecocashReference);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logCallbackResult(String txStatus, String description, String ecocashRef) {
        String statusIcon = "COMPLETED".equals(txStatus) ? "✅ SUCCESS" :
                           "FAILED".equals(txStatus) ? "❌ FAILED" : "⏳ PENDING";
        
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    ECOCASH CALLBACK RECEIVED                     ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Status:        {} ({})", statusIcon, txStatus);
        log.info("║ Description:   {}", description);
        log.info("║ EcoCash Ref:   {}", ecocashRef != null ? ecocashRef : "N/A");
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    /** Public so the reconciliation scheduler can map a queryTransaction() result the same way a real callback would be mapped -- one source of truth for what each EcoCash status string actually means. */
    public String mapEcoCashStatus(String ecoCashStatus) {
        if (ecoCashStatus == null) return "PENDING";
        return switch (ecoCashStatus.toUpperCase()) {
            // Was including "CHARGED" here -- but per TX_STATUS_DESCRIPTIONS
            // above (same file), "CHARGED" is explicitly documented as the
            // *initial* status: "USSD push sent, awaiting customer
            // approval" -- not a completed payment. Treating it as SUCCESS
            // risked confirming a payment before the customer had actually
            // approved anything.
            case "COMPLETED", "SUCCESS" -> "SUCCESS";
            case "FAILED", "REJECTED", "DECLINED" -> "FAILED";
            default -> "PENDING";
        };
    }

    private String normalisePhone(String phone) {
        if (phone == null) return "";
        String digits = phone.replaceAll("\\D", "");
        if (digits.startsWith("263")) {
            digits = digits.substring(3);
        }
        if (digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        if (digits.length() > 9) {
            digits = digits.substring(digits.length() - 9);
        }
        return digits;
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) return "****";
        return phone.substring(0, 4) + "****" + phone.substring(phone.length() - 3);
    }

    private String maskMerchantCode(Object code) {
        if (code == null) return "****";
        String str = code.toString();
        if (str.length() <= 4) return "****";
        return str.substring(0, 2) + "****" + str.substring(str.length() - 2);
    }

    private String maskMerchantNumber(Object number) {
        if (number == null) return "****";
        String str = number.toString();
        if (str.length() <= 4) return "****";
        return str.substring(0, 3) + "****" + str.substring(str.length() - 3);
    }

    // ============================================================
    // INNER CLASSES
    // ============================================================

    public static class RefundRequest {
        private String clientCorrelator;
        private String referenceCode;
        private String endUserId;
        private String originalEcocashReference;
        private long amountCents;
        private String currency;
        private String remark;

        public String getClientCorrelator() { return clientCorrelator; }
        public void setClientCorrelator(String clientCorrelator) { this.clientCorrelator = clientCorrelator; }
        public String getReferenceCode() { return referenceCode; }
        public void setReferenceCode(String referenceCode) { this.referenceCode = referenceCode; }
        public String getEndUserId() { return endUserId; }
        public void setEndUserId(String endUserId) { this.endUserId = endUserId; }
        public String getOriginalEcocashReference() { return originalEcocashReference; }
        public void setOriginalEcocashReference(String originalEcocashReference) { this.originalEcocashReference = originalEcocashReference; }
        public long getAmountCents() { return amountCents; }
        public void setAmountCents(long amountCents) { this.amountCents = amountCents; }
        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }
        public String getRemark() { return remark; }
        public void setRemark(String remark) { this.remark = remark; }
    }

    public static class RefundResult {
        private final boolean success;
        private final String message;
        private final String rawResponse;

        public RefundResult(boolean success, String message, String rawResponse) {
            this.success = success;
            this.message = message;
            this.rawResponse = rawResponse;
        }
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getRawResponse() { return rawResponse; }
    }

    public static class QueryResult {
        private final String clientCorrelator;
        private final String transactionStatus;
        private final String ecocashReference;
        private final double amount;
        private final String currency;

        @SuppressWarnings("unchecked")
        public QueryResult(Map<String, Object> data) {
            this.clientCorrelator = (String) data.get("clientCorrelator");
            this.transactionStatus = (String) data.get("transactionOperationStatus");
            this.ecocashReference = (String) data.get("ecocashReference");
            
            double amt = 0;
            String curr = "USD";
            try {
                Map<String, Object> paymentAmount = (Map<String, Object>) data.get("paymentAmount");
                if (paymentAmount != null) {
                    Map<String, Object> chargingInfo = (Map<String, Object>) paymentAmount.get("charginginformation");
                    if (chargingInfo != null) {
                        if (chargingInfo.get("amount") instanceof Number) {
                            amt = ((Number) chargingInfo.get("amount")).doubleValue();
                        }
                        if (chargingInfo.get("currency") instanceof String) {
                            curr = (String) chargingInfo.get("currency");
                        }
                    }
                }
            } catch (Exception e) {
                // Use defaults
            }
            this.amount = amt;
            this.currency = curr;
        }
        public String getClientCorrelator() { return clientCorrelator; }
        public String getTransactionStatus() { return transactionStatus; }
        public String getEcocashReference() { return ecocashReference; }
        public double getAmount() { return amount; }
        public String getCurrency() { return currency; }
        public String getDescription() { return TX_STATUS_DESCRIPTIONS.getOrDefault(transactionStatus, "Unknown status"); }
    }
}
