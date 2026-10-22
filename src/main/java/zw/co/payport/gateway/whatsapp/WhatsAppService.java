package zw.co.payport.gateway.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.ussd.UssdService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * WhatsApp payments (Phase 8) over the WhatsApp Cloud API webhook. The conversation is the USSD
 * menu engine with channel WHATSAPP: each message the customer sends is the next menu choice;
 * "menu", "hi" or "0" start again. Payments use the same orchestrator, risk checks and switches
 * (channel WHATSAPP, licence line WHATSAPP_CHANNEL); the mobile money prompt still asks the customer
 * for their wallet PIN on their phone, so no PIN is ever typed into WhatsApp.
 *
 * Webhook security: X-Hub-Signature-256 = sha256=HMAC-SHA256(app secret, raw body), checked in
 * constant time; without a configured app secret every delivery is refused (fails closed).
 * Messages are de-duplicated by WhatsApp message id (Meta retries deliveries).
 */
@Slf4j
@Service
public class WhatsAppService {

    static final Set<String> RESTART = Set.of("menu", "hi", "hello", "0", "start");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final UssdService engine;
    private final WhatsAppSender sender;
    private final String appSecret;
    private final String verifyToken;
    private final ObjectMapper json = new ObjectMapper();

    public WhatsAppService(JdbcTemplate jdbc, UssdService engine, WhatsAppSender sender,
                           @Value("${payport.whatsapp.app-secret:}") String appSecret,
                           @Value("${payport.whatsapp.verify-token:}") String verifyToken) {
        this.jdbc = jdbc;
        this.engine = engine;
        this.sender = sender;
        this.appSecret = appSecret;
        this.verifyToken = verifyToken;
    }

    public boolean configured() {
        return !appSecret.isBlank();
    }

    /** Meta's subscription handshake. */
    public String verify(String mode, String token, String challenge) {
        if (verifyToken.isBlank() || !"subscribe".equals(mode) || token == null
                || !MessageDigest.isEqual(verifyToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("verification refused");
        }
        return challenge == null ? "" : challenge;
    }

    public boolean signatureValid(String header, String rawBody) {
        if (!configured() || header == null || !header.startsWith("sha256=")) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal((rawBody == null ? "" : rawBody).getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), header.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    public record Reply(String to, String text, String sendError) {}

    /** Processes a verified webhook delivery; returns the replies (sent, or recorded with why not). */
    public List<Reply> receive(String rawBody) throws Exception {
        List<Reply> out = new ArrayList<>();
        JsonNode root = json.readTree(rawBody == null ? "{}" : rawBody);
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                for (JsonNode m : change.path("value").path("messages")) {
                    String id = m.path("id").asText(null);
                    String from = m.path("from").asText("").replaceAll("[^0-9]", "");
                    if (id == null || from.isEmpty()) continue;
                    try {
                        jdbc.update("INSERT INTO whatsapp_messages_seen (message_id, wa_id, received_at) VALUES (?,?,?)", id, from, now());
                    } catch (org.springframework.dao.DuplicateKeyException e) {
                        continue;   // Meta retried a delivery we already answered
                    }
                    String text = "text".equals(m.path("type").asText()) ? m.path("text").path("body").asText("") : "";
                    String reply = converse(from, text);
                    String err = sender.send(from, reply);
                    jdbc.update("UPDATE whatsapp_messages_seen SET reply = ?, sent = ?, send_error = ? WHERE message_id = ?",
                            clip(reply, 1000), err == null, clip(err, 300), id);
                    if (err != null) log.warn("[WHATSAPP] reply to {} not sent: {}", mask(from), err);
                    out.add(new Reply(from, reply, err));
                }
            }
        }
        return out;
    }

    /** One step of the conversation for this WhatsApp number. */
    String converse(String waId, String text) {
        String input = text == null ? "" : text.replace("*", "").replace("#", "").trim();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT session_id, path FROM whatsapp_sessions WHERE wa_id = ?", waId);
        boolean restart = rows.isEmpty() || RESTART.contains(input.toLowerCase(Locale.ROOT));
        String session = restart ? "wa:" + waId + ":" + token() : (String) rows.get(0).get("session_id");
        String path = restart ? "" : ((String) rows.get(0).get("path")).isEmpty() ? input : rows.get(0).get("path") + "*" + input;
        if (path.length() > 450) path = "";
        String r = engine.handle("WHATSAPP", session, "+" + waId, path);
        boolean end = r.startsWith("END");
        if (end) {
            jdbc.update("DELETE FROM whatsapp_sessions WHERE wa_id = ?", waId);
        } else {
            jdbc.update("INSERT INTO whatsapp_sessions (wa_id, session_id, path, updated_at) VALUES (?,?,?,?) "
                    + "ON DUPLICATE KEY UPDATE session_id = VALUES(session_id), path = VALUES(path), updated_at = VALUES(updated_at)", waId, session, path, now());
        }
        String body = r.length() > 4 ? r.substring(4) : r;
        return end ? body + "\n\nSend MENU to start again." : body + "\n\nReply with a number, or MENU to start again.";
    }

    public List<Map<String, Object>> recent() {
        return jdbc.queryForList("SELECT message_id, CONCAT('***', RIGHT(wa_id, 4)) AS wa_id, received_at, sent, send_error FROM whatsapp_messages_seen ORDER BY received_at DESC LIMIT 100");
    }

    public Map<String, Object> status() {
        return Map.of("webhookSecretConfigured", configured(), "verifyTokenConfigured", !verifyToken.isBlank(), "senderConfigured", sender.configured());
    }

    private static String token() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static String mask(String s) {
        return s.length() <= 4 ? "****" : "***" + s.substring(s.length() - 4);
    }

    private static String clip(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }

    private static Timestamp now() {
        return Timestamp.valueOf(LocalDateTime.now());
    }
}
