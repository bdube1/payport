package zw.co.payport.gateway.whatsapp;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.Web;

import java.util.List;
import java.util.Map;

/** WhatsApp Cloud API webhook (public, signature-checked) and its ops status. */
@Slf4j
@RestController
public class WhatsAppController {

    private final WhatsAppService whatsapp;
    private final WhatsAppSettings settings;
    private final WhatsAppSender sender;

    public WhatsAppController(WhatsAppService whatsapp, WhatsAppSettings settings, WhatsAppSender sender) {
        this.whatsapp = whatsapp;
        this.settings = settings;
        this.sender = sender;
    }

    @GetMapping(value = "/api/v1/whatsapp/webhook", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode,
                                         @RequestParam(name = "hub.verify_token", required = false) String token,
                                         @RequestParam(name = "hub.challenge", required = false) String challenge) {
        try {
            return ResponseEntity.ok(whatsapp.verify(mode, token, challenge));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body("forbidden");
        }
    }

    @PostMapping("/api/v1/whatsapp/webhook")
    public ResponseEntity<String> receive(@RequestBody(required = false) String body, HttpServletRequest req) {
        if (!whatsapp.configured()) return ResponseEntity.status(503).body("not configured");
        if (!whatsapp.signatureValid(req.getHeader("X-Hub-Signature-256"), body)) {
            zw.co.payport.gateway.observability.SecurityEvents.count(zw.co.payport.gateway.observability.SecurityEvents.SIGNATURE_INVALID, "whatsapp");
            return ResponseEntity.status(401).body("bad signature");
        }
        try {
            whatsapp.receive(body);
        } catch (Exception e) {
            log.warn("[WHATSAPP] delivery not processed: {}", e.getMessage());
        }
        return ResponseEntity.ok("EVENT_RECEIVED");
    }

    @GetMapping("/api/v1/admin/whatsapp")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(HttpServletRequest req) {
        return Web.run(() -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>(whatsapp.status());
            List<Map<String, Object>> recent = whatsapp.recent();
            m.put("recent", recent);
            return m;
        }, req);
    }

    public record SettingsBody(String businessNumber, String phoneNumberId, String wabaId, String accessToken, String appSecret, String verifyToken, String apiBase) {}

    @PutMapping("/api/v1/admin/whatsapp/settings")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> settings(@RequestBody SettingsBody b, HttpServletRequest req) {
        return Web.run(() -> settings.update(b.businessNumber(), b.phoneNumberId(), b.wabaId(), b.accessToken(), b.appSecret(), b.verifyToken(), b.apiBase(), Web.staff()), req);
    }

    public record TestBody(String to) {}

    /** Sends one message through the configured sender; the number must have messaged the business in the last 24 hours (Meta's rule for free-form replies). */
    @PostMapping("/api/v1/admin/whatsapp/test")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> test(@RequestBody TestBody b, HttpServletRequest req) {
        return Web.run(() -> {
            String to = b.to() == null ? "" : b.to().replaceAll("[^0-9]", "");
            if (to.length() < 9) throw new IllegalArgumentException("Enter the number to send the test to, with its country code");
            String err = sender.send(to, "PayPort WhatsApp is connected. Send MENU to start.");
            return Map.<String, Object>of("sent", err == null, "error", err == null ? "" : err);
        }, req);
    }
}
