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
    private final WhatsAppSettings settings;

    public WhatsAppSender(RestTemplate rest, WhatsAppSettings settings) {
        this.rest = rest;
        this.settings = settings;
    }

    public boolean configured() {
        WhatsAppSettings.Values v = settings.get();
        return !v.phoneNumberId().isBlank() && !v.accessToken().isBlank();
    }

    /** Returns null when sent, otherwise why not. */
    public String send(String to, String text) {
        if (!configured()) return "WhatsApp sender not configured (phone number ID and access token, set in the ops console)";
        WhatsAppSettings.Values v = settings.get();
        try {
            HttpHeaders h = new HttpHeaders();
            h.setBearerAuth(v.accessToken());
            h.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = Map.of("messaging_product", "whatsapp", "to", to, "type", "text", "text", Map.of("body", text));
            rest.postForEntity(v.apiBase() + "/" + v.phoneNumberId() + "/messages", new HttpEntity<>(body, h), String.class);
            return null;
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
