package zw.co.payport.gateway.pos;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.channels.ChannelService;
import zw.co.payport.gateway.channels.ChannelUnavailableException;
import zw.co.payport.gateway.entity.CheckoutSession;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.platform.SecretBox;
import zw.co.payport.gateway.products.ServiceLineService;
import zw.co.payport.gateway.repository.MerchantRepository;
import zw.co.payport.gateway.risk.ComplianceAudit;
import zw.co.payport.gateway.risk.ComplianceParameters;
import zw.co.payport.gateway.service.CheckoutSessionService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * POS terminals (Phase 8). A terminal is registered by its merchant and gets a secret shown once
 * (stored sealed). Every terminal request is signed:
 *
 *   X-Terminal-Id, X-Timestamp (epoch seconds), X-Nonce (unique per request), X-Signature =
 *   hex(HMAC-SHA256(secret, METHOD \n PATH \n TIMESTAMP \n NONCE \n hex(SHA-256(body))))
 *
 * A request outside the clock window (POS.SIGNATURE_WINDOW_SECONDS) or with a nonce already seen is
 * refused, so a captured request cannot be replayed. A sale creates a merchant checkout session that
 * the customer pays by QR, wallet or mobile money on the same rails as hosted checkout. Cards fail
 * closed until an acquirer is configured ({@link CardAcquirer}). Behind POS_ACQUIRING and channel POS.
 */
@Slf4j
@Service
public class PosService {

    public static final String LINE = "POS_ACQUIRING";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final SecretBox secrets;
    private final ServiceLineService lines;
    private final ChannelService channels;
    private final ComplianceParameters params;
    private final ComplianceAudit audit;
    private final CheckoutSessionService checkout;
    private final MerchantRepository merchants;

    @Autowired(required = false)
    private List<CardAcquirer> acquirers;

    public PosService(JdbcTemplate jdbc, SecretBox secrets, ServiceLineService lines, ChannelService channels, ComplianceParameters params,
                      ComplianceAudit audit, CheckoutSessionService checkout, MerchantRepository merchants) {
        this.jdbc = jdbc;
        this.secrets = secrets;
        this.lines = lines;
        this.channels = channels;
        this.params = params;
        this.audit = audit;
        this.checkout = checkout;
        this.merchants = merchants;
    }

    // ─── terminals ───────────────────────────────────────────────────────────

    public Map<String, Object> register(Merchant merchant, String label, String by) {
        lines.require(LINE, "Point-of-sale terminals");
        if (!"ACTIVE".equals(merchant.getStatus())) throw new IllegalStateException("Only an active merchant can register terminals");
        if (label == null || label.isBlank()) throw new IllegalArgumentException("A label is required (e.g. Till 1)");
        byte[] id = new byte[5];
        RANDOM.nextBytes(id);
        String terminalId = "T" + HexFormat.of().formatHex(id).toUpperCase(Locale.ROOT);
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.update("INSERT INTO pos_terminals (terminal_id, merchant_id, label, secret_sealed, created_by, created_at) VALUES (?,?,?,?,?,?)",
                terminalId, merchant.getId(), label.trim(), secrets.seal(secret), by, now());
        audit.record(by, "POS_TERMINAL_REGISTERED", "pos:" + terminalId, "merchant " + merchant.getId() + " " + label);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("terminalId", terminalId);
        out.put("secret", secret);
        out.put("note", "Store the secret in the terminal now. PayPort will not show it again.");
        return out;
    }

    public List<Map<String, Object>> terminals(Long merchantId) {
        String cols = "t.id, t.terminal_id, t.merchant_id, t.label, t.status, t.status_reason, t.created_by, t.created_at, t.last_seen_at";
        return merchantId == null
                ? jdbc.queryForList("SELECT " + cols + ", m.business_name FROM pos_terminals t LEFT JOIN merchants m ON m.id = t.merchant_id ORDER BY t.id DESC LIMIT 500")
                : jdbc.queryForList("SELECT " + cols + " FROM pos_terminals t WHERE t.merchant_id = ? ORDER BY t.id DESC", merchantId);
    }

    public Map<String, Object> setStatus(Long merchantId, String terminalId, boolean active, String reason, String by) {
        if (reason == null || reason.isBlank()) reason = active ? "Enabled" : "Disabled";
        int n = merchantId == null
                ? jdbc.update("UPDATE pos_terminals SET status = ?, status_reason = ? WHERE terminal_id = ?", active ? "ACTIVE" : "DISABLED", reason, terminalId)
                : jdbc.update("UPDATE pos_terminals SET status = ?, status_reason = ? WHERE terminal_id = ? AND merchant_id = ?", active ? "ACTIVE" : "DISABLED", reason, terminalId, merchantId);
        if (n == 0) throw new IllegalArgumentException("No such terminal");
        audit.record(by, active ? "POS_TERMINAL_ENABLED" : "POS_TERMINAL_DISABLED", "pos:" + terminalId, reason);
        return Map.of("terminalId", terminalId, "status", active ? "ACTIVE" : "DISABLED");
    }

    public List<Map<String, Object>> sales(long merchantId) {
        return jdbc.queryForList("SELECT s.terminal_id, s.sale_ref, s.checkout_session_id, s.amount_minor, s.currency, s.created_at, cs.status "
                + "FROM pos_sales s LEFT JOIN checkout_sessions cs ON cs.id = s.checkout_session_id WHERE s.merchant_id = ? ORDER BY s.id DESC LIMIT 500", merchantId);
    }

    // ─── signed terminal requests ────────────────────────────────────────────

    /** Verifies the signature, clock window and nonce; returns the terminal. Throws SecurityException otherwise. */
    public Map<String, Object> authenticate(String terminalId, String timestamp, String nonce, String signature, String method, String path, String body) {
        if (terminalId == null || timestamp == null || nonce == null || signature == null) throw new SecurityException("Missing terminal signature headers");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM pos_terminals WHERE terminal_id = ?", terminalId);
        if (rows.isEmpty()) throw new SecurityException("Unknown terminal");
        Map<String, Object> t = rows.get(0);
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            throw new SecurityException("Bad timestamp");
        }
        long window = (long) params.number("POS.SIGNATURE_WINDOW_SECONDS");
        if (Math.abs(Instant.now().getEpochSecond() - ts) > window) throw new SecurityException("Request timestamp outside the allowed window");
        if (nonce.length() < 8 || nonce.length() > 64) throw new SecurityException("Bad nonce");
        String expected = sign(secrets.open((String) t.get("secret_sealed")), method, path, timestamp.trim(), nonce, body);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("Bad signature");
        }
        try {
            jdbc.update("INSERT INTO pos_nonces (terminal_id, nonce, seen_at) VALUES (?,?,?)", terminalId, nonce, now());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new SecurityException("Replayed request");
        }
        if (!"ACTIVE".equals(t.get("status"))) throw new SecurityException("Terminal is disabled");
        jdbc.update("UPDATE pos_terminals SET last_seen_at = ? WHERE terminal_id = ?", now(), terminalId);
        if (RANDOM.nextInt(50) == 0) jdbc.update("DELETE FROM pos_nonces WHERE seen_at < ?", Timestamp.valueOf(LocalDateTime.now().minusDays(1)));
        return t;
    }

    public static String sign(String secret, String method, String path, String timestamp, String nonce, String body) {
        try {
            String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((body == null ? "" : body).getBytes(StandardCharsets.UTF_8)));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String canonical = method.toUpperCase(Locale.ROOT) + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodyHash;
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ─── sales ───────────────────────────────────────────────────────────────

    public Map<String, Object> createSale(Map<String, Object> terminal, String saleRef, long amount, String currency, String description) {
        lines.require(LINE, "Point-of-sale payments");
        channels.require("POS");
        if (saleRef == null || saleRef.isBlank() || saleRef.length() > 64) throw new IllegalArgumentException("saleRef is required (max 64 characters)");
        String terminalId = (String) terminal.get("terminal_id");
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT * FROM pos_sales WHERE terminal_id = ? AND sale_ref = ?", terminalId, saleRef);
        if (!existing.isEmpty()) return view(existing.get(0));
        long merchantId = ((Number) terminal.get("merchant_id")).longValue();
        Merchant m = merchants.findById(merchantId).orElseThrow(() -> new IllegalStateException("Merchant not found"));
        if (!"ACTIVE".equals(m.getStatus())) throw new IllegalStateException("This merchant account is not active");
        CheckoutSession s = checkout.createSession(m, null, new CheckoutSessionService.CreateSessionRequest(
                "pos_" + terminalId + "_" + saleRef, amount, currency, description == null ? "POS sale " + saleRef : description,
                null, null, null, null, "{\"pos\":\"" + terminalId + "\"}"));
        jdbc.update("INSERT INTO pos_sales (terminal_id, merchant_id, sale_ref, checkout_session_id, amount_minor, currency, created_at) VALUES (?,?,?,?,?,?,?)",
                terminalId, merchantId, saleRef, s.getId(), s.getAmountCents(), s.getCurrency(), now());
        return view(jdbc.queryForMap("SELECT * FROM pos_sales WHERE terminal_id = ? AND sale_ref = ?", terminalId, saleRef));
    }

    public Map<String, Object> sale(Map<String, Object> terminal, String saleRef) {
        return view(jdbc.queryForList("SELECT * FROM pos_sales WHERE terminal_id = ? AND sale_ref = ?", terminal.get("terminal_id"), saleRef).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No such sale")));
    }

    /** Card at the terminal: refused unless a configured acquirer exists. */
    public Map<String, Object> card(Map<String, Object> terminal, String saleRef, String encryptedCardData) throws Exception {
        lines.require(LINE, "Point-of-sale payments");
        channels.require("POS");
        CardAcquirer a = acquirers == null ? null : acquirers.stream().filter(CardAcquirer::configured).findFirst().orElse(null);
        if (a == null) throw new ChannelUnavailableException("POS", "Card payments at terminals are not available: no card acquirer is configured. Use QR, wallet or mobile money.");
        Map<String, Object> sale = sale(terminal, saleRef);
        if (encryptedCardData == null || encryptedCardData.isBlank()) throw new IllegalArgumentException("Encrypted card data is required");
        CardAcquirer.Authorisation r = a.authorise(saleRef, ((Number) sale.get("amountMinor")).longValue(), (String) sale.get("currency"), encryptedCardData);
        Map<String, Object> out = new LinkedHashMap<>(sale);
        out.put("cardApproved", r.approved());
        out.put("cardOutcomeUnknown", r.outcomeUnknown());
        out.put("authCode", r.authCode());
        out.put("message", r.message());
        return out;
    }

    private Map<String, Object> view(Map<String, Object> sale) {
        long sessionId = ((Number) sale.get("checkout_session_id")).longValue();
        CheckoutSession s = checkout.getPublic(sessionId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("saleRef", sale.get("sale_ref"));
        out.put("terminalId", sale.get("terminal_id"));
        out.put("checkoutSessionId", sessionId);
        out.put("amountMinor", sale.get("amount_minor"));
        out.put("currency", sale.get("currency"));
        out.put("status", s.getStatus());
        out.put("paid", "PAID".equals(s.getStatus()) || "COMPLETED".equals(s.getStatus()));
        out.put("qrPath", "/api/v1/checkout/" + sessionId + "/qr");
        out.put("checkoutPath", "/checkout/" + sessionId);
        return out;
    }

    static Timestamp now() {
        return Timestamp.valueOf(LocalDateTime.now());
    }
}
