package zw.co.payport.gateway.whatsapp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.entity.User;
import zw.co.payport.gateway.repository.UserRepository;
import zw.co.payport.gateway.service.AuthService;
import zw.co.payport.gateway.wallets.PinVerifier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Secure PIN entry for WhatsApp (owner, 2026-10-30): a PIN is never typed into the chat, where it would stay in the
 * customer's message history. When the conversation needs one, the customer gets a one-time link to a PayPort page;
 * the page checks or sets the PIN here and the conversation carries on in WhatsApp.
 *
 * Only the SHA-256 of the link token is stored; a link works once and for 10 minutes; a new link for the same step
 * replaces the old one. A wrong login PIN counts against the account like anywhere else (PinVerifier).
 */
@Service
public class ChannelPinService {

    public static final String LOGIN = "LOGIN", REGISTER = "REGISTER", CHANGE = "CHANGE";
    static final int MINUTES = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final PinVerifier pins;
    private final AuthService auth;
    private final PasswordEncoder encoder;
    private final UserRepository users;
    private final String consumerBase;

    public ChannelPinService(JdbcTemplate jdbc, PinVerifier pins, AuthService auth, PasswordEncoder encoder, UserRepository users,
                             @Value("${payport.consumer.base-url:https://app.payport.africa}") String consumerBase) {
        this.jdbc = jdbc;
        this.pins = pins;
        this.auth = auth;
        this.encoder = encoder;
        this.users = users;
        this.consumerBase = consumerBase == null ? "" : consumerBase.replaceAll("/+$", "");
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A new one-time link for this step of the session (the previous one for the same step stops working). */
    public String link(String session, String waId, String purpose, Long userId) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("UPDATE channel_pin_requests SET used_at = ? WHERE session_id = ? AND purpose = ? AND used_at IS NULL", now, session, purpose);
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        jdbc.update("INSERT INTO channel_pin_requests (token_hash, session_id, wa_id, purpose, user_id, expires_at, created_at) VALUES (?,?,?,?,?,?,?)",
                hash(token), session, waId.replaceAll("[^0-9]", ""), purpose, userId, Timestamp.valueOf(now.toLocalDateTime().plusMinutes(MINUTES)), now);
        return consumerBase + "/pin/" + token;
    }

    public record Request(String tokenHash, String session, String waId, String purpose, Long userId) {}

    private Request open(String token) {
        if (token == null || token.length() < 20 || token.length() > 64) throw new IllegalArgumentException("This link is not valid.");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT session_id, wa_id, purpose, user_id, expires_at, used_at FROM channel_pin_requests WHERE token_hash = ?", hash(token));
        if (rows.isEmpty()) throw new IllegalArgumentException("This link is not valid.");
        Map<String, Object> r = rows.get(0);
        if (r.get("used_at") != null) throw new IllegalStateException("This link has already been used. Go back to WhatsApp.");
        Object exp = r.get("expires_at");
        LocalDateTime expires = exp instanceof Timestamp t ? t.toLocalDateTime() : (LocalDateTime) exp;
        if (expires.isBefore(LocalDateTime.now()))
            throw new IllegalStateException("This link has expired. Go back to WhatsApp and send MENU to start again.");
        return new Request(hash(token), (String) r.get("session_id"), (String) r.get("wa_id"), (String) r.get("purpose"),
                r.get("user_id") == null ? null : ((Number) r.get("user_id")).longValue());
    }

    /** What the page shows: the step and the customer's first name (nothing else about the account). */
    public Map<String, Object> describe(String token) {
        Request r = open(token);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("purpose", r.purpose());
        String name = r.userId() == null ? null : users.findById(r.userId()).map(User::getFullName).orElse(null);
        out.put("firstName", name == null || name.isBlank() ? null : name.trim().split("\\s+")[0]);
        return out;
    }

    /**
     * Checks or sets the PIN for the step, then marks the link used. Returns the WhatsApp session to carry on.
     * LOGIN: pin. REGISTER: pin and confirm. CHANGE: pin (current), newPin and confirm.
     */
    public Request submit(String token, String pin, String newPin, String confirm) {
        Request r = open(token);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        switch (r.purpose()) {
            case LOGIN -> {
                User u = users.findById(r.userId()).orElseThrow(() -> new IllegalArgumentException("This link is not valid."));
                User v = pins.verify(u.getPhone(), pin);
                jdbc.update("INSERT INTO ussd_state (session_id, channel, msisdn, path, wallet_user_id, created_at, updated_at) VALUES (?, 'WHATSAPP', ?, '', ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE wallet_user_id = VALUES(wallet_user_id), updated_at = VALUES(updated_at)", r.session(), r.waId(), v.getId(), now, now);
            }
            case REGISTER -> {
                requireNew(pin, confirm);
                secret(r.session(), "register_pin", encoder.encode(pin), now);
            }
            case CHANGE -> {
                User u = users.findById(r.userId()).orElseThrow(() -> new IllegalArgumentException("This link is not valid."));
                pins.verify(u.getPhone(), pin);
                requireNew(newPin, confirm);
                auth.setCustomerPin(u.getPhone(), newPin);
                secret(r.session(), "pin_changed", "1", now);
            }
            default -> throw new IllegalArgumentException("This link is not valid.");
        }
        int used = jdbc.update("UPDATE channel_pin_requests SET used_at = ? WHERE token_hash = ? AND used_at IS NULL", now, r.tokenHash());
        if (used == 0) throw new IllegalStateException("This link has already been used. Go back to WhatsApp.");
        return r;
    }

    private static void requireNew(String pin, String confirm) {
        if (pin == null || !pin.matches("\\d{4}")) throw new IllegalArgumentException("Your PIN must be exactly 4 digits.");
        if (!pin.equals(confirm)) throw new IllegalArgumentException("The PINs do not match.");
    }

    private void secret(String session, String name, String value, Timestamp now) {
        jdbc.update("INSERT INTO channel_session_secrets (session_id, name, value, created_at) VALUES (?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE value = VALUES(value)", session, name, value, now);
    }
}
