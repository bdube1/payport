package zw.co.payport.gateway.whatsapp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Sends replies through the WhatsApp Cloud API (POST {api-base}/{phone-number-id}/messages, bearer
 * token). Nothing is sent until the sender number and token are configured; the reply is still
 * recorded so ops can see what would have gone out.
 */
@Component
public class WhatsAppSender {

    private final RestTemplate rest;
    private final String apiBase;
    private final String phoneNumberId;
    private final String token;

    public WhatsAppSender(RestTemplate rest,
                          @Value("${payport.whatsapp.api-base:https://graph.facebook.com/v20.0}") String apiBase,
                          @Value("${payport.whatsapp.phone-number-id:}") String phoneNumberId,
                          @Value("${payport.whatsapp.access-token:}") String token) {
        this.rest = rest;
        this.apiBase = apiBase;
        this.phoneNumberId = phoneNumberId;
        this.token = token;
    }

    public boolean configured() {
        return !phoneNumberId.isBlank() && !token.isBlank();
    }

    /** Returns null when sent, otherwise why not. */
    public String send(String to, String text) {
        if (!configured()) return "WhatsApp sender not configured (payport.whatsapp.phone-number-id / access-token)";
        try {
            HttpHeaders h = new HttpHeaders();
            h.setBearerAuth(token);
            h.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = Map.of("messaging_product", "whatsapp", "to", to, "type", "text", "text", Map.of("body", text));
            rest.postForEntity(apiBase + "/" + phoneNumberId + "/messages", new HttpEntity<>(body, h), String.class);
            return null;
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
