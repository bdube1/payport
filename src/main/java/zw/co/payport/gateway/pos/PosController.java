package zw.co.payport.gateway.pos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.config.MerchantAuthResolver;
import zw.co.payport.gateway.entity.MerchantUser;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.Web;

import java.util.List;
import java.util.Map;

/** POS: signed terminal API (no login; HMAC per request), the merchant's terminal management, and ops. */
@RestController
public class PosController {

    private final PosService pos;
    private final ObjectMapper json = new ObjectMapper();

    public PosController(PosService pos) {
        this.pos = pos;
    }

    private Map<String, Object> terminal(HttpServletRequest req, String body) {
        return pos.authenticate(req.getHeader("X-Terminal-Id"), req.getHeader("X-Timestamp"), req.getHeader("X-Nonce"), req.getHeader("X-Signature"),
                req.getMethod(), req.getRequestURI(), body);
    }

    // ─── terminal API ────────────────────────────────────────────────────────

    @PostMapping("/api/v1/pos/sales")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sale(@RequestBody(required = false) String body, HttpServletRequest req) {
        return Web.call(() -> {
            Map<String, Object> t = terminal(req, body);
            JsonNode n = json.readTree(body == null ? "{}" : body);
            return pos.createSale(t, n.path("saleRef").asText(null), n.path("amountMinor").asLong(), n.path("currency").asText(null), n.path("description").asText(null));
        }, req);
    }

    @GetMapping("/api/v1/pos/sales/{saleRef}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> saleStatus(@PathVariable String saleRef, HttpServletRequest req) {
        return Web.run(() -> pos.sale(terminal(req, ""), saleRef), req);
    }

    @PostMapping("/api/v1/pos/sales/{saleRef}/card")
    public ResponseEntity<ApiResponse<Map<String, Object>>> card(@PathVariable String saleRef, @RequestBody(required = false) String body, HttpServletRequest req) {
        return Web.call(() -> {
            Map<String, Object> t = terminal(req, body);
            return pos.card(t, saleRef, json.readTree(body == null ? "{}" : body).path("encryptedCardData").asText(null));
        }, req);
    }

    // ─── merchant dashboard ──────────────────────────────────────────────────

    private static String actor() {
        MerchantUser u = MerchantAuthResolver.currentMerchantUserOrNull();
        return "merchant:" + MerchantAuthResolver.currentMerchant().getId() + (u == null ? "" : ":user:" + u.getId());
    }

    @GetMapping("/api/v1/merchant/pos/terminals")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> terminals(HttpServletRequest req) {
        return Web.run(() -> pos.terminals(MerchantAuthResolver.currentMerchant().getId()), req);
    }

    public record RegisterBody(String label) {}

    @PostMapping("/api/v1/merchant/pos/terminals")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> register(@RequestBody RegisterBody b, HttpServletRequest req) {
        return Web.run(() -> pos.register(MerchantAuthResolver.currentMerchant(), b.label(), actor()), req);
    }

    public record StatusBody(boolean active, String reason) {}

    @PostMapping("/api/v1/merchant/pos/terminals/{terminalId}/status")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> merchantStatus(@PathVariable String terminalId, @RequestBody StatusBody b, HttpServletRequest req) {
        return Web.run(() -> pos.setStatus(MerchantAuthResolver.currentMerchant().getId(), terminalId, b.active(), b.reason(), actor()), req);
    }

    @GetMapping("/api/v1/merchant/pos/sales")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> sales(HttpServletRequest req) {
        return Web.run(() -> pos.sales(MerchantAuthResolver.currentMerchant().getId()), req);
    }

    // ─── ops ─────────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/admin/pos/terminals")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','COMPLIANCE')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> all(HttpServletRequest req) {
        return Web.run(() -> pos.terminals(null), req);
    }

    @PostMapping("/api/v1/admin/pos/terminals/{terminalId}/status")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> opsStatus(@PathVariable String terminalId, @RequestBody StatusBody b, HttpServletRequest req) {
        return Web.run(() -> pos.setStatus(null, terminalId, b.active(), b.reason(), Web.staff()), req);
    }
}
