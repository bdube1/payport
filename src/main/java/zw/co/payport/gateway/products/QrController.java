package zw.co.payport.gateway.products;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.channels.ChannelService;
import zw.co.payport.gateway.markets.MarketRegistry;
import zw.co.payport.gateway.model.response.ApiResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 8 QR: a dynamic EMVCo QR for an open checkout session (shown on the hosted page or a till),
 * and resolution of a scanned PayPort QR back to the session to pay. Works only while the QR channel
 * is switched on, which needs the QR_PAYMENTS licence question answered first.
 */
@RestController
public class QrController {

    private final JdbcTemplate jdbc;
    private final ChannelService channels;
    private final MarketRegistry markets;

    public QrController(JdbcTemplate jdbc, ChannelService channels, MarketRegistry markets) {
        this.jdbc = jdbc;
        this.channels = channels;
        this.markets = markets;
    }

    @GetMapping("/api/v1/checkout/{id}/qr")
    public ResponseEntity<ApiResponse<Map<String, Object>>> qr(@PathVariable Long id, HttpServletRequest req) {
        channels.require("QR");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT cs.id, cs.reference, cs.amount_cents, cs.currency, cs.status, m.business_name "
                + "FROM checkout_sessions cs JOIN merchants m ON m.id = cs.merchant_id WHERE cs.id = ?", id);
        if (rows.isEmpty()) return ResponseEntity.status(404).body(ApiResponse.error("NOT_FOUND", "Checkout session not found", rid(req)));
        Map<String, Object> s = rows.get(0);
        if (!"CREATED".equals(s.get("status"))) {
            return ResponseEntity.status(409).body(ApiResponse.error("NOT_PAYABLE", "This checkout session cannot accept payment", rid(req)));
        }
        String ccy = (String) s.get("currency");
        MarketRegistry.Market market = markets.requireForCurrency(ccy);
        String numeric = markets.isoNumeric(ccy).orElseThrow(() -> new IllegalStateException("No ISO numeric code configured for " + ccy));
        String payload = EmvQr.build(new EmvQr.Payload(true, "cs:" + s.get("id"), null, numeric, ((Number) s.get("amount_cents")).longValue(),
                market.country(), (String) s.get("business_name"), null, (String) s.get("reference")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", s.get("id"));
        out.put("payload", payload);
        out.put("amountMinor", s.get("amount_cents"));
        out.put("currency", ccy);
        out.put("merchantName", s.get("business_name"));
        return ResponseEntity.ok(ApiResponse.success(out, rid(req)));
    }

    public record ResolveBody(String payload) {}

    /** A wallet or the PayPort app scanned a code: which session is it, and can it still be paid? */
    @PostMapping("/api/v1/qr/resolve")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resolve(@RequestBody ResolveBody b, HttpServletRequest req) {
        channels.require("QR");
        EmvQr.Payload p;
        try {
            p = EmvQr.parse(b == null ? null : b.payload());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_QR", e.getMessage(), rid(req)));
        }
        if (p.merchantRef() == null || !p.merchantRef().startsWith("cs:")) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_QR", "Not a PayPort checkout code", rid(req)));
        }
        long id;
        try { id = Long.parseLong(p.merchantRef().substring(3)); } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_QR", "Not a PayPort checkout code", rid(req)));
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, amount_cents, currency, status FROM checkout_sessions WHERE id = ?", id);
        if (rows.isEmpty()) return ResponseEntity.status(404).body(ApiResponse.error("NOT_FOUND", "Checkout session not found", rid(req)));
        Map<String, Object> s = rows.get(0);
        // The code must match the session exactly (amount and currency), so an altered code cannot change what is paid.
        boolean matches = p.amountMinor() != null && p.amountMinor().equals(((Number) s.get("amount_cents")).longValue())
                && markets.isoNumeric((String) s.get("currency")).map(n -> n.equals(p.currencyNumeric())).orElse(false);
        if (!matches) return ResponseEntity.status(409).body(ApiResponse.error("QR_MISMATCH", "This code does not match the checkout session", rid(req)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", s.get("id"));
        out.put("amountMinor", s.get("amount_cents"));
        out.put("currency", s.get("currency"));
        out.put("payable", "CREATED".equals(s.get("status")));
        out.put("merchantName", p.merchantName());
        return ResponseEntity.ok(ApiResponse.success(out, rid(req)));
    }

    private static String rid(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
