package zw.co.payport.gateway.service.adaptor.onemoney;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import zw.co.payport.gateway.service.adaptor.PaymentProviderAdaptor;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OneMoneyAdaptor implements PaymentProviderAdaptor {

    @Value("${payport.onemoney.base-url}")
    private String baseUrl;

    @Value("${payport.onemoney.merchant-no}")
    private String merchantNo;

    @Value("${payport.onemoney.encrypt-key-id}")
    private String encryptKeyId;

    @Value("${payport.onemoney.notify-url}")
    private String notifyUrl;

    @Value("${payport.onemoney.platform-public-key}")
    private String platformPublicKey;

    @Value("${payport.onemoney.merchant-private-key}")
    private String merchantPrivateKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private static final Map<String, String> ORDER_STATUS_DESCRIPTIONS = new LinkedHashMap<>();
    static {
        ORDER_STATUS_DESCRIPTIONS.put("10", "Initialization - Payment request received");
        ORDER_STATUS_DESCRIPTIONS.put("20", "Payment in progress - USSD push sent, awaiting customer PIN");
        ORDER_STATUS_DESCRIPTIONS.put("30", "Payment successful - Transaction completed");
        ORDER_STATUS_DESCRIPTIONS.put("40", "Payment failed - Transaction was not completed");
        ORDER_STATUS_DESCRIPTIONS.put("50", "Order closed - Transaction expired or cancelled");
    }

    @Override
    public String getProviderCode() {
        return "ONEMONEY";
    }

    @Override
    public PaymentSubmitResult submitPayment(PaymentRequest request) throws Exception {
        log.info("[ONEMONEY] Submitting C2B payment for reference: {}", request.getPayportReference());

        String formattedPhone = normalisePhone(request.getCustomerPhone());
        double amountInDollars = request.getAmountCents() / 100.0;

        Map<String, Object> businessData = new LinkedHashMap<>();
        businessData.put("transOrderNo", request.getPayportReference());
        // Was "orderAmt" -- the spec's C2B Push business parameters table
        // (section 2.4) and its own sample body both confirm the request
        // field is "amt". "orderAmt" is real in this same spec, but for
        // two different things: the C2B Push *response* (section 2.5) and
        // the separate B2C payout endpoint's *request* (section 3.5.3) --
        // easy to mix up since they're both literally "the amount" in
        // adjacent sections. Sent this way, OneMoney's C2B Push endpoint
        // would never have received a valid amount for the payment.
        businessData.put("amt", amountInDollars);
        businessData.put("currency", request.getCurrency());
        businessData.put("mobileNo", formattedPhone);
        businessData.put("goodsName", "PayPort Payment");
        businessData.put("notifyUrl", notifyUrl);

        logOneMoneyRequest(businessData, amountInDollars, request.getCurrency());

        String businessJson = objectMapper.writeValueAsString(businessData);
        String encryptedPayload = buildEncryptedPayload(businessJson);
        HttpEntity<String> entity = buildHttpEntity(encryptedPayload);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                baseUrl + "/api/pay/payment/push", 
                HttpMethod.POST, 
                entity, 
                String.class
            );

            // The outer envelope (status/message) is never encrypted --
            // only "data" is. Was never checked at all, so an API-level
            // rejection (bad encryptKeyId, bad signature, unrecognised
            // merNo) fell straight through to attempting to decrypt a
            // "data" field that likely doesn't exist for a rejected
            // request, surfacing a confusing decryption failure instead
            // of OneMoney's own clear rejection reason.
            Map<String, Object> envelope = objectMapper.readValue(response.getBody(), Map.class);
            String apiStatus = String.valueOf(envelope.get("status"));
            if (!"0".equals(apiStatus)) {
                String apiMessage = (String) envelope.get("message");
                log.error("[ONEMONEY] C2B Push rejected at API level - status: {}, message: {}", apiStatus, apiMessage);
                return PaymentSubmitResult.builder()
                        .submitted(false)
                        .message(apiMessage != null && !apiMessage.isBlank() ? apiMessage : "OneMoney rejected the request")
                        .rawResponse(response.getBody())
                        .build();
            }
            
            // Parse the response
            String decryptedResponse = decryptResponse(response.getBody());
            Map<String, Object> responseBody = objectMapper.readValue(decryptedResponse, Map.class);
            
            // The response structure: {"transOrderNo":"...","orderNo":"...","orderStatus":"20",...}
            String orderNo = (String) responseBody.get("orderNo");
            String orderStatus = (String) responseBody.get("orderStatus");
            
            // Status "0" means success at API level (payment initiated)
            // The actual payment outcome is in orderStatus
            boolean submitted = true;  // API call succeeded
            
            logOneMoneyResponse(orderNo, orderStatus);

            return PaymentSubmitResult.builder()
                    .submitted(submitted)
                    .providerReference(request.getPayportReference())
                    .message("OneMoney push sent - Order: " + orderNo + ", Status: " + orderStatus)
                    .rawResponse(decryptedResponse)
                    .build();

        } catch (Exception e) {
            log.error("[ONEMONEY] C2B Push failed: {}", e.getMessage());
            return PaymentSubmitResult.builder()
                    .submitted(false)
                    .message("OneMoney API call failed: " + e.getMessage())
                    .rawResponse(e.getMessage())
                    .build();
        }
    }

    // ============================================================
    // LOGGING METHODS
    // ============================================================

    private void logOneMoneyRequest(Map<String, Object> data, double amount, String currency) {
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    ONEMONEY C2B PUSH REQUEST                     ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ transOrderNo:  {}", data.get("transOrderNo"));
        log.info("║ orderAmt:      {} {}", amount, currency);
        log.info("║ currency:      {}", data.get("currency"));
        log.info("║ mobileNo:      {}", data.get("mobileNo"));
        log.info("║ goodsName:     {}", data.get("goodsName"));
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logOneMoneyResponse(String orderNo, String orderStatus) {
        String description = ORDER_STATUS_DESCRIPTIONS.getOrDefault(orderStatus, "Unknown status");
        String statusIcon = "20".equals(orderStatus) ? "⏳ PENDING" : 
                           "30".equals(orderStatus) ? "✅ SUCCESS" : 
                           "40".equals(orderStatus) ? "❌ FAILED" : "❓ UNKNOWN";
        
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    ONEMONEY C2B PUSH RESPONSE                    ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Result:        {} ({})", statusIcon, orderStatus);
        log.info("║ Order No:      {}", orderNo);
        log.info("║ Description:   {}", description);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    // ============================================================
    // C2B QUERY (Transaction Status)
    // ============================================================

    public C2BQueryResult queryC2BTransaction(String transOrderNo) throws Exception {
        log.info("[ONEMONEY] Querying C2B transaction: {}", transOrderNo);

        Map<String, Object> businessData = new LinkedHashMap<>();
        businessData.put("transOrderNo", transOrderNo);

        String businessJson = objectMapper.writeValueAsString(businessData);
        String encryptedPayload = buildEncryptedPayload(businessJson);
        HttpEntity<String> entity = buildHttpEntity(encryptedPayload);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                baseUrl + "/api/pay/payment/order/status/query", 
                HttpMethod.POST, 
                entity, 
                String.class
            );

            // Same gap as submitPayment() had -- the unencrypted outer
            // envelope's status/message was never checked before
            // attempting to decrypt "data", so an API-level rejection
            // produced a confusing decryption error instead of
            // OneMoney's own clear reason.
            Map<String, Object> envelope = objectMapper.readValue(response.getBody(), Map.class);
            String apiStatus = String.valueOf(envelope.get("status"));
            if (!"0".equals(apiStatus)) {
                String apiMessage = (String) envelope.get("message");
                throw new RuntimeException("OneMoney rejected the query" +
                        (apiMessage != null && !apiMessage.isBlank() ? ": " + apiMessage : " (status " + apiStatus + ")"));
            }
            
            String decryptedResponse = decryptResponse(response.getBody());
            Map<String, Object> result = objectMapper.readValue(decryptedResponse, Map.class);
            
            String orderNo = (String) result.get("orderNo");
            String orderStatus = (String) result.get("orderStatus");
            String description = ORDER_STATUS_DESCRIPTIONS.getOrDefault(orderStatus, "Unknown status");
            
            log.info("[ONEMONEY] C2B Query - Order: {}, Status: {} - {}", orderNo, orderStatus, description);
            
            return new C2BQueryResult(result);
        } catch (Exception e) {
            log.error("[ONEMONEY] C2B Query failed: {}", e.getMessage());
            throw e;
        }
    }

    // ============================================================
    // CALLBACK HANDLING
    // ============================================================

    @Override
    public CallbackValidationResult validateCallback(Map<String, String> headers, String rawBody) {
        return CallbackValidationResult.builder().valid(true).build();
    }

    @Override
    public CallbackEvent parseCallback(String rawBody) throws Exception {
        String decryptedData = decryptResponse(rawBody);
        Map<String, Object> payload = objectMapper.readValue(decryptedData, Map.class);
        
        String transOrderNo = (String) payload.get("transOrderNo");
        String orderStatus = (String) payload.get("orderStatus");
        String orderNo = (String) payload.get("orderNo");
        String description = ORDER_STATUS_DESCRIPTIONS.getOrDefault(orderStatus, "Unknown status");
        
        log.info("[ONEMONEY] Callback received - Order: {}, Status: {} - {}", orderNo, orderStatus, description);
        
        return CallbackEvent.builder()
                .payportReference(transOrderNo)
                .providerReference(orderNo)
                .status(mapOneMoneyStatus(orderStatus))
                .amountCents(0)
                .currency("USD")
                .rawPayload(decryptedData)
                .build();
    }

    @Override
    public PaymentStatusResult queryStatus(String providerReference) throws Exception {
        C2BQueryResult result = queryC2BTransaction(providerReference);
        String description = ORDER_STATUS_DESCRIPTIONS.getOrDefault(result.getOrderStatus(), "Unknown status");
        return PaymentStatusResult.builder()
                .status(result.getOrderStatus())
                .providerReference(providerReference)
                .message(result.getOrderStatus() + " - " + description)
                .build();
    }

    // ============================================================
    // HELPER METHODS
    // ============================================================

    private String buildEncryptedPayload(String businessJson) throws Exception {
        String aesKey = randomAesKey();
        String encryptedData = aesEncrypt(businessJson, aesKey);
        String encryptedKey = rsaEncrypt(aesKey, platformPublicKey);
        String signData = sha256Hex(businessJson);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", System.currentTimeMillis());
        payload.put("random", UUID.randomUUID().toString());
        payload.put("language", "en");
        payload.put("encryptKeyId", encryptKeyId);
        payload.put("merNo", merchantNo);
        payload.put("encryptData", encryptedData);
        payload.put("encryptKey", encryptedKey);
        payload.put("signData", signData);

        return objectMapper.writeValueAsString(payload);
    }

    private HttpEntity<String> buildHttpEntity(String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(jsonBody, headers);
    }

    private String aesEncrypt(String content, String aesKey) throws Exception {
        SecretKeySpec secretKey = new SecretKeySpec(aesKey.getBytes(StandardCharsets.UTF_8), "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        byte[] encrypted = cipher.doFinal(content.getBytes(StandardCharsets.UTF_8));
        String firstEncode = Base64.getEncoder().encodeToString(encrypted);
        return Base64.getEncoder().encodeToString(firstEncode.getBytes(StandardCharsets.UTF_8));
    }

    private String rsaEncrypt(String content, String publicKeyBase64) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(publicKeyBase64);
        PublicKey pubKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(keyBytes));
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, pubKey);
        byte[] encrypted = cipher.doFinal(content.getBytes(StandardCharsets.UTF_8));
        String firstEncode = Base64.getEncoder().encodeToString(encrypted);
        return Base64.getEncoder().encodeToString(firstEncode.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256Hex(String content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    public String decryptResponse(String responseBody) throws Exception {
        try {
            Map<String, Object> response = objectMapper.readValue(responseBody, Map.class);
            String encryptKey = (String) response.get("encryptKey");
            String encryptData = (String) response.get("data");
            
            if (encryptKey != null && encryptData != null) {
                String aesKey = rsaDecrypt(encryptKey);
                return aesDecrypt(encryptData, aesKey);
            }
        } catch (Exception e) {
            log.warn("[ONEMONEY] Failed to decrypt response: {}", e.getMessage());
        }
        return responseBody;
    }

    public String rsaDecrypt(String encryptedContent) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(merchantPrivateKey);
        PrivateKey privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        String firstDecode = new String(Base64.getDecoder().decode(encryptedContent), StandardCharsets.UTF_8);
        byte[] cipherBytes = Base64.getDecoder().decode(firstDecode);
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
    }

    public String aesDecrypt(String encryptedContent, String aesKey) throws Exception {
        String firstDecode = new String(Base64.getDecoder().decode(encryptedContent), StandardCharsets.UTF_8);
        byte[] cipherBytes = Base64.getDecoder().decode(firstDecode);
        SecretKeySpec secretKey = new SecretKeySpec(aesKey.getBytes(StandardCharsets.UTF_8), "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey);
        return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
    }

    private String randomAesKey() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    public String mapOneMoneyStatus(String oneMoneyStatus) {
        if (oneMoneyStatus == null) return "PENDING";
        return switch (oneMoneyStatus) {
            case "30" -> "SUCCESS";
            // Was "FAILED" only for 40 -- CallbackEvent.status only
            // recognises SUCCESS | FAILED | PENDING (see
            // TransactionOrchestratorService.handlePaymentCallback,
            // which has no else branch for anything else), but "50"
            // mapped to "CLOSED" and "10" to "INITIATED" -- neither
            // matches, so either one would be silently dropped with no
            // status change at all. A closed/expired order is a genuine,
            // definitive failure from the customer's perspective (they
            // never got what they paid for) and deserves to actually
            // resolve the transaction instead of leaving it stuck on
            // PAYMENT_PENDING until a separate, unrelated timeout
            // eventually catches it.
            case "40", "50" -> "FAILED";
            case "10", "20" -> "PENDING";
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

    // ============================================================
    // INNER CLASSES
    // ============================================================

    public static class C2BQueryResult {
        private final String transOrderNo;
        private final String orderNo;
        private final double orderAmt;
        private final String currency;
        private final double actAmt;
        private final String orderStatus;

        public C2BQueryResult(Map<String, Object> data) {
            this.transOrderNo = (String) data.get("transOrderNo");
            this.orderNo = (String) data.get("orderNo");
            this.orderAmt = data.get("orderAmt") instanceof Number ? ((Number) data.get("orderAmt")).doubleValue() : 0;
            this.currency = (String) data.get("currency");
            this.actAmt = data.get("actAmt") instanceof Number ? ((Number) data.get("actAmt")).doubleValue() : 0;
            this.orderStatus = (String) data.get("orderStatus");
        }
        public String getTransOrderNo() { return transOrderNo; }
        public String getOrderNo() { return orderNo; }
        public double getOrderAmt() { return orderAmt; }
        public String getCurrency() { return currency; }
        public double getActAmt() { return actAmt; }
        public String getOrderStatus() { return orderStatus; }
    }
}
