package zw.co.payport.gateway.whatsapp;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.Web;
import zw.co.payport.gateway.ussd.UssdService;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Test phone for staging: drives the real WhatsApp conversation and the real USSD menus for a number and returns
 * the reply on screen instead of sending it, so both channels can be compared with the mobile app. Refused in
 * production.
 */
@RestController
public class ChannelSimulatorController {

    static final Set<String> TEST_ENVIRONMENTS = Set.of("staging", "sandbox", "local", "dev", "test");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WhatsAppService whatsapp;
    private final UssdService ussd;
    private final String environment;

    public ChannelSimulatorController(WhatsAppService whatsapp, UssdService ussd, @Value("${payport.environment:production}") String environment) {
        this.whatsapp = whatsapp;
        this.ussd = ussd;
        this.environment = environment == null ? "production" : environment.trim().toLowerCase();
    }

    public record WhatsAppBody(String phone, String text) {}
    public record UssdBody(String phone, String session, String path) {}

    private void testOnly() {
        if (!TEST_ENVIRONMENTS.contains(environment)) throw new IllegalStateException("The test phone is available on staging only");
    }

    private static String phone(String raw) {
        String d = raw == null ? "" : raw.replaceAll("[^0-9]", "");
        if (d.startsWith("0") && d.length() == 10) d = "263" + d.substring(1);
        if (d.length() < 9 || d.length() > 15) throw new IllegalArgumentException("Enter the phone number with its country code, e.g. 263771234567");
        return d;
    }

    /** One WhatsApp message from the number; returns exactly what Payport would reply on WhatsApp. */
    @PostMapping("/api/v1/admin/whatsapp/simulate")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> whatsapp(@RequestBody WhatsAppBody b, HttpServletRequest req) {
        return Web.run(() -> {
            testOnly();
            String reply = whatsapp.converse(phone(b.phone()), b.text() == null ? "" : b.text());
            return Map.<String, Object>of("reply", reply);
        }, req);
    }

    /** One USSD step: path is everything typed so far joined by '*', as the networks send it. */
    @PostMapping("/api/v1/admin/ussd/simulate")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> ussd(@RequestBody UssdBody b, HttpServletRequest req) {
        return Web.run(() -> {
            testOnly();
            String session = b.session() == null || b.session().isBlank() ? "sim:" + HexFormat.of().formatHex(bytes()) : b.session();
            String r = ussd.handle("USSD", session, "+" + phone(b.phone()), b.path() == null ? "" : b.path());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("session", session);
            out.put("end", r.startsWith("END"));
            out.put("text", r.length() > 4 ? r.substring(4) : r);
            return out;
        }, req);
    }

    /** Messages Payport started to the test number (after a PIN page, payment updates): the test phone shows them. */
    @org.springframework.web.bind.annotation.GetMapping("/api/v1/admin/whatsapp/simulate/outbox")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ResponseEntity<ApiResponse<java.util.List<Map<String, Object>>>> outbox(@org.springframework.web.bind.annotation.RequestParam String phone,
                                                                                @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") long after, HttpServletRequest req) {
        return Web.run(() -> {
            testOnly();
            return whatsapp.outboxFor(phone(phone), after);
        }, req);
    }

    private static byte[] bytes() {
        byte[] x = new byte[6];
        RANDOM.nextBytes(x);
        return x;
    }
}
