package zw.co.payport.gateway.service.adaptor.omari;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import zw.co.payport.gateway.model.omari.*;
import zw.co.payport.gateway.service.adaptor.PaymentProviderAdaptor;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OmariPaymentAdaptor implements PaymentProviderAdaptor {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${payport.omari.base-url}")
    private String baseUrl;

    @Value("${payport.omari.api-key}")
    private String apiKey;

    @Value("${payport.omari.timeout:30000}")
    private int timeout;

    @Override
    public String getProviderCode() {
        return "OMARI";
    }

    private String normalizePhone(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.startsWith("263")) return digits;
        if (digits.startsWith("0")) return "263" + digits.substring(1);
        if (phone.startsWith("+263")) return phone.substring(1);
        return digits;
    }

    @Override
    public PaymentSubmitResult submitPayment(PaymentRequest request) throws Exception {
        log.info("[OMARI] Submitting payment for reference: {}", request.getPayportReference());

        try {
            // Generate a UUID for Omari
            String omariUuid = UUID.randomUUID().toString();
            String normalizedPhone = normalizePhone(request.getCustomerPhone());
            
            OmariAuthRequest authRequest = OmariAuthRequest.builder()
                    .msisdn(normalizedPhone)
                    .reference(omariUuid)
                    .amount(BigDecimal.valueOf(request.getAmountCents() / 100.0))
                    .currency(request.getCurrency())
                    .channel("WEB")
                    .build();

            // === LOG REQUEST ===
            log.info("╔══════════════════════════════════════════════════════════════════╗");
            log.info("║                     OMARI AUTH REQUEST                          ║");
            log.info("╠══════════════════════════════════════════════════════════════════╣");
            log.info("║ MSISDN:        {}", normalizedPhone);
            log.info("║ Amount:        {} {}", request.getAmountCents() / 100.0, request.getCurrency());
            log.info("║ Payport Ref:   {}", request.getPayportReference());
            log.info("║ Omari UUID:    {}", omariUuid);
            log.info("║ Channel:       WEB");
            log.info("╚══════════════════════════════════════════════════════════════════╝");

            OmariAuthResponse authResponse = callAuth(authRequest);

            // === LOG RESPONSE ===
            log.info("╔══════════════════════════════════════════════════════════════════╗");
            log.info("║                     OMARI AUTH RESPONSE                         ║");
            log.info("╠══════════════════════════════════════════════════════════════════╣");
            log.info("║ error:          {}", authResponse.isError());
            log.info("║ message:        {}", authResponse.getMessage());
            log.info("║ responseCode:   {}", authResponse.getResponseCode());
            log.info("║ otpReference:   {}", authResponse.getOtpReference());
            log.info("║ Omari UUID:     {}", omariUuid);
            log.info("╚══════════════════════════════════════════════════════════════════╝");

            if (authResponse.isError()) {
                log.error("[OMARI] ❌ Auth failed: {}", authResponse.getMessage());
                return PaymentSubmitResult.builder()
                        .submitted(false)
                        .providerReference(authResponse.getOtpReference())
                        .message("Auth failed: " + authResponse.getMessage())
                        .rawResponse(objectMapper.writeValueAsString(authResponse))
                        .build();
            }

            log.info("[OMARI] ✅ Auth successful. OTP Reference: {}, Omari UUID: {}", authResponse.getOtpReference(), omariUuid);

            // Store the Omari UUID as the providerReference so we can use it for completion
            return PaymentSubmitResult.builder()
                    .submitted(true)
                    .providerReference(omariUuid)
                    .message("OTP sent. OTP Ref: " + authResponse.getOtpReference() + ", Omari UUID: " + omariUuid)
                    .rawResponse(objectMapper.writeValueAsString(authResponse))
                    .build();

        } catch (Exception e) {
            log.error("[OMARI] ❌ Payment submission failed: {}", e.getMessage(), e);
            return PaymentSubmitResult.builder()
                    .submitted(false)
                    .outcomeUnknown(zw.co.payport.gateway.payments.SubmissionErrors.isOutcomeUnknown(e))
                    .message("Omari payment failed: " + e.getMessage())
                    .rawResponse(e.getMessage())
                    .build();
        }
    }

    public OmariAuthResponse callAuth(OmariAuthRequest request) throws Exception {
        String url = baseUrl + "/auth";
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Merchant-Key", apiKey);

        HttpEntity<OmariAuthRequest> entity = new HttpEntity<>(request, headers);

        log.debug("[OMARI] Auth URL: {}", url);
        log.debug("[OMARI] Auth Request: {}", objectMapper.writeValueAsString(request));

        ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                entity,
                String.class
        );

        log.info("[OMARI] Auth Response: {}", response.getBody());

        return objectMapper.readValue(response.getBody(), OmariAuthResponse.class);
    }

    public OmariPaymentResponse callPayment(OmariPaymentRequest request) throws Exception {
        String url = baseUrl + "/request";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Merchant-Key", apiKey);

        HttpEntity<OmariPaymentRequest> entity = new HttpEntity<>(request, headers);

        // === LOG PAYMENT REQUEST ===
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    OMARI PAYMENT REQUEST                        ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ MSISDN:        {}", request.getMsisdn());
        log.info("║ Omari UUID:    {}", request.getReference());
        log.info("╚══════════════════════════════════════════════════════════════════╝");

        ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                entity,
                String.class
        );

        OmariPaymentResponse paymentResponse = objectMapper.readValue(response.getBody(), OmariPaymentResponse.class);

        // === LOG PAYMENT RESPONSE ===
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    OMARI PAYMENT RESPONSE                       ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ error:          {}", paymentResponse.isError());
        log.info("║ message:        {}", paymentResponse.getMessage());
        log.info("║ responseCode:   {}", paymentResponse.getResponseCode());
        log.info("║ paymentReference: {}", paymentResponse.getPaymentReference());
        log.info("║ debitReference: {}", paymentResponse.getDebitReference());
        log.info("╚══════════════════════════════════════════════════════════════════╝");

        if (!paymentResponse.isError()) {
            log.info("[OMARI] ✅ Payment completed successfully. Payment Reference: {}", paymentResponse.getPaymentReference());
        } else {
            log.error("[OMARI] ❌ Payment failed: {}", paymentResponse.getMessage());
        }

        return paymentResponse;
    }

    public OmariQueryResponse callQuery(String reference) throws Exception {
        String url = baseUrl + "/query/" + reference;

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Merchant-Key", apiKey);

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        // === LOG QUERY REQUEST ===
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                     OMARI QUERY REQUEST                         ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Reference:     {}", reference);
        log.info("╚══════════════════════════════════════════════════════════════════╝");

        ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                String.class
        );

        OmariQueryResponse queryResponse = objectMapper.readValue(response.getBody(), OmariQueryResponse.class);

        // === LOG QUERY RESPONSE ===
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                     OMARI QUERY RESPONSE                        ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ error:          {}", queryResponse.isError());
        log.info("║ message:        {}", queryResponse.getMessage());
        log.info("║ status:         {}", queryResponse.getStatus());
        log.info("║ responseCode:   {}", queryResponse.getResponseCode());
        log.info("║ reference:      {}", queryResponse.getReference());
        log.info("║ amount:         {}", queryResponse.getAmount());
        log.info("║ currency:       {}", queryResponse.getCurrency());
        log.info("║ channel:        {}", queryResponse.getChannel());
        log.info("║ paymentReference: {}", queryResponse.getPaymentReference());
        log.info("║ debitReference: {}", queryResponse.getDebitReference());
        log.info("║ created:        {}", queryResponse.getCreated());
        log.info("╚══════════════════════════════════════════════════════════════════╝");

        return queryResponse;
    }

    public OmariVoidResponse callVoid(String reference) throws Exception {
        String url = baseUrl + "/void";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Merchant-Key", apiKey);

        OmariVoidRequest voidRequest = OmariVoidRequest.builder()
                .reference(reference)
                .build();

        HttpEntity<OmariVoidRequest> entity = new HttpEntity<>(voidRequest, headers);

        // === LOG VOID REQUEST ===
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                     OMARI VOID REQUEST                          ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Reference:     {}", reference);
        log.info("╚══════════════════════════════════════════════════════════════════╝");

        ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                entity,
                String.class
        );

        OmariVoidResponse voidResponse = objectMapper.readValue(response.getBody(), OmariVoidResponse.class);

        // === LOG VOID RESPONSE ===
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                     OMARI VOID RESPONSE                         ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ error:          {}", voidResponse.isError());
        log.info("║ message:        {}", voidResponse.getMessage());
        log.info("║ responseCode:   {}", voidResponse.getResponseCode());
        log.info("╚══════════════════════════════════════════════════════════════════╝");

        if (!voidResponse.isError()) {
            log.info("[OMARI] ✅ Transaction voided successfully");
        } else {
            log.error("[OMARI] ❌ Void failed: {}", voidResponse.getMessage());
        }

        return voidResponse;
    }

    @Override
    public CallbackValidationResult validateCallback(Map<String, String> headers, String rawBody) {
        log.info("[OMARI] Callback validation received (not used for Omari)");
        return CallbackValidationResult.builder()
                .valid(false)
                .failureReason("Omari doesn't support callbacks")
                .build();
    }

    @Override
    public CallbackEvent parseCallback(String rawBody) throws Exception {
        log.info("[OMARI] Callback parse received (not used for Omari)");
        return CallbackEvent.builder()
                .status("UNKNOWN")
                .providerReference("N/A")
                .rawPayload(rawBody)
                .build();
    }

    @Override
    public PaymentStatusResult queryStatus(String providerReference) throws Exception {
        log.info("[OMARI] Querying status for reference: {}", providerReference);
        
        try {
            OmariQueryResponse response = callQuery(providerReference);
            
            String status = "UNKNOWN";
            if (!response.isError()) {
                if ("Success".equalsIgnoreCase(response.getStatus())) {
                    status = "SUCCESS";
                } else {
                    status = response.getStatus() != null ? response.getStatus() : "UNKNOWN";
                }
            }
            
            long amountCents = 0;
            if (response.getAmount() != null) {
                amountCents = Math.round(response.getAmount().doubleValue() * 100);
            }
            
            return PaymentStatusResult.builder()
                    .status(status)
                    .amountCents(amountCents)
                    .providerReference(response.getPaymentReference())
                    .message(response.getMessage())
                    .build();
                    
        } catch (Exception e) {
            log.error("[OMARI] ❌ Query status failed: {}", e.getMessage(), e);
            return PaymentStatusResult.builder()
                    .status("UNKNOWN")
                    .providerReference(providerReference)
                    .message("Query failed: " + e.getMessage())
                    .build();
        }
    }
}
