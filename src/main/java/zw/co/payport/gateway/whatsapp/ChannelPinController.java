package zw.co.payport.gateway.whatsapp;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.Web;

import java.util.LinkedHashMap;
import java.util.Map;

/** PayPort's PIN page for WhatsApp (public, rate-limited, one-time links): see ChannelPinService. */
@Slf4j
@RestController
public class ChannelPinController {

    private final ChannelPinService pins;
    private final WhatsAppService whatsapp;
    private final WhatsAppSettings settings;

    public ChannelPinController(ChannelPinService pins, WhatsAppService whatsapp, WhatsAppSettings settings) {
        this.pins = pins;
        this.whatsapp = whatsapp;
        this.settings = settings;
    }

    /** Where "Back to WhatsApp" goes: the PayPort business number's chat. */
    private String back() {
        String n = settings.view().get("businessNumber") == null ? "" : String.valueOf(settings.view().get("businessNumber")).replaceAll("[^0-9]", "");
        return n.isEmpty() ? null : "https://wa.me/" + n;
    }

    @GetMapping("/api/v1/channel-pin/{token}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> describe(@PathVariable String token, HttpServletRequest req) {
        return Web.run(() -> {
            Map<String, Object> out = new LinkedHashMap<>(pins.describe(token));
            out.put("returnUrl", back());
            return out;
        }, req);
    }

    public record Body(String pin, String newPin, String confirm) {}

    @PostMapping("/api/v1/channel-pin/{token}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submit(@PathVariable String token, @RequestBody Body b, HttpServletRequest req) {
        return Web.run(() -> {
            ChannelPinService.Request r = pins.submit(token, b == null ? null : b.pin(), b == null ? null : b.newPin(), b == null ? null : b.confirm());
            try {
                whatsapp.continueAfterPin(r.waId(), r.session());
            } catch (RuntimeException e) {
                log.warn("[WHATSAPP] carrying on after the PIN page failed: {}", e.getMessage());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("done", true);
            out.put("returnUrl", back());
            return out;
        }, req);
    }
}
