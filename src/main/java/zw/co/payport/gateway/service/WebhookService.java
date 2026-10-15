package zw.co.payport.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.entity.CheckoutSession;
import zw.co.payport.gateway.entity.Webhook;
import zw.co.payport.gateway.entity.WebhookDelivery;
import zw.co.payport.gateway.repository.WebhookDeliveryRepository;
import zw.co.payport.gateway.repository.WebhookRepository;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registration, HMAC-signed delivery, and retry with backoff -- see the
 * Merchants Module design doc Section 6.7 for the retry schedule this
 * follows (immediate, 1m, 5m, 30m, 2h, 6h).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    /** Minutes to wait before each retry, indexed by attempt number (0 = first retry after the initial failed attempt). */
    private static final int[] RETRY_DELAYS_MINUTES = {1, 5, 30, 120, 360};

    private final WebhookRepository webhookRepository;
    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public record RegisteredWebhook(Webhook webhook, String secret) {}

    public RegisteredWebhook register(Long merchantId, String url) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            throw new RuntimeException("url must be a valid http(s) URL");
        }
        String secret = "whsec_" + randomToken();
        Webhook webhook = Webhook.builder()
                .merchantId(merchantId)
                .url(url)
                .secret(secret)
                .status("ACTIVE")
                .build();
        webhookRepository.save(webhook);
        log.info("[WEBHOOK] Registered for merchant {}: {}", merchantId, url);
        return new RegisteredWebhook(webhook, secret);
    }

    public List<Webhook> listForMerchant(Long merchantId) {
        return webhookRepository.findByMerchantId(merchantId);
    }

    public List<WebhookDelivery> listDeliveries(Webhook webhook) {
        return webhookDeliveryRepository.findByWebhookIdOrderByCreatedAtDesc(webhook.getId());
    }

    /**
     * Called by CheckoutSessionService the moment a session reaches a
     * terminal state. Fires (and records) a delivery attempt against
     * every ACTIVE webhook the merchant has registered -- a merchant with
     * no webhooks registered yet simply gets nothing, which is correct,
     * not an error.
     */
    public void notifySessionStatusChanged(CheckoutSession session, String eventType) {
        List<Webhook> webhooks = webhookRepository.findByMerchantIdAndStatus(session.getMerchantId(), "ACTIVE");
        for (Webhook webhook : webhooks) {
            String payload = buildPayload(session, eventType);
            WebhookDelivery delivery = WebhookDelivery.builder()
                    .webhookId(webhook.getId())
                    .checkoutSessionId(session.getId())
                    .eventType(eventType)
                    .payload(payload)
                    .status("PENDING")
                    .build();
            webhookDeliveryRepository.save(delivery);
            attemptDelivery(webhook, delivery);
        }
    }

    /** Called both for the first attempt and by the retry sweeper -- identical logic either way. */
    public void attemptDelivery(Webhook webhook, WebhookDelivery delivery) {
        String signature = sign(delivery.getPayload(), webhook.getSecret());

        delivery.setAttemptCount(delivery.getAttemptCount() + 1);
        delivery.setLastAttemptAt(LocalDateTime.now());

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhook.getUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Payport-Signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(delivery.getPayload()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            delivery.setResponseStatus(response.statusCode());
            String body = response.body();
            delivery.setResponseBody(body != null && body.length() > 2000 ? body.substring(0, 2000) : body);

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                delivery.setStatus("DELIVERED");
                delivery.setDeliveredAt(LocalDateTime.now());
                delivery.setNextAttemptAt(null);
                log.info("[WEBHOOK] Delivered to {} ({}): {}", webhook.getUrl(), response.statusCode(), delivery.getEventType());
            } else {
                scheduleRetryOrGiveUp(delivery, "HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            delivery.setResponseStatus(null);
            delivery.setResponseBody(e.getMessage());
            scheduleRetryOrGiveUp(delivery, e.getMessage());
        }

        webhookDeliveryRepository.save(delivery);
    }

    /** Sweeper entry point -- see WebhookRetryScheduler. */
    public int retryDueDeliveries() {
        List<WebhookDelivery> due = webhookDeliveryRepository.findByStatusAndNextAttemptAtBefore("PENDING", LocalDateTime.now());
        for (WebhookDelivery delivery : due) {
            webhookRepository.findById(delivery.getWebhookId()).ifPresent(webhook -> attemptDelivery(webhook, delivery));
        }
        return due.size();
    }

    private void scheduleRetryOrGiveUp(WebhookDelivery delivery, String failureReason) {
        int attemptIndex = delivery.getAttemptCount() - 1; // attemptCount already incremented before this call
        if (attemptIndex >= RETRY_DELAYS_MINUTES.length) {
            delivery.setStatus("FAILED");
            delivery.setNextAttemptAt(null);
            log.warn("[WEBHOOK] Delivery {} exhausted all retries, giving up: {}", delivery.getId(), failureReason);
        } else {
            delivery.setStatus("PENDING");
            delivery.setNextAttemptAt(LocalDateTime.now().plusMinutes(RETRY_DELAYS_MINUTES[attemptIndex]));
            log.info("[WEBHOOK] Delivery {} failed ({}), retry #{} scheduled for {}",
                    delivery.getId(), failureReason, delivery.getAttemptCount(), delivery.getNextAttemptAt());
        }
    }

    private String buildPayload(CheckoutSession session, String eventType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", eventType);
        body.put("checkoutSessionId", session.getId());
        body.put("reference", session.getReference());
        body.put("status", session.getStatus());
        // Lets the merchant's server tell real payments from simulated ones (never ship goods for livemode=false).
        body.put("livemode", Boolean.TRUE.equals(session.getLivemode()));
        body.put("amountCents", session.getAmountCents());
        body.put("currency", session.getCurrency());
        body.put("customerPhone", session.getCustomerPhone());
        body.put("metadata", session.getMetadata());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("Could not serialize webhook payload", e);
        }
    }

    private String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Could not sign webhook payload", e);
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
