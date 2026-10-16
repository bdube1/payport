package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.config.ApiKeyAuthenticationFilter;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.entity.Webhook;
import zw.co.payport.gateway.entity.WebhookDelivery;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.service.WebhookService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchant/webhooks")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MERCHANT_OWNER','MERCHANT_DEVELOPER')")
public class WebhookController {

    private final WebhookService webhookService;

    public record RegisterBody(String url) {}

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> register(@RequestBody RegisterBody body, HttpServletRequest req) {
        try {
            WebhookService.RegisteredWebhook result = webhookService.register(currentMerchant().getId(), body.url());
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", result.webhook().getId());
            map.put("url", result.webhook().getUrl());
            map.put("secret", result.secret());
            map.put("warning", "This secret is shown once. Save it now -- you'll need it to verify webhook signatures, and it can't be retrieved again.");
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(map, requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("WEBHOOK_REGISTER_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest req) {
        List<Webhook> webhooks = webhookService.listForMerchant(currentMerchant().getId());
        List<Map<String, Object>> body = webhooks.stream().map(w -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", w.getId());
            m.put("url", w.getUrl());
            m.put("status", w.getStatus());
            m.put("createdAt", w.getCreatedAt());
            // Secret intentionally omitted here -- only ever returned once, at creation.
            return m;
        }).toList();
        return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
    }

    @GetMapping("/{id}/deliveries")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> deliveries(@PathVariable Long id, HttpServletRequest req) {
        Webhook webhook = webhookService.listForMerchant(currentMerchant().getId()).stream()
                .filter(w -> w.getId().equals(id)).findFirst()
                .orElseThrow(() -> new RuntimeException("Webhook not found: " + id));

        List<WebhookDelivery> deliveries = webhookService.listDeliveries(webhook);
        List<Map<String, Object>> body = deliveries.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("eventId", d.getEventId());
            m.put("eventType", d.getEventType());
            m.put("status", d.getStatus());
            m.put("attemptCount", d.getAttemptCount());
            m.put("replayOf", d.getReplayOf());
            m.put("responseStatus", d.getResponseStatus());
            m.put("lastAttemptAt", d.getLastAttemptAt());
            m.put("deliveredAt", d.getDeliveredAt());
            m.put("createdAt", d.getCreatedAt());
            return m;
        }).toList();
        return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
    }

    /** Webhooks v2 console: rotate the signing secret (shown once). */
    @PostMapping("/{id}/rotate-secret")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rotate(@PathVariable Long id, HttpServletRequest req) {
        WebhookService.RegisteredWebhook r = webhookService.rotateSecret(currentMerchant().getId(), id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.webhook().getId());
        m.put("secret", r.secret());
        m.put("warning", "This secret is shown once. The previous secret stopped working.");
        return ResponseEntity.ok(ApiResponse.success(m, requestId(req)));
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<ApiResponse<Map<String, Object>>> disable(@PathVariable Long id, HttpServletRequest req) {
        Webhook w = webhookService.disable(currentMerchant().getId(), id);
        return ResponseEntity.ok(ApiResponse.success(Map.of("id", w.getId(), "status", w.getStatus()), requestId(req)));
    }

    /** Sends a signed "ping" event to check the endpoint and signature verification. */
    @PostMapping("/{id}/test")
    public ResponseEntity<ApiResponse<Map<String, Object>>> test(@PathVariable Long id, HttpServletRequest req) {
        WebhookDelivery d = webhookService.sendTestEvent(currentMerchant().getId(), id);
        return ResponseEntity.ok(ApiResponse.success(deliveryMap(d), requestId(req)));
    }

    /** Replay console: re-send a past delivery (same event id, so receivers can de-duplicate). */
    @PostMapping("/deliveries/{deliveryId}/replay")
    public ResponseEntity<ApiResponse<Map<String, Object>>> replay(@PathVariable Long deliveryId, HttpServletRequest req) {
        WebhookDelivery d = webhookService.replay(currentMerchant().getId(), deliveryId);
        return ResponseEntity.ok(ApiResponse.success(deliveryMap(d), requestId(req)));
    }

    private static Map<String, Object> deliveryMap(WebhookDelivery d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("eventId", d.getEventId());
        m.put("eventType", d.getEventType());
        m.put("status", d.getStatus());
        m.put("attemptCount", d.getAttemptCount());
        m.put("responseStatus", d.getResponseStatus());
        m.put("replayOf", d.getReplayOf());
        return m;
    }

    private Merchant currentMerchant() {
        return zw.co.payport.gateway.config.MerchantAuthResolver.currentMerchant();
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
