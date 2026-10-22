package zw.co.payport.gateway.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.payments.TransactionOutcomeService;
import zw.co.payport.gateway.repository.TransactionRepository;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Push notifications to the customer's phone (Expo push service, which the mobile app uses).
 *
 * Every final outcome of a payment the customer owns is pushed: SUCCEEDED, FAILED, TIMED_OUT,
 * NO_RESPONSE, REFUNDED, with the same wording the app and USSD show (the provider's own message
 * on a failure). SMS stays success-only (owner decision); push is free.
 *
 * Delivery: the status change writes an outbox row in the same transaction; a worker sends it
 * afterwards with retries (1, 5, 30 min). Tokens Expo reports as DeviceNotRegistered are disabled.
 */
@Slf4j
@Service
public class PushNotificationService {

    /** Statuses whose outcome is final for the customer. */
    static final Set<String> NOTIFY = Set.of("COMPLETED", "PAYMENT_FAILED", "PAYMENT_TIMEOUT", "FULFILMENT_FAILED", "REFUNDED");
    private static final int[] RETRY_MINUTES = {1, 5, 30};

    private final JdbcTemplate jdbc;
    private final TransactionRepository transactions;
    private final TransactionOutcomeService outcomes;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String expoUrl;
    private final String expoAccessToken;
    private final boolean enabled;

    public PushNotificationService(JdbcTemplate jdbc, TransactionRepository transactions, TransactionOutcomeService outcomes,
                                   @Value("${payport.push.expo-url:https://exp.host/--/api/v2/push/send}") String expoUrl,
                                   @Value("${payport.push.expo-access-token:}") String expoAccessToken,
                                   @Value("${payport.push.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.outcomes = outcomes;
        this.expoUrl = expoUrl;
        this.expoAccessToken = expoAccessToken;
        this.enabled = enabled;
    }

    // ─── tokens ───────────────────────────────────────────────────────────────

    public static boolean isExpoToken(String token) {
        return token != null && token.matches("(ExponentPushToken|ExpoPushToken)\\[[A-Za-z0-9_-]{10,100}\\]");
    }

    /** Registers (or re-assigns) a device token to the logged-in customer. One token = one device. */
    public void register(long userId, String token, String platform) {
        if (!isExpoToken(token)) throw new IllegalArgumentException("Not a valid push token");
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        String p = platform == null ? null : platform.toLowerCase().replaceAll("[^a-z]", "");
        if (p != null && p.length() > 10) p = p.substring(0, 10);
        jdbc.update("INSERT INTO push_tokens (user_id, token, platform, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE user_id = VALUES(user_id), platform = COALESCE(VALUES(platform), platform), last_seen_at = VALUES(last_seen_at), "
                + "disabled_at = NULL, disabled_reason = NULL", userId, token, p, now, now);
    }

    /** Logout or notifications turned off on the device. */
    public void unregister(long userId, String token) {
        jdbc.update("UPDATE push_tokens SET disabled_at = ?, disabled_reason = 'UNREGISTERED' WHERE user_id = ? AND token = ? AND disabled_at IS NULL",
                Timestamp.valueOf(LocalDateTime.now()), userId, token);
    }

    // ─── outbox ───────────────────────────────────────────────────────────────

    /** Runs inside the status change's transaction: the row commits or rolls back with it. */
    @EventListener
    public void onStatusChanged(TransactionOrchestratorService.TransactionStatusChangedEvent e) {
        if (!NOTIFY.contains(e.newStatus())) return;
        try {
            // The owner is the registered customer whose phone paid (the same rule as the app's history).
            List<Long> owner = jdbc.queryForList("SELECT COALESCE(t.user_id, u.id) FROM transactions t LEFT JOIN users u ON u.phone = t.customer_phone "
                    + "AND u.role = 'CUSTOMER' WHERE t.reference = ? AND COALESCE(t.user_id, u.id) IS NOT NULL", Long.class, e.transactionReference());
            if (owner.isEmpty()) return;     // guest payment: no device to notify
            Timestamp now = Timestamp.valueOf(LocalDateTime.now());
            jdbc.update("INSERT IGNORE INTO push_outbox (transaction_reference, status, user_id, state, next_attempt_at, created_at) VALUES (?, ?, ?, 'PENDING', ?, ?)",
                    e.transactionReference(), e.newStatus(), owner.get(0), now, now);
        } catch (RuntimeException ex) {
            // A notification must never break a payment.
            log.warn("[PUSH] Could not queue push for {}: {}", e.transactionReference(), ex.getMessage());
        }
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 20000)
    public void scheduledSend() {
        if (!enabled) return;
        try {
            sendDue(LocalDateTime.now());
        } catch (Exception e) {
            log.error("[PUSH] send run failed: {}", e.getMessage());
        }
    }

    /** Sends every due outbox row. Returns how many were sent. */
    public int sendDue(LocalDateTime now) {
        int sent = 0;
        List<Map<String, Object>> due = jdbc.queryForList("SELECT * FROM push_outbox WHERE state = 'PENDING' AND next_attempt_at <= ? ORDER BY id LIMIT 100",
                Timestamp.valueOf(now));
        for (Map<String, Object> row : due) {
            long id = ((Number) row.get("id")).longValue();
            // Claim: only one worker (or instance) sends a row.
            if (jdbc.update("UPDATE push_outbox SET attempts = attempts + 1, next_attempt_at = ? WHERE id = ? AND state = 'PENDING' AND attempts = ?",
                    Timestamp.valueOf(now.plusMinutes(10)), id, ((Number) row.get("attempts")).intValue()) == 0) continue;
            if (sendOne(row, now)) sent++;
        }
        return sent;
    }

    private boolean sendOne(Map<String, Object> row, LocalDateTime now) {
        long id = ((Number) row.get("id")).longValue();
        String ref = (String) row.get("transaction_reference");
        List<String> tokens = jdbc.queryForList("SELECT token FROM push_tokens WHERE user_id = ? AND disabled_at IS NULL", String.class, row.get("user_id"));
        var tx = transactions.findByReference(ref).orElse(null);
        if (tokens.isEmpty() || tx == null) {
            jdbc.update("UPDATE push_outbox SET state = 'SKIPPED', last_error = ? WHERE id = ?", tokens.isEmpty() ? "no device registered" : "no transaction", id);
            return false;
        }
        TransactionOutcomeService.Result r = outcomes.of(tx);
        Map<String, String> text = message(r, tx.getProductName(), tx.getAmountCents(), tx.getCurrency());
        try {
            List<Map<String, Object>> batch = new ArrayList<>();
            for (String t : tokens) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("to", t);
                m.put("title", text.get("title"));
                m.put("body", text.get("body"));
                m.put("sound", "default");
                m.put("data", Map.of("reference", ref, "outcome", r.outcome().name()));
                batch.add(m);
            }
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(expoUrl)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(batch)));
            if (expoAccessToken != null && !expoAccessToken.isBlank()) req.header("Authorization", "Bearer " + expoAccessToken);
            HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) throw new IllegalStateException("Expo HTTP " + resp.statusCode());
            JsonNode tickets = json.readTree(resp.body()).path("data");
            int ok = 0;
            for (int i = 0; i < tokens.size() && i < tickets.size(); i++) {
                JsonNode t = tickets.get(i);
                if ("ok".equals(t.path("status").asText())) ok++;
                else if ("DeviceNotRegistered".equals(t.path("details").path("error").asText())) {
                    jdbc.update("UPDATE push_tokens SET disabled_at = ?, disabled_reason = 'DeviceNotRegistered' WHERE token = ?",
                            Timestamp.valueOf(now), tokens.get(i));
                }
            }
            jdbc.update("UPDATE push_outbox SET state = ?, sent_at = ?, last_error = ? WHERE id = ?",
                    ok > 0 ? "SENT" : "FAILED", Timestamp.valueOf(now), ok > 0 ? null : "no device accepted the push", id);
            return ok > 0;
        } catch (Exception e) {
            int attempts = ((Number) row.get("attempts")).intValue() + 1;
            boolean giveUp = attempts > RETRY_MINUTES.length;
            jdbc.update("UPDATE push_outbox SET state = ?, next_attempt_at = ?, last_error = ? WHERE id = ?",
                    giveUp ? "FAILED" : "PENDING", Timestamp.valueOf(now.plusMinutes(giveUp ? 0 : RETRY_MINUTES[attempts - 1])),
                    String.valueOf(e.getMessage()).substring(0, Math.min(500, String.valueOf(e.getMessage()).length())), id);
            log.warn("[PUSH] {} for {} failed (attempt {}): {}", row.get("status"), ref, attempts, e.getMessage());
            return false;
        }
    }

    /** Title and body for an outcome: the same words the customer sees in the app. */
    static Map<String, String> message(TransactionOutcomeService.Result r, String product, Long amountMinor, String currency) {
        String what = (product == null ? "Your payment" : product) + (amountMinor == null ? "" : String.format(" %s %d.%02d", currency, amountMinor / 100, amountMinor % 100));
        String title;
        String body;
        switch (r.outcome()) {
            case SUCCEEDED -> { title = "Payment successful"; body = what + " was successful. Ref " + r.reference(); }
            case FAILED -> { title = "Payment failed"; body = (r.message() != null ? r.message() : what + " did not go through.") + " Ref " + r.reference(); }
            case TIMED_OUT -> { title = "Payment timed out"; body = (r.message() != null ? r.message() : "The transaction timed out.") + " Ref " + r.reference(); }
            case NO_RESPONSE -> { title = "No response from provider"; body = (r.message() != null ? r.message() : "No response from the provider.") + " Ref " + r.reference(); }
            case REFUNDED -> { title = "Payment refunded"; body = what + " has been refunded. Ref " + r.reference(); }
            default -> { title = "Payment update"; body = (r.message() != null ? r.message() : what + " is being processed.") + " Ref " + r.reference(); }
        }
        return Map.of("title", title, "body", body);
    }
}
