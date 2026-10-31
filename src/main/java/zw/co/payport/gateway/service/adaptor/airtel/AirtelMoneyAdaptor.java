package zw.co.payport.gateway.service.adaptor.airtel;

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
import zw.co.payport.gateway.markets.MarketRegistry;
import zw.co.payport.gateway.payments.SubmissionErrors;
import zw.co.payport.gateway.service.adaptor.PaymentProviderAdaptor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Airtel Money, Collections (Airtel Africa Open API, developers.airtel.africa):
 *
 *   token    POST {base}/auth/oauth2/token  {client_id, client_secret, grant_type=client_credentials}
 *   submit   POST {base}/merchant/v1/payments/  X-Country, X-Currency,
 *            {reference, subscriber{country, currency, msisdn (national)}, transaction{amount, country, currency, id}}
 *   status   GET  {base}/standard/v1/payments/{id}  -> data.transaction.status TS | TF | TA | TIP | TE
 *   callback {transaction{id, message, status_code TS|TF, airtel_money_id}}
 *
 * Country and national number come from the market of the payment currency (portal settings). The
 * callback is only a trigger: Airtel is in payport.payments.query-providers, so only our authenticated
 * status query changes a payment. Fails closed until base URL, client id and secret are configured.
 */
@Slf4j
@Component
public class AirtelMoneyAdaptor implements PaymentProviderAdaptor {

    public static final String CODE = "AIRTEL_MONEY";

    private final RestTemplate rest;
    private final MarketRegistry markets;
    private final ObjectMapper json = new ObjectMapper();
    private final String baseUrl;
    private final String clientId;
    private final String clientSecret;
    private final String defaultCurrency;

    private volatile String token;
    private volatile long tokenExpiresAt;

    public AirtelMoneyAdaptor(RestTemplate rest, MarketRegistry markets,
                              @Value("${payport.airtel-money.base-url:}") String baseUrl,
                              @Value("${payport.airtel-money.client-id:}") String clientId,
                              @Value("${payport.airtel-money.client-secret:}") String clientSecret,
                              @Value("${payport.airtel-money.status-currency:ZMW}") String defaultCurrency) {
        this.rest = rest;
        this.markets = markets;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.defaultCurrency = defaultCurrency;
    }

    @Override
    public String getProviderCode() {
        return CODE;
    }

    public boolean configured() {
        return !baseUrl.isBlank() && !clientId.isBlank() && !clientSecret.isBlank();
    }

    @Override
    public PaymentSubmitResult submitPayment(PaymentRequest r) {
        if (!configured()) {
            return PaymentSubmitResult.builder().submitted(false).message("Airtel Money is not configured. No money was taken.").rawResponse("NOT_CONFIGURED").build();
        }
        try {
            MarketRegistry.Market m = markets.requireForCurrency(r.getCurrency());
            String digits = r.getCustomerPhone() == null ? "" : r.getCustomerPhone().replaceAll("[^0-9]", "");
            String national = digits.startsWith(m.phoneCountryCode()) ? digits.substring(m.phoneCountryCode().length()) : digits;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("reference", clip(r.getDescription() == null ? "Payport" : r.getDescription(), 64));
            body.put("subscriber", Map.of("country", m.country(), "currency", r.getCurrency(), "msisdn", national));
            body.put("transaction", Map.of("amount", BigDecimal.valueOf(r.getAmountCents(), 2), "country", m.country(), "currency", r.getCurrency(),
                    "id", r.getPayportReference()));
            ResponseEntity<String> resp = rest.exchange(baseUrl + "/merchant/v1/payments/", HttpMethod.POST,
                    new HttpEntity<>(json.writeValueAsString(body), headers(m.country(), r.getCurrency())), String.class);
            JsonNode n = json.readTree(resp.getBody() == null ? "{}" : resp.getBody());
            boolean ok = n.path("status").path("success").asBoolean(false);
            return PaymentSubmitResult.builder().submitted(ok)
                    .providerReference(n.path("data").path("transaction").path("id").asText(r.getPayportReference()))
                    .message(ok ? "Approve the Airtel Money prompt on your phone" : "Airtel Money refused the request: " + n.path("status").path("message").asText("refused"))
                    .rawResponse(clip(resp.getBody(), 2000)).build();
        } catch (HttpStatusCodeException e) {
            boolean unknown = e.getStatusCode().is5xxServerError();
            return PaymentSubmitResult.builder().submitted(false).outcomeUnknown(unknown)
                    .message(unknown ? "Airtel Money did not answer clearly" : "Airtel Money refused the request")
                    .rawResponse("HTTP " + e.getStatusCode().value() + " " + clip(e.getResponseBodyAsString(), 1000)).build();
        } catch (Exception e) {
            boolean unknown = SubmissionErrors.isOutcomeUnknown(e);
            return PaymentSubmitResult.builder().submitted(false).outcomeUnknown(unknown)
                    .message(unknown ? "Airtel Money call failed: " + e.getMessage() : "Could not reach Airtel Money. No money was taken.")
                    .rawResponse(e.getClass().getSimpleName() + ": " + e.getMessage()).build();
        }
    }

    @Override
    public CallbackValidationResult validateCallback(Map<String, String> headers, String rawBody) {
        return CallbackValidationResult.builder().valid(true).build();   // trigger only; confirmed by status query
    }

    @Override
    public CallbackEvent parseCallback(String rawBody) throws Exception {
        JsonNode t = json.readTree(rawBody).path("transaction");
        return CallbackEvent.builder()
                .payportReference(t.path("id").asText(null))
                .providerReference(t.path("airtel_money_id").asText(null))
                .status(status(t.path("status_code").asText("")))
                .rawPayload(rawBody)
                .failureMessage(t.path("message").asText(null))
                .build();
    }

    @Override
    public PaymentStatusResult queryStatus(String reference) throws Exception {
        if (!configured()) throw new IllegalStateException("Airtel Money is not configured");
        String ccy = defaultCurrency;
        MarketRegistry.Market m = markets.requireForCurrency(ccy);
        ResponseEntity<String> resp = rest.exchange(baseUrl + "/standard/v1/payments/" + reference, HttpMethod.GET, new HttpEntity<>(headers(m.country(), ccy)), String.class);
        JsonNode t = json.readTree(resp.getBody() == null ? "{}" : resp.getBody()).path("data").path("transaction");
        return PaymentStatusResult.builder().status(status(t.path("status").asText(""))).providerReference(t.path("airtel_money_id").asText(null))
                .message(t.path("message").asText(t.path("status").asText())).build();
    }

    static String status(String s) {
        return switch (s.toUpperCase()) {
            case "TS" -> "SUCCESS";
            case "TF", "TE" -> "FAILED";
            case "TIP" -> "PENDING";
            default -> "UNKNOWN";      // TA (ambiguous) and anything else: left for the resolver
        };
    }

    private HttpHeaders headers(String country, String currency) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token());
        h.set("X-Country", country);
        h.set("X-Currency", currency);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setAccept(java.util.List.of(MediaType.ALL));
        return h;
    }

    private synchronized String token() throws Exception {
        if (token != null && System.currentTimeMillis() < tokenExpiresAt) return token;
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        String body = json.writeValueAsString(Map.of("client_id", clientId, "client_secret", clientSecret, "grant_type", "client_credentials"));
        ResponseEntity<String> resp = rest.exchange(baseUrl + "/auth/oauth2/token", HttpMethod.POST, new HttpEntity<>(body, h), String.class);
        JsonNode n = json.readTree(resp.getBody());
        token = n.path("access_token").asText();
        tokenExpiresAt = System.currentTimeMillis() + Math.max(60, n.path("expires_in").asLong(180) - 30) * 1000L;
        return token;
    }

    private static String clip(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }
}
