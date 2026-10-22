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

    public WhatsAppController(WhatsAppService whatsapp) {
        this.whatsapp = whatsapp;
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
        if (!whatsapp.signatureValid(req.getHeader("X-Hub-Signature-256"), body)) return ResponseEntity.status(401).body("bad signature");
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
}
