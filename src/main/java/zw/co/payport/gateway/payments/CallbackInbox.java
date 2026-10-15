package zw.co.payport.gateway.payments;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.entity.Transaction;
import zw.co.payport.gateway.repository.TransactionRepository;
import zw.co.payport.gateway.service.AdaptorRegistry;
import zw.co.payport.gateway.service.PaymentStatusVerifier;
import zw.co.payport.gateway.service.adaptor.PaymentProviderAdaptor;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService.CallbackOutcome;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;

/**
 * The callback inbox: every provider callback is stored before it is processed, then
 * processed, and the outcome recorded on the stored row.
 *
 *  - An identical callback (same provider, same body) is one row with a received count;
 *    once it has been applied it is not processed again.
 *  - Per-provider trust: providers listed in payport.payments.query-providers are never
 *    trusted on their content (the provider is asked through our authenticated query);
 *    the others must pass the adaptor's signature/structure validation.
 *  - Processing is idempotent and serialised per payment (orchestrator row lock), so
 *    duplicates, out-of-order callbacks and callbacks that race the submit response have
 *    exactly one effect. Contradictions are recorded as CONFLICT for a person to review.
 *
 * Never throws: the caller always acknowledges the provider (they retry on non-2xx, which
 * the inbox would absorb anyway).
 */
@Slf4j
@Service
public class CallbackInbox {

    public enum Status { RECEIVED, APPLIED, NO_CHANGE, CONFLICT, INVALID, UNKNOWN_REF, ERROR }

    public record Result(long id, Status status, String detail, boolean duplicate, String payportReference) {}

    private final JdbcTemplate jdbc;
    private final AdaptorRegistry adaptorRegistry;
    private final PaymentStatusVerifier verifier;
    private final TransactionOrchestratorService orchestrator;
    private final TransactionRepository transactionRepository;
    private final PaymentCheckStore checks;

    public CallbackInbox(JdbcTemplate jdbc, AdaptorRegistry adaptorRegistry, PaymentStatusVerifier verifier,
                         TransactionOrchestratorService orchestrator, TransactionRepository transactionRepository,
                         PaymentCheckStore checks) {
        this.jdbc = jdbc;
        this.adaptorRegistry = adaptorRegistry;
        this.verifier = verifier;
        this.orchestrator = orchestrator;
        this.transactionRepository = transactionRepository;
        this.checks = checks;
    }

    public Result receive(String provider, String rawBody, Map<String, String> headers, String remoteIp) {
        String body = rawBody == null ? "" : rawBody;
        String code = provider.toUpperCase();
        LocalDateTime now = LocalDateTime.now();
        long id;
        boolean duplicate;
        Status previous;
        try {
            Stored s = store(code, body, remoteIp, now);
            id = s.id;
            duplicate = s.duplicate;
            previous = s.status;
        } catch (Exception e) {
            // The inbox itself is unavailable (database down). Do not process an unrecorded callback;
            // the provider's retry or the resolver will pick the payment up.
            log.error("[INBOX] Could not store {} callback: {}", code, e.getMessage(), e);
            return new Result(0, Status.ERROR, "inbox unavailable: " + e.getMessage(), false, null);
        }

        if (duplicate && (previous == Status.APPLIED || previous == Status.INVALID)) {
            log.info("[INBOX] Duplicate {} callback #{} (already {}) -- not processed again", code, id, previous);
            return new Result(id, previous, "duplicate of an already " + previous + " callback", true, null);
        }

        Outcome o;
        try {
            o = process(code, body, headers);
        } catch (Exception e) {
            log.error("[INBOX] {} callback #{} processing error: {}", code, id, e.getMessage(), e);
            o = new Outcome(Status.ERROR, e.getClass().getSimpleName() + ": " + e.getMessage(), null, null);
        }
        finish(id, o, LocalDateTime.now());
        log.info("[INBOX] {} callback #{} ref={} claimed={} -> {} ({})", code, id, o.reference, o.claimed, o.status, o.detail);
        return new Result(id, o.status, o.detail, duplicate, o.reference);
    }

    private record Outcome(Status status, String detail, String reference, String claimed) {}

    private Outcome process(String provider, String body, Map<String, String> headers) throws Exception {
        if (!adaptorRegistry.hasPaymentAdaptor(provider)) {
            return new Outcome(Status.INVALID, "no adaptor for provider " + provider, null, null);
        }
        PaymentProviderAdaptor adaptor = adaptorRegistry.getPaymentAdaptor(provider);
        boolean queryVerified = verifier.requiresVerification(provider);

        if (!queryVerified) {
            PaymentProviderAdaptor.CallbackValidationResult v = adaptor.validateCallback(headers, body);
            if (v == null || !v.isValid()) {
                return new Outcome(Status.INVALID, "validation failed: " + (v == null ? "no result" : v.getFailureReason()), null, null);
            }
        }

        PaymentProviderAdaptor.CallbackEvent claimed;
        try {
            claimed = adaptor.parseCallback(body);
        } catch (Exception e) {
            return new Outcome(Status.INVALID, "unparseable: " + e.getMessage(), null, null);
        }
        if (claimed == null || claimed.getPayportReference() == null || claimed.getPayportReference().isBlank()) {
            return new Outcome(Status.INVALID, "no PayPort reference in callback", null, claimed == null ? null : claimed.getStatus());
        }
        String ref = claimed.getPayportReference();
        Transaction tx = transactionRepository.findByReference(ref).orElse(null);
        if (tx == null) {
            return new Outcome(Status.UNKNOWN_REF, "no such payment", ref, claimed.getStatus());
        }

        PaymentProviderAdaptor.CallbackEvent toApply = claimed;
        if (queryVerified) {
            PaymentStatusVerifier.Verified verified = verifier.verify(provider, tx);
            boolean claimedDefinite = "SUCCESS".equals(claimed.getStatus()) || "FAILED".equals(claimed.getStatus());
            if (claimedDefinite && verified.isDefinite() && !claimed.getStatus().equals(verified.status())) {
                log.error("[INBOX] CONTRADICTION for {}: callback says {}, {} status query says {} -- applying the query result; review",
                        ref, claimed.getStatus(), provider, verified.status());
            }
            if (verifier.isAmountMismatch(tx, verified)) {
                return new Outcome(Status.CONFLICT, "provider confirmed " + verified.amountCents() + " cents, expected "
                        + tx.getAmountCents(), ref, claimed.getStatus());
            }
            toApply = verifier.toEvent(tx, verified, provider + " callback");
            if (toApply == null) {
                checks.checkSoon(ref, LocalDateTime.now());
                return new Outcome(Status.NO_CHANGE, "provider query says " + verified.status() + " (" + verified.detail()
                        + "); left for the resolver", ref, claimed.getStatus());
            }
        }

        CallbackOutcome outcome = orchestrator.handlePaymentCallback(toApply);
        Status status = switch (outcome) {
            case APPLIED -> Status.APPLIED;
            case NO_CHANGE -> Status.NO_CHANGE;
            case CONFLICT -> Status.CONFLICT;
            case UNKNOWN_REF -> Status.UNKNOWN_REF;
        };
        return new Outcome(status, toApply.getStatus() + " -> " + outcome, ref, claimed.getStatus());
    }

    private record Stored(long id, boolean duplicate, Status status) {}

    private Stored store(String provider, String body, String remoteIp, LocalDateTime now) {
        String hash = sha256(body);
        jdbc.update("INSERT INTO payment_callbacks (provider_code, body_sha256, raw_body, remote_ip, status, "
                        + "received_count, first_received_at, last_received_at) VALUES (?, ?, ?, ?, 'RECEIVED', 1, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE received_count = received_count + 1, last_received_at = VALUES(last_received_at)",
                provider, hash, body, PaymentAttemptStore.truncate(remoteIp, 64), now, now);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, status, received_count FROM payment_callbacks WHERE provider_code = ? AND body_sha256 = ?", provider, hash);
        boolean duplicate = ((Number) row.get("received_count")).intValue() > 1;
        return new Stored(((Number) row.get("id")).longValue(), duplicate, Status.valueOf((String) row.get("status")));
    }

    private void finish(long id, Outcome o, LocalDateTime now) {
        try {
            // Concurrent deliveries of the same callback: one APPLIES, the others see NO_CHANGE; keep APPLIED.
            // MySQL evaluates SET left to right, so outcome_detail must be decided before status changes.
            jdbc.update("UPDATE payment_callbacks SET outcome_detail = IF(status = 'APPLIED', outcome_detail, ?), status = IF(status = 'APPLIED', 'APPLIED', ?), payport_reference = COALESCE(?, payport_reference), "
                            + "claimed_status = COALESCE(?, claimed_status), processed_at = ? WHERE id = ?",
                    PaymentAttemptStore.truncate(o.detail, 1000), o.status.name(), PaymentAttemptStore.truncate(o.reference, 100),
                    PaymentAttemptStore.truncate(o.claimed, 20), now, id);
        } catch (Exception e) {
            log.error("[INBOX] Could not record outcome {} for callback #{}: {}", o.status, id, e.getMessage());
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
