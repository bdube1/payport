package zw.co.payport.gateway.psp.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.psp.integration.IntegrationSpis;
import zw.co.payport.gateway.service.EmailService;
import zw.co.payport.gateway.service.SmsNotificationService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Outbound channels: SMS (via the gateway's Africa's Talking client), email (gateway SMTP) and WhatsApp Cloud API. */
public final class MessageChannels {
    private MessageChannels() {}

    @Component
    public static class Sms implements IntegrationSpis.MessageChannel {
        private final SmsNotificationService sms;
        public Sms(SmsNotificationService sms) { this.sms = sms; }
        @Override public String channel() { return "SMS"; }
        @Override public String send(String to, String subjectOrTemplate, String body) {
            if (!sms.sendCustom(to, body, subjectOrTemplate, subjectOrTemplate)) throw new IllegalStateException("SMS gateway did not accept the message");
            return "AT";
        }
    }

    @Component
    public static class Email implements IntegrationSpis.MessageChannel {
        private final EmailService email;
        public Email(EmailService email) { this.email = email; }
        @Override public String channel() { return "EMAIL"; }
        @Override public String send(String to, String subject, String body) {
            email.sendGeneric(to, subject, body);
            return "SMTP";
        }
    }

    /**
     * WhatsApp Business Cloud API (Meta). Text messages inside the 24h customer
     * service window; outside it Meta requires an approved template, so set
     * payport.psp.whatsapp.use-templates=true and register templates with the
     * same codes as psp_notification_templates.
     */
    @Component
    public static class WhatsApp implements IntegrationSpis.MessageChannel {
        private final ObjectMapper om;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        @Value("${payport.psp.whatsapp.phone-number-id:}") private String phoneNumberId;
        @Value("${payport.psp.whatsapp.access-token:}") private String token;
        @Value("${payport.psp.whatsapp.api-version:v21.0}") private String version;
        @Value("${payport.psp.whatsapp.use-templates:false}") private boolean useTemplates;

        public WhatsApp(ObjectMapper om) { this.om = om; }
        @Override public String channel() { return "WHATSAPP"; }

        @Override
        public String send(String to, String templateCode, String body) throws Exception {
            if (phoneNumberId == null || phoneNumberId.isBlank() || token == null || token.isBlank()) {
                throw new IllegalStateException("WhatsApp is not configured");
            }
            String number = to.replaceAll("\\D", "");
            Map<String, Object> payload = useTemplates
                    ? Map.of("messaging_product", "whatsapp", "to", number, "type", "template",
                             "template", Map.of("name", templateCode.toLowerCase(), "language", Map.of("code", "en"),
                                     "components", java.util.List.of(Map.of("type", "body",
                                             "parameters", java.util.List.of(Map.of("type", "text", "text", body))))))
                    : Map.of("messaging_product", "whatsapp", "to", number, "type", "text", "text", Map.of("body", body));
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://graph.facebook.com/" + version + "/" + phoneNumberId + "/messages"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) throw new IllegalStateException("WhatsApp HTTP " + r.statusCode() + ": " + r.body());
            return om.readTree(r.body()).path("messages").path(0).path("id").asText("WA");
        }
    }
}
