package zw.co.payport.gateway.service.adaptor.innbucks;

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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * InnBucks payment code collection. Payport asks InnBucks for a payment code for the amount; the
 * customer approves the code in the InnBucks app or on the InnBucks USSD menu; Payport learns the
 * outcome by status query (InnBucks is a query provider: a callback is only a trigger).
 *
 *   auth      POST {base}{auth-path}      {username, password}            -> {accessToken | token}
 *   generate  POST {base}{generate-path}  {amount, currency, reference, narration, msisdn}
 *                                                                          -> {code, authNumber, expiresAt}
 *   query     POST {base}{query-path}     {code}                          -> {status}
 *
 * Paths and credentials come from configuration (InnBucks issues them with the merchant account),
 * so a difference in the merchant pack is a configuration change. Fails closed: nothing is sent
 * until the base URL and API key are set. SPEC_ONLY in the integration register until sandbox
 * evidence is recorded.
 */
@Slf4j
@Component
public class InnBucksAdaptor implements PaymentProviderAdaptor {

    public static final String CODE = "INNBUCKS";

    private final RestTemplate rest;
    private final ObjectMapper json = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final String username;
    private final String password;
    private final String authPath;
    private final String generatePath;
    private final String queryPath;

    private volatile String token;
    private volatile long tokenExpiresAt;

    public InnBucksAdaptor(RestTemplate rest,
                           @Value("${payport.innbucks.base-url:}") String baseUrl,
                           @Value("${payport.innbucks.api-key:}") String apiKey,
                           @Value("${payport.innbucks.username:}") String username,
                           @Value("${payport.innbucks.password:}") String password,
                           @Value("${payport.innbucks.auth-path:/auth/third-party}") String authPath,
                           @Value("${payport.innbucks.generate-path:/api/code/generate}") String generatePath,
                           @Value("${payport.innbucks.query-path:/api/code/query}") String queryPath) {
        this.rest = rest;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.username = username;
        this.password = password;
        this.authPath = authPath;
        this.generatePath = generatePath;
        this.queryPath = queryPath;
    }

    @Override
    public String getProviderCode() {
        return CODE;
    }

    public boolean configured() {
        return !baseUrl.isBlank() && !apiKey.isBlank();
    }

    @Override
    public PaymentSubmitResult submitPayment(PaymentRequest r) {
        if (!configured()) {
            return PaymentSubmitResult.builder().submitted(false).message("InnBucks is not configured. No money was taken.").rawResponse("NOT_CONFIGURED").build();
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("amount", BigDecimal.valueOf(r.getAmountCents(), 2).toPlainString());
            body.put("currency", r.getCurrency());
            body.put("reference", r.getPayportReference());
            body.put("narration", clip(r.getDescription() == null ? "Payport " + r.getPayportReference() : r.getDescription(), 100));
            if (r.getCustomerPhone() != null) body.put("msisdn", r.getCustomerPhone().replaceAll("[^0-9]", ""));
            ResponseEntity<String> resp = rest.exchange(baseUrl + generatePath, HttpMethod.POST, new HttpEntity<>(json.writeValueAsString(body), headers()), String.class);
            JsonNode n = json.readTree(resp.getBody() == null ? "{}" : resp.getBody());
            String code = first(n, "code", "paymentCode", "authCode");
            if (code == null) {
                return PaymentSubmitResult.builder().submitted(false).outcomeUnknown(false)
                        .message("InnBucks did not issue a payment code: " + first(n, "message", "error"))
                        .rawResponse(clip(resp.getBody(), 1000)).build();
            }
            return PaymentSubmitResult.builder().submitted(true).providerReference(code)
                    .message(code)
                    .rawResponse(clip(resp.getBody(), 1000)).build();
        } catch (HttpStatusCodeException e) {
            boolean unknown = e.getStatusCode().is5xxServerError();
            return PaymentSubmitResult.builder().submitted(false).outcomeUnknown(unknown)
                    .message(unknown ? "InnBucks did not answer clearly" : "InnBucks refused the request")
                    .rawResponse("HTTP " + e.getStatusCode().value() + " " + clip(e.getResponseBodyAsString(), 1000)).build();
        } catch (Exception e) {
            boolean unknown = SubmissionErrors.isOutcomeUnknown(e);
            return PaymentSubmitResult.builder().submitted(false).outcomeUnknown(unknown)
                    .message(unknown ? "InnBucks call failed: " + e.getMessage() : "Could not reach InnBucks. No money was taken.")
                    .rawResponse(e.getClass().getSimpleName() + ": " + e.getMessage()).build();
        }
    }

    @Override
    public CallbackValidationResult validateCallback(Map<String, String> headers, String rawBody) {
        // Content is only a trigger: INNBUCKS is a query provider, so only our own status query changes a payment.
        return CallbackValidationResult.builder().valid(rawBody != null && !rawBody.isBlank()).failureReason("Empty callback body").build();
    }

    @Override
    public CallbackEvent parseCallback(String rawBody) throws Exception {
        JsonNode n = json.readTree(rawBody);
        return CallbackEvent.builder()
                .payportReference(first(n, "reference", "externalReference"))
                .providerReference(first(n, "code", "paymentCode", "transactionId"))
                .status(status(first(n, "status", "state")))
                .amountCents(minor(first(n, "amount")))
                .currency(first(n, "currency"))
                .rawPayload(rawBody)
                .build();
    }

    @Override
    public PaymentStatusResult queryStatus(String code) throws Exception {
        if (!configured()) throw new IllegalStateException("InnBucks is not configured");
        String body = json.writeValueAsString(Map.of("code", code));
        ResponseEntity<String> resp = rest.exchange(baseUrl + queryPath, HttpMethod.POST, new HttpEntity<>(body, headers()), String.class);
        JsonNode n = json.readTree(resp.getBody() == null ? "{}" : resp.getBody());
        String raw = first(n, "status", "state");
        return PaymentStatusResult.builder().status(status(raw)).amountCents(minor(first(n, "amount")))
                .providerReference(code).message(raw).build();
    }

    static String status(String s) {
        if (s == null) return "UNKNOWN";
        return switch (s.trim().toUpperCase(Locale.ROOT)) {
            case "PAID", "CLAIMED", "SUCCESS", "SUCCESSFUL", "COMPLETED", "APPROVED" -> "SUCCESS";
            case "EXPIRED", "CANCELLED", "CANCELED", "FAILED", "DECLINED", "REVERSED", "REJECTED" -> "FAILED";
            case "NEW", "PENDING", "UNPAID", "CREATED", "ISSUED", "PROCESSING" -> "PENDING";
            default -> "UNKNOWN";
        };
    }

    private HttpHeaders headers() throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("api-key", apiKey);
        if (!username.isBlank()) h.setBearerAuth(token());
        return h;
    }

    private synchronized String token() throws Exception {
        if (token != null && System.currentTimeMillis() < tokenExpiresAt) return token;
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("api-key", apiKey);
        String body = json.writeValueAsString(Map.of("username", username, "password", password));
        ResponseEntity<String> resp = rest.exchange(baseUrl + authPath, HttpMethod.POST, new HttpEntity<>(body, h), String.class);
        JsonNode n = json.readTree(resp.getBody() == null ? "{}" : resp.getBody());
        String t = first(n, "accessToken", "token", "access_token");
        if (t == null) throw new IllegalStateException("InnBucks login returned no token");
        token = t;
        tokenExpiresAt = System.currentTimeMillis() + 50 * 60 * 1000L;
        return token;
    }

    private static String first(JsonNode n, String... names) {
        for (String k : names) {
            JsonNode v = n.path(k);
            if (!v.isMissingNode() && !v.isNull() && !v.asText().isBlank()) return v.asText();
            JsonNode d = n.path("data").path(k);
            if (!d.isMissingNode() && !d.isNull() && !d.asText().isBlank()) return d.asText();
        }
        return null;
    }

    static long minor(String major) {
        if (major == null || major.isBlank()) return 0;
        try {
            return new BigDecimal(major.trim()).movePointRight(2).longValueExact();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String clip(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }
}
