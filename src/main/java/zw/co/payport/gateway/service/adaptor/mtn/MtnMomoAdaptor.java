package zw.co.payport.gateway.service.adaptor.mtn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import zw.co.payport.gateway.payments.SubmissionErrors;
import zw.co.payport.gateway.service.adaptor.PaymentProviderAdaptor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MTN Mobile Money, Collection API "RequestToPay" (MoMo Open API, momodeveloper.mtn.com):
 *
 *   token      POST {base}/collection/token/  Basic(apiUser:apiKey), Ocp-Apim-Subscription-Key
 *   submit     POST {base}/collection/v1_0/requesttopay  X-Reference-Id (UUID v4), X-Target-Environment,
 *              X-Callback-Url, body {amount, currency, externalId, payer{MSISDN}, payerMessage, payeeNote}
 *   status     GET  {base}/collection/v1_0/requesttopay/{referenceId}  -> PENDING | SUCCESSFUL | FAILED
 *   callback   the same body as the status, unsigned
 *
 * The X-Reference-Id is derived from the attempt reference (SHA-256, v4 layout), so the payment can be
 * queried even when the submit outcome was unknown. Callbacks are never trusted on their content: MTN
 * is in payport.payments.query-providers, so only our authenticated status query changes a payment.
 *
 * Fails closed: until base URL, subscription key, API user and API key are configured, nothing is sent.
 * SPEC_ONLY in the integration register until sandbox evidence is recorded.
 */
@Slf4j
@Component
public class MtnMomoAdaptor implements PaymentProviderAdaptor {

    public static final String CODE = "MTN_MOMO";

    private final RestTemplate rest;
    private final ObjectMapper json = new ObjectMapper();
    private final String baseUrl;
    private final String subscriptionKey;
    private final String apiUser;
    private final String apiKey;
    private final String targetEnvironment;
    private final String callbackUrl;

    private volatile String token;
    private volatile long tokenExpiresAt;

    public MtnMomoAdaptor(RestTemplate rest,
                          @Value("${payport.mtn-momo.base-url:}") String baseUrl,
                          @Value("${payport.mtn-momo.subscription-key:}") String subscriptionKey,
                          @Value("${payport.mtn-momo.api-user:}") String apiUser,
                          @Value("${payport.mtn-momo.api-key:}") String apiKey,
                          @Value("${payport.mtn-momo.target-environment:sandbox}") String targetEnvironment,
                          @Value("${payport.mtn-momo.callback-url:}") String callbackUrl) {
        this.rest = rest;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.subscriptionKey = subscriptionKey;
        this.apiUser = apiUser;
        this.apiKey = apiKey;
        this.targetEnvironment = targetEnvironment;
        this.callbackUrl = callbackUrl;
    }

    @Override
    public String getProviderCode() {
        return CODE;
    }

    public boolean configured() {
        return !baseUrl.isBlank() && !subscriptionKey.isBlank() && !apiUser.isBlank() && !apiKey.isBlank();
    }

    /** Deterministic X-Reference-Id for an attempt reference, in UUID v4 layout. */
    public static String referenceId(String attemptReference) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(("PAYPORT|" + attemptReference).getBytes(StandardCharsets.UTF_8));
            h[6] = (byte) ((h[6] & 0x0f) | 0x40);
            h[8] = (byte) ((h[8] & 0x3f) | 0x80);
            long msb = 0, lsb = 0;
            for (int i = 0; i < 8; i++) msb = (msb << 8) | (h[i] & 0xff);
            for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (h[i] & 0xff);
            return new UUID(msb, lsb).toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String amount(long minor) {
        return BigDecimal.valueOf(minor, 2).stripTrailingZeros().toPlainString();
    }

    @Override
    public PaymentSubmitResult submitPayment(PaymentRequest r) {
        if (!configured()) {
            return PaymentSubmitResult.builder().submitted(false).message("MTN MoMo is not configured. No money was taken.").rawResponse("NOT_CONFIGURED").build();
        }
        String refId = referenceId(r.getPayportReference());
        try {
            HttpHeaders h = headers();
            h.set("X-Reference-Id", refId);
            if (!callbackUrl.isBlank()) h.set("X-Callback-Url", callbackUrl);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("amount", amount(r.getAmountCents()));
            body.put("currency", r.getCurrency());
            body.put("externalId", r.getPayportReference());
            body.put("payer", Map.of("partyIdType", "MSISDN", "partyId", r.getCustomerPhone() == null ? "" : r.getCustomerPhone().replaceAll("[^0-9]", "")));
            body.put("payerMessage", clip(r.getDescription(), 160));
            body.put("payeeNote", r.getPayportReference());
            ResponseEntity<String> resp = rest.exchange(baseUrl + "/collection/v1_0/requesttopay", HttpMethod.POST, new HttpEntity<>(json.writeValueAsString(body), h), String.class);
            boolean accepted = resp.getStatusCode().value() == 202;
            return PaymentSubmitResult.builder().submitted(accepted).outcomeUnknown(!accepted && resp.getStatusCode().is2xxSuccessful())
                    .providerReference(refId).message(accepted ? "Approve the MTN MoMo prompt on your phone" : "MTN MoMo did not accept the request")
                    .rawResponse("HTTP " + resp.getStatusCode().value()).build();
        } catch (HttpStatusCodeException e) {
            boolean unknown = e.getStatusCode().is5xxServerError();
            return PaymentSubmitResult.builder().submitted(false).outcomeUnknown(unknown).providerReference(refId)
                    .message(unknown ? "MTN MoMo did not answer clearly" : "MTN MoMo refused the request: " + reason(e.getResponseBodyAsString()))
                    .rawResponse("HTTP " + e.getStatusCode().value() + " " + clip(e.getResponseBodyAsString(), 1000)).build();
        } catch (Exception e) {
            boolean unknown = SubmissionErrors.isOutcomeUnknown(e);
            return PaymentSubmitResult.builder().submitted(false).outcomeUnknown(unknown).providerReference(refId)
                    .message(unknown ? "MTN MoMo call failed: " + e.getMessage() : "Could not reach MTN MoMo. No money was taken.")
                    .rawResponse(e.getClass().getSimpleName() + ": " + e.getMessage()).build();
        }
    }

    @Override
    public CallbackValidationResult validateCallback(Map<String, String> headers, String rawBody) {
        // Unsigned by design at MTN; content is only a trigger for our status query (query-providers).
        return CallbackValidationResult.builder().valid(true).build();
    }

    @Override
    public CallbackEvent parseCallback(String rawBody) throws Exception {
        JsonNode n = json.readTree(rawBody);
        return CallbackEvent.builder()
                .payportReference(n.path("externalId").asText(null))
                .providerReference(n.path("financialTransactionId").asText(null))
                .status(status(n.path("status").asText("")))
                .amountCents(minor(n.path("amount").asText(null)))
                .currency(n.path("currency").asText(null))
                .rawPayload(rawBody)
                .failureMessage(n.path("reason").isMissingNode() ? null : n.path("reason").toString())
                .build();
    }

    @Override
    public PaymentStatusResult queryStatus(String reference) throws Exception {
        if (!configured()) throw new IllegalStateException("MTN MoMo is not configured");
        String refId = isUuid(reference) ? reference : referenceId(reference);
        ResponseEntity<String> resp = rest.exchange(baseUrl + "/collection/v1_0/requesttopay/" + refId, HttpMethod.GET, new HttpEntity<>(headers()), String.class);
        JsonNode n = json.readTree(resp.getBody() == null ? "{}" : resp.getBody());
        return PaymentStatusResult.builder().status(status(n.path("status").asText(""))).amountCents(minor(n.path("amount").asText(null)))
                .providerReference(n.path("financialTransactionId").asText(refId))
                .message(n.path("reason").isMissingNode() ? n.path("status").asText() : n.path("reason").asText(n.path("reason").toString())).build();
    }

    static String status(String s) {
        return switch (s.toUpperCase()) {
            case "SUCCESSFUL" -> "SUCCESS";
            case "FAILED", "REJECTED", "TIMEOUT" -> "FAILED";
            case "PENDING" -> "PENDING";
            default -> "UNKNOWN";
        };
    }

    private HttpHeaders headers() throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token());
        h.set("X-Target-Environment", targetEnvironment);
        h.set("Ocp-Apim-Subscription-Key", subscriptionKey);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private synchronized String token() throws Exception {
        if (token != null && System.currentTimeMillis() < tokenExpiresAt) return token;
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Basic " + Base64.getEncoder().encodeToString((apiUser + ":" + apiKey).getBytes(StandardCharsets.UTF_8)));
        h.set("Ocp-Apim-Subscription-Key", subscriptionKey);
        ResponseEntity<String> resp = rest.exchange(baseUrl + "/collection/token/", HttpMethod.POST, new HttpEntity<>(h), String.class);
        JsonNode n = json.readTree(resp.getBody());
        token = n.path("access_token").asText();
        tokenExpiresAt = System.currentTimeMillis() + Math.max(60, n.path("expires_in").asLong(3600) - 60) * 1000L;
        return token;
    }

    private static boolean isUuid(String s) {
        try {
            UUID.fromString(s);
            return s.length() == 36;
        } catch (Exception e) {
            return false;
        }
    }

    static long minor(String major) {
        if (major == null || major.isBlank()) return 0;
        try {
            return new BigDecimal(major.trim()).movePointRight(2).longValueExact();
        } catch (Exception e) {
            return 0;
        }
    }

    private String reason(String body) {
        try {
            JsonNode n = json.readTree(body);
            return n.path("message").asText(n.path("code").asText("refused"));
        } catch (Exception e) {
            return "refused";
        }
    }

    private static String clip(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }
}
