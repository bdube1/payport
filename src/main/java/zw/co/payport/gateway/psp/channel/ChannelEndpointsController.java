package zw.co.payport.gateway.psp.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.psp.common.Api;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.common.Refs;
import zw.co.payport.gateway.psp.legacy.LegacyGateway;
import zw.co.payport.gateway.psp.legacy.LegacyPayments;
import zw.co.payport.gateway.psp.platform.idempotency.IdempotencyService;
import zw.co.payport.gateway.psp.routing.RoutingService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Inbound channel endpoints: POS terminals (signed), USSD aggregator callback, WhatsApp Cloud API webhook. */
@RestController
@RequestMapping("/api/v1/psp/channels")
public class ChannelEndpointsController {

    private static final Logger log = LoggerFactory.getLogger(ChannelEndpointsController.class);

    public record PosPaymentBody(long amountMinor, String currency, String customerMsisdn, String method) {}
    public record PosQrBody(long amountMinor, String currency, String reference) {}
    public record HeartbeatBody(String appVersion) {}

    private final UssdService ussd;
    private final WhatsAppBot whatsApp;
    private final LegacyPayments payments;
    private final LegacyGateway gateway;
    private final RoutingService routing;
    private final ChannelAdminService channels;
    private final IdempotencyService idempotency;
    private final ObjectMapper om;

    @Value("${payport.psp.ussd.callback-token:}")
    private String ussdToken;
    @Value("${payport.psp.ussd.allowed-ips:}")
    private String ussdAllowedIps;
    @Value("${payport.psp.whatsapp.verify-token:}")
    private String waVerifyToken;
    @Value("${payport.psp.whatsapp.app-secret:}")
    private String waAppSecret;

    public ChannelEndpointsController(UssdService ussd, WhatsAppBot whatsApp, LegacyPayments payments, LegacyGateway gateway,
                                      RoutingService routing, ChannelAdminService channels, IdempotencyService idempotency, ObjectMapper om) {
        this.ussd = ussd;
        this.whatsApp = whatsApp;
        this.payments = payments;
        this.gateway = gateway;
        this.routing = routing;
        this.channels = channels;
        this.idempotency = idempotency;
        this.om = om;
    }

    // ── USSD (Africa's Talking callback format) ─────────────────────────

    @PostMapping(value = "/ussd/at", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> ussd(@RequestParam String sessionId, @RequestParam String phoneNumber,
                                       @RequestParam(required = false) String serviceCode, @RequestParam(required = false) String text,
                                       @RequestParam(required = false) String token, HttpServletRequest req) {
        if (ussdToken == null || ussdToken.isBlank() || token == null
                || !MessageDigest.isEqual(ussdToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(403).body("END Service unavailable");
        }
        if (ussdAllowedIps != null && !ussdAllowedIps.isBlank()
                && Arrays.stream(ussdAllowedIps.split(",")).map(String::trim).noneMatch(req.getRemoteAddr()::equals)) {
            return ResponseEntity.status(403).body("END Service unavailable");
        }
        return ResponseEntity.ok(ussd.handle(sessionId, phoneNumber, text));
    }

    // ── WhatsApp Cloud API webhook ───────────────────────────────────────

    @GetMapping(value = "/whatsapp/webhook", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verify(@RequestParam("hub.mode") String mode, @RequestParam("hub.verify_token") String verifyToken,
                                         @RequestParam("hub.challenge") String challenge) {
        if ("subscribe".equals(mode) && waVerifyToken != null && !waVerifyToken.isBlank() && waVerifyToken.equals(verifyToken)) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(403).body("forbidden");
    }

    @PostMapping("/whatsapp/webhook")
    public ResponseEntity<String> inbound(@RequestBody String body,
                                          @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) {
        if (waAppSecret == null || waAppSecret.isBlank()
                || !RequestSigning.verifyHexBodySignature(waAppSecret, body.getBytes(StandardCharsets.UTF_8), signature)) {
            log.warn("[WHATSAPP] Rejected webhook with bad signature");
            return ResponseEntity.status(401).body("bad signature");
        }
        try {
            JsonNode root = om.readTree(body);
            for (JsonNode entry : root.path("entry")) {
                for (JsonNode change : entry.path("changes")) {
                    for (JsonNode msg : change.path("value").path("messages")) {
                        String id = msg.path("id").asText();
                        String from = msg.path("from").asText();
                        String text = "text".equals(msg.path("type").asText()) ? msg.path("text").path("body").asText() : "menu";
                        // Meta retries deliveries: handle each message id once.
                        idempotency.execute("whatsapp", id, id, () -> new IdempotencyService.StoredResponse(200, whatsApp.onMessage(from, text), null));
                    }
                }
            }
        } catch (Exception e) {
            log.error("[WHATSAPP] Could not process webhook: {}", e.getMessage());
        }
        return ResponseEntity.ok("EVENT_RECEIVED");
    }

    // ── POS / mPOS terminals (HMAC-signed by SignedRequestFilter) ─────────

    @PostMapping("/pos/payments")
    @PreAuthorize("hasRole('TERMINAL')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> posPayment(@RequestBody PosPaymentBody b, HttpServletRequest req) throws Exception {
        SignedRequestFilter.PartnerPrincipal t = PartnerApiController.principal();
        String method = b.method() == null || "AUTO".equalsIgnoreCase(b.method())
                ? routing.walletProviderFor(b.customerMsisdn(), b.currency(), b.amountMinor()) : b.method().toUpperCase();
        long sessionId = payments.createCheckout(t.merchantId(), "pos_" + t.keyId() + "_" + Refs.ulid(), b.amountMinor(), b.currency(),
                "POS " + t.keyId(), b.customerMsisdn());
        var r = payments.payCheckout(sessionId, b.customerMsisdn(), method, req.getRemoteAddr());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("method", method);
        out.put("status", r.session().getStatus());
        return Api.created(out, req);
    }

    @GetMapping("/pos/payments/{sessionId}")
    @PreAuthorize("hasRole('TERMINAL')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> posStatus(@PathVariable long sessionId, HttpServletRequest req) {
        SignedRequestFilter.PartnerPrincipal t = PartnerApiController.principal();
        LegacyGateway.CheckoutLink c = gateway.checkoutSession(sessionId).filter(x -> x.merchantId() == t.merchantId())
                .orElseThrow(() -> PspException.notFound("Payment", sessionId));
        return Api.ok(Map.of("sessionId", sessionId, "status", c.status(), "amountMinor", c.amountCents(), "currency", c.currency()), req);
    }

    @PostMapping("/pos/qr")
    @PreAuthorize("hasRole('TERMINAL')")
    public ResponseEntity<ApiResponse<Map<String, String>>> posQr(@RequestBody PosQrBody b, HttpServletRequest req) {
        SignedRequestFilter.PartnerPrincipal t = PartnerApiController.principal();
        LegacyGateway.MerchantSnapshot m = gateway.merchant(t.merchantId()).orElseThrow(() -> PspException.notFound("Merchant", t.merchantId()));
        String payload = EmvQr.build(new EmvQr.Payload(true, String.valueOf(m.id()), null, b.currency(), b.amountMinor(),
                m.businessName(), "HARARE", b.reference() != null ? b.reference() : t.keyId()));
        return Api.ok(Map.of("payload", payload), req);
    }

    @PostMapping("/pos/heartbeat")
    @PreAuthorize("hasRole('TERMINAL')")
    public ResponseEntity<ApiResponse<String>> heartbeat(@RequestBody(required = false) HeartbeatBody b, HttpServletRequest req) {
        channels.heartbeat(PartnerApiController.principal().keyId(), b == null ? null : b.appVersion());
        return Api.ok("ok", req);
    }
}
