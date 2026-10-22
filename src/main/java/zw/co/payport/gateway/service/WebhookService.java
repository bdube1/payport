package zw.co.payport.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.entity.CheckoutSession;
import zw.co.payport.gateway.entity.Webhook;
import zw.co.payport.gateway.entity.WebhookDelivery;
import zw.co.payport.gateway.merchant.WebhookTargetGuard;
import zw.co.payport.gateway.platform.SecretBox;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Merchant webhooks: registration, signed delivery, retry with back-off (immediate, 1m, 5m, 30m,
 * 2h, 6h), replay and test events.
 *
 * Phase 4 (webhooks v2):
 *  - SSRF-safe targets (WebhookTargetGuard), checked at registration and before every attempt;
 *    redirects are never followed.
 *  - Signing secrets encrypted at rest (SecretBox).
 *  - v2 signature: header PayPort-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256 of "t.body">, so
 *    receivers can reject replays older than 5 minutes; PayPort-Event-Id is stable across retries
 *    and replays. v1 webhooks keep receiving X-Payport-Signature (base64 HMAC of the body) as
 *    before; both headers are sent to v2 webhooks.
 *  - Events beyond checkout sessions (payout.paid, payout.returned, payout.failed).
 */
@Slf4j
@Service
public class WebhookService {

    /** Minutes to wait before each retry, indexed by attempt number (0 = first retry after the initial failed attempt). */
    private static final int[] RETRY_DELAYS_MINUTES = {1, 5, 30, 120, 360};

    private final WebhookRepository webhookRepository;
    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final ObjectMapper objectMapper;
    private final WebhookTargetGuard guard;
    private final SecretBox secrets;
    private final SecureRandom secureRandom = new SecureRandom();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public WebhookService(WebhookRepository webhookRepository, WebhookDeliveryRepository webhookDeliveryRepository,
                          ObjectMapper objectMapper, WebhookTargetGuard guard, SecretBox secrets) {
        this.webhookRepository = webhookRepository;
        this.webhookDeliveryRepository = webhookDeliveryRepository;
        this.objectMapper = objectMapper;
        this.guard = guard;
        this.secrets = secrets;
    }

    public record RegisteredWebhook(Webhook webhook, String secret) {}

    public RegisteredWebhook register(Long merchantId, String url) {
        try {
            guard.check(url);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Webhook URL refused: " + e.getMessage());
        }
        String secret = "whsec_" + randomToken();
        Webhook webhook = Webhook.builder()
                .merchantId(merchantId)
                .url(url.trim())
                .secret(secrets.seal(secret))
                .status("ACTIVE")
                .apiVersion("v2")
                .build();
        webhookRepository.save(webhook);
        log.info("[WEBHOOK] Registered for merchant {}: {}", merchantId, url);
        return new RegisteredWebhook(webhook, secret);
    }

    /** New secret, shown once; the old one stops working immediately. */
    public RegisteredWebhook rotateSecret(Long merchantId, Long webhookId) {
        Webhook w = owned(merchantId, webhookId);
        String secret = "whsec_" + randomToken();
        w.setSecret(secrets.seal(secret));
        webhookRepository.save(w);
        return new RegisteredWebhook(w, secret);
    }

    public Webhook disable(Long merchantId, Long webhookId) {
        Webhook w = owned(merchantId, webhookId);
        w.setStatus("DISABLED");
        w.setDisabledReason("disabled by merchant");
        return webhookRepository.save(w);
    }

    public List<Webhook> listForMerchant(Long merchantId) {
        return webhookRepository.findByMerchantId(merchantId);
    }

    public List<WebhookDelivery> listDeliveries(Webhook webhook) {
        return webhookDeliveryRepository.findByWebhookIdOrderByCreatedAtDesc(webhook.getId());
    }

    /**
     * Called by CheckoutSessionService the moment a session reaches a terminal state. Fires (and
     * records) a delivery against every ACTIVE webhook the merchant has registered.
     */
    public void notifySessionStatusChanged(CheckoutSession session, String eventType) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("checkoutSessionId", session.getId());
        data.put("reference", session.getReference());
        data.put("status", session.getStatus());
        data.put("amountCents", session.getAmountCents());
        data.put("currency", session.getCurrency());
        data.put("customerPhone", session.getCustomerPhone());
        data.put("metadata", session.getMetadata());
        publish(session.getMerchantId(), eventType, data, Boolean.TRUE.equals(session.getLivemode()), session.getId());
    }

    /** Any merchant event (e.g. payout.paid). The body carries event id, type, livemode, created and the data. */
    public void notifyMerchantEvent(Long merchantId, String eventType, Map<String, Object> data, boolean livemode) {
        publish(merchantId, eventType, data, livemode, null);
    }

    private void publish(Long merchantId, String eventType, Map<String, Object> data, boolean livemode, Long sessionId) {
        List<Webhook> webhooks = webhookRepository.findByMerchantIdAndStatus(merchantId, "ACTIVE");
        if (webhooks.isEmpty()) return;
        String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "");
        String payload = buildPayload(eventId, eventType, data, livemode);
        for (Webhook webhook : webhooks) {
            WebhookDelivery delivery = WebhookDelivery.builder()
                    .webhookId(webhook.getId())
                    .checkoutSessionId(sessionId)
                    .eventId(eventId)
                    .eventType(eventType)
                    .payload(payload)
                    .status("PENDING")
                    .build();
            webhookDeliveryRepository.save(delivery);
            attemptAfterCommit(webhook, delivery);
        }
    }

    /**
     * Events are often raised inside a database transaction (e.g. while the payment row is locked).
     * The HTTP call then waits until that transaction has committed: no lock is held during the
     * merchant's response time, and nothing is sent for a change that rolled back. The delivery row
     * commits with the transaction, so the retry sweeper picks it up even if this process stops.
     */
    private void attemptAfterCommit(Webhook webhook, WebhookDelivery delivery) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            delivery.setNextAttemptAt(LocalDateTime.now().plusMinutes(1)); // safety net if the process stops before afterCommit
            webhookDeliveryRepository.save(delivery);
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            try {
                                attemptDelivery(webhook, delivery);
                            } catch (Exception e) {
                                log.warn("[WEBHOOK] First attempt for delivery {} failed: {}", delivery.getId(), e.getMessage());
                            }
                        }
                    });
        } else {
            attemptDelivery(webhook, delivery);
        }
    }

    /** Merchant-requested resend of a past delivery: same event id and body, new delivery record. */
    public WebhookDelivery replay(Long merchantId, Long deliveryId) {
        WebhookDelivery original = webhookDeliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new RuntimeException("Delivery not found: " + deliveryId));
        Webhook webhook = owned(merchantId, original.getWebhookId());
        WebhookDelivery copy = WebhookDelivery.builder()
                .webhookId(webhook.getId())
                .checkoutSessionId(original.getCheckoutSessionId())
                .eventId(original.getEventId())
                .eventType(original.getEventType())
                .payload(original.getPayload())
                .status("PENDING")
                .replayOf(original.getId())
                .build();
        webhookDeliveryRepository.save(copy);
        attemptDelivery(webhook, copy);
        return copy;
    }

    /** Sends a "ping" event so a merchant can check their endpoint and signature verification. */
    public WebhookDelivery sendTestEvent(Long merchantId, Long webhookId) {
        Webhook webhook = owned(merchantId, webhookId);
        String eventId = "evt_test_" + UUID.randomUUID().toString().replace("-", "");
        WebhookDelivery d = WebhookDelivery.builder()
                .webhookId(webhook.getId())
                .eventId(eventId)
                .eventType("ping")
                .payload(buildPayload(eventId, "ping", Map.of("message", "PayPort test event"), false))
                .status("PENDING")
                .build();
        webhookDeliveryRepository.save(d);
        attemptDelivery(webhook, d);
        return d;
    }

    /** Called both for the first attempt and by the retry sweeper -- identical logic either way. */
    public void attemptDelivery(Webhook webhook, WebhookDelivery delivery) {
        delivery.setAttemptCount(delivery.getAttemptCount() + 1);
        delivery.setLastAttemptAt(LocalDateTime.now());

        try {
            URI target = guard.check(webhook.getUrl()); // DNS may have changed since registration
            String secret = secrets.open(webhook.getSecret());
            if (!SecretBox.isSealed(webhook.getSecret())) {      // R14: encrypt legacy plaintext secrets on use
                webhook.setSecret(secrets.seal(secret));
                webhookRepository.save(webhook);
            }
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(target)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Payport-Signature", signV1(delivery.getPayload(), secret))
                    .POST(HttpRequest.BodyPublishers.ofString(delivery.getPayload()));
            if ("v2".equals(webhook.getApiVersion())) {
                long t = Instant.now().getEpochSecond();
                request.header("PayPort-Signature", "t=" + t + ",v1=" + signV2(t, delivery.getPayload(), secret));
                if (delivery.getEventId() != null) request.header("PayPort-Event-Id", delivery.getEventId());
            }

            HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
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

    private Webhook owned(Long merchantId, Long webhookId) {
        return webhookRepository.findById(webhookId).filter(w -> w.getMerchantId().equals(merchantId))
                .orElseThrow(() -> new RuntimeException("Webhook not found: " + webhookId));
    }

    private String buildPayload(String eventId, String eventType, Map<String, Object> data, boolean livemode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", eventId);
        body.put("event", eventType);
        body.put("type", eventType);
        // Lets the merchant's server tell real payments from simulated ones (never ship goods for livemode=false).
        body.put("livemode", livemode);
        body.put("created", Instant.now().getEpochSecond());
        // v1 receivers read the session fields at the top level; keep them there.
        body.putAll(data);
        body.put("data", data);
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("Could not serialize webhook payload", e);
        }
    }

    /** v1: base64 HMAC-SHA256 of the body. */
    static String signV1(String payload, String secret) {
        return Base64.getEncoder().encodeToString(hmac(secret, payload));
    }

    /** v2: hex HMAC-SHA256 of "timestamp.body" (receivers check the timestamp to refuse replays). */
    public static String signV2(long timestamp, String payload, String secret) {
        return HexFormat.of().formatHex(hmac(secret, timestamp + "." + payload));
    }

    private static byte[] hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
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
