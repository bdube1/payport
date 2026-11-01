package zw.co.payport.gateway.tap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.entity.CheckoutSession;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.entity.User;
import zw.co.payport.gateway.repository.CheckoutSessionRepository;
import zw.co.payport.gateway.repository.UserRepository;
import zw.co.payport.gateway.service.CheckoutSessionService;
import zw.co.payport.gateway.wallets.WalletPaymentService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Payport Tap: contactless payment from the Payport wallet, phone to phone or phone to POS, over NFC.
 *
 *  1. The customer opens Tap to pay and confirms with their PIN (or fingerprint, which unlocks the PIN on the phone).
 *  2. Payport gives the app a one-time token, valid 90 seconds, for one currency and up to the customer's tap limit.
 *     The app offers it over NFC (an NDEF record "payport-tap:<token>"); only a hash is stored here.
 *  3. The merchant's device (Payport merchant app/portal on an NFC phone, or a POS) reads it and charges an amount.
 *     The token is used exactly once (an atomic update), must not be expired, and the amount must be within the limit.
 *  4. The payment is an ordinary wallet payment to the merchant (ledger, limits, compliance rules all apply). Both
 *     phones see the result; the customer also gets the usual push.
 *
 * Relay and replay: a token works once, for 90 seconds, up to a limit the customer chose; a stolen token cannot pay
 * more or later. Merchants are authenticated; every tap is in the merchant's and the customer's history.
 */
@Service
public class TapToPay {

    static final long VALID_SECONDS = 90;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final WalletPaymentService payments;
    private final CheckoutSessionService checkout;
    private final CheckoutSessionRepository sessions;
    private final UserRepository users;
    private final long defaultLimitMinor;

    public TapToPay(JdbcTemplate jdbc, WalletPaymentService payments, CheckoutSessionService checkout, CheckoutSessionRepository sessions,
                    UserRepository users, @Value("${payport.tap.limit-minor:10000}") long defaultLimitMinor) {
        this.jdbc = jdbc;
        this.payments = payments;
        this.checkout = checkout;
        this.sessions = sessions;
        this.users = users;
        this.defaultLimitMinor = defaultLimitMinor;
    }

    static String hash(String token) {
        try {
            StringBuilder s = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))) s.append(String.format("%02x", b));
            return s.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Step 2: a one-time token for a customer who has just confirmed with their PIN. */
    public Map<String, Object> issue(User u, String currency, Long maxMinor) {
        String ccy = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        if (!ccy.matches("[A-Z]{3}")) throw new IllegalArgumentException("Choose the currency");
        long limit = maxMinor == null || maxMinor <= 0 ? defaultLimitMinor : Math.min(maxMinor, defaultLimitMinor);
        Integer recent = jdbc.queryForObject("SELECT COUNT(*) FROM tap_tokens WHERE user_id = ? AND created_at >= ?", Integer.class, u.getId(),
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(10)));
        if (recent != null && recent >= 20) throw new IllegalStateException("Too many tap attempts. Please wait a few minutes.");
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        LocalDateTime exp = LocalDateTime.now().plusSeconds(VALID_SECONDS);
        jdbc.update("INSERT INTO tap_tokens (token_hash, user_id, currency, max_minor, expires_at, created_at) VALUES (?,?,?,?,?,?)",
                hash(token), u.getId(), ccy, limit, Timestamp.valueOf(exp), Timestamp.valueOf(LocalDateTime.now()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("nfc", "payport-tap:" + token);
        out.put("currency", ccy);
        out.put("limitMinor", limit);
        out.put("expiresAt", exp.toString());
        out.put("validSeconds", VALID_SECONDS);
        return out;
    }

    /** The customer's app asks what happened to its token (paid, failed, or still waiting). */
    public Map<String, Object> status(User u, String token) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT t.*, m.business_name FROM tap_tokens t LEFT JOIN merchants m ON m.id = t.merchant_id "
                + "WHERE t.token_hash = ? AND t.user_id = ?", hash(token == null ? "" : token), u.getId());
        if (r.isEmpty()) throw new IllegalArgumentException("No such tap");
        Map<String, Object> t = r.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        boolean expired = t.get("used_at") == null && ((Timestamp) t.get("expires_at")).toLocalDateTime().isBefore(LocalDateTime.now());
        out.put("state", t.get("result") != null ? t.get("result") : t.get("used_at") != null ? "PROCESSING" : expired ? "EXPIRED" : "WAITING");
        out.put("merchant", t.get("business_name"));
        out.put("amountMinor", t.get("amount_minor"));
        out.put("currency", t.get("currency"));
        out.put("reference", t.get("reference"));
        out.put("message", t.get("message"));
        return out;
    }

    /** Step 3: the merchant charges a tapped token. */
    public Map<String, Object> charge(Merchant m, String nfcPayload, long amountMinor, String currency, String description, String ip) {
        if (!"ACTIVE".equals(m.getStatus())) throw new IllegalStateException("This merchant cannot take payments at the moment");
        if (amountMinor <= 0) throw new IllegalArgumentException("Enter an amount above zero");
        String token = nfcPayload == null ? "" : nfcPayload.trim();
        if (token.startsWith("payport-tap:")) token = token.substring("payport-tap:".length());
        if (!token.matches("[A-Za-z0-9_-]{20,64}")) throw new IllegalArgumentException("This is not a Payport tap");
        String ccy = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        // used exactly once, before it expires, in its currency, within its limit
        int claimed = jdbc.update("UPDATE tap_tokens SET used_at = ?, merchant_id = ?, amount_minor = ? WHERE token_hash = ? AND used_at IS NULL "
                + "AND expires_at > ? AND currency = ? AND max_minor >= ?", now, m.getId(), amountMinor, hash(token), now, ccy, amountMinor);
        if (claimed == 0) {
            List<Map<String, Object>> t = jdbc.queryForList("SELECT * FROM tap_tokens WHERE token_hash = ?", hash(token));
            if (t.isEmpty()) throw new IllegalArgumentException("This is not a Payport tap");
            Map<String, Object> x = t.get(0);
            if (x.get("used_at") != null) throw new IllegalStateException("This tap has already been used. Ask the customer to tap again.");
            if (((Timestamp) x.get("expires_at")).before(now)) throw new IllegalStateException("This tap has expired. Ask the customer to tap again.");
            if (!ccy.equals(x.get("currency"))) throw new IllegalArgumentException("The customer's tap is for " + x.get("currency") + ", not " + ccy);
            throw new IllegalArgumentException("The amount is above the customer's tap limit. Ask them to pay with Pay merchant instead.");
        }
        Map<String, Object> t = jdbc.queryForMap("SELECT * FROM tap_tokens WHERE token_hash = ?", hash(token));
        User u = users.findById(((Number) t.get("user_id")).longValue()).orElseThrow(() -> new IllegalStateException("The customer was not found"));
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            CheckoutSession s = checkout.createSession(m, null, new CheckoutSessionService.CreateSessionRequest(null, amountMinor, ccy,
                    description == null || description.isBlank() ? "Tap payment to " + m.getBusinessName() : description.trim(),
                    u.getPhone(), null, null, null, null));
            s.setChannel(zw.co.payport.gateway.rules.PartyRules.channel("APP"));
            s = sessions.save(s);
            Map<String, Object> paid = payments.payCheckoutAs(u, s.getId(), ip, "APP");
            String status = String.valueOf(paid.getOrDefault("status", ""));
            boolean ok = "COMPLETED".equals(status) || "PAYMENT_CONFIRMED".equals(status) || "PAID".equals(status);
            String ref = paid.get("reference") == null ? null : String.valueOf(paid.get("reference"));
            String msg = paid.get("message") == null ? null : String.valueOf(paid.get("message"));
            jdbc.update("UPDATE tap_tokens SET reference = ?, result = ?, message = ? WHERE token_hash = ?", ref, ok ? "PAID" : "FAILED",
                    msg == null ? null : msg.substring(0, Math.min(300, msg.length())), hash(token));
            // the merchant sees the result and the reference, never the customer's balance
            out.put("reference", ref);
            out.put("message", msg);
            out.put("result", ok ? "PAID" : "FAILED");
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "The payment did not go through" : e.getMessage();
            jdbc.update("UPDATE tap_tokens SET result = 'FAILED', message = ? WHERE token_hash = ?", msg.substring(0, Math.min(300, msg.length())), hash(token));
            out.put("result", "FAILED");
            out.put("message", msg);
        }
        out.put("customer", mask(u.getPhone()));
        out.put("amountMinor", amountMinor);
        out.put("currency", ccy);
        return out;
    }

    static String mask(String phone) {
        String d = phone == null ? "" : phone.replaceAll("\\D", "");
        return d.length() < 6 ? "***" : "*** " + d.substring(d.length() - 3);
    }
}
