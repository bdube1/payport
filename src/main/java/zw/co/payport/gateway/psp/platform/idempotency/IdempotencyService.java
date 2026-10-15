package zw.co.payport.gateway.psp.platform.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.ledger.domain.ChainHasher;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Stripe-style Idempotency-Key handling for every client-initiated write
 * (checkout creation, refunds, payouts, wallet transfers, partner API).
 *
 *  - same key + same request  -> the stored response is replayed, nothing runs twice
 *  - same key + different body -> 422 IDEMPOTENCY_KEY_REUSED
 *  - same key while first call still running -> 409 IDEMPOTENCY_IN_PROGRESS (client retries later)
 *  - the operation fails with a 5xx/unexpected error -> key released so the client can retry
 *  - the operation fails with a 4xx business error -> that error is stored and replayed (it is a final answer)
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final int TTL_HOURS = 48;

    public record StoredResponse(int status, String body, String resourceRef) {}

    private final IdempotencyRecordRepository repository;
    private final TransactionTemplate newTx;

    public IdempotencyService(IdempotencyRecordRepository repository, PlatformTransactionManager txManager) {
        this.repository = repository;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public static String hashRequest(String method, String path, String body) {
        return ChainHasher.sha256(method + "\n" + path + "\n" + (body == null ? "" : body));
    }

    /**
     * @param scope   who the key belongs to, e.g. "merchant:42" or "partner:acme" (keys are only unique per client)
     * @param key     the client's Idempotency-Key header; when null the operation simply runs
     * @param work    the operation; must return the response to store
     */
    public StoredResponse execute(String scope, String key, String requestHash, Supplier<StoredResponse> work) {
        if (key == null || key.isBlank()) {
            return work.get();
        }
        if (key.length() > 120) {
            throw PspException.badRequest("IDEMPOTENCY_KEY_INVALID", "Idempotency-Key must be at most 120 characters");
        }

        Optional<StoredResponse> replay = reserve(scope, key, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        StoredResponse result;
        try {
            result = work.get();
        } catch (PspException e) {
            if (e.httpStatus() >= 400 && e.httpStatus() < 500) {
                complete(scope, key, new StoredResponse(e.httpStatus(),
                        "{\"success\":false,\"error\":{\"code\":\"" + e.code() + "\",\"message\":" + jsonString(e.getMessage()) + "}}", null));
            } else {
                release(scope, key);
            }
            throw e;
        } catch (RuntimeException e) {
            release(scope, key);
            throw e;
        }
        complete(scope, key, result);
        return result;
    }

    private Optional<StoredResponse> reserve(String scope, String key, String requestHash) {
        try {
            newTx.executeWithoutResult(s -> {
                IdempotencyRecord r = new IdempotencyRecord();
                r.setScope(scope);
                r.setIdemKey(key);
                r.setRequestHash(requestHash);
                r.setStatus("IN_PROGRESS");
                r.setExpiresAt(LocalDateTime.now().plusHours(TTL_HOURS));
                repository.saveAndFlush(r);
            });
            return Optional.empty();
        } catch (DataIntegrityViolationException dup) {
            IdempotencyRecord existing = repository.findByScopeAndIdemKey(scope, key)
                    .orElseThrow(() -> PspException.conflict("IDEMPOTENCY_IN_PROGRESS", "Request with this Idempotency-Key is still being processed"));
            if (!existing.getRequestHash().equals(requestHash)) {
                throw PspException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used with a different request");
            }
            if (!"COMPLETED".equals(existing.getStatus())) {
                throw PspException.conflict("IDEMPOTENCY_IN_PROGRESS", "Request with this Idempotency-Key is still being processed");
            }
            log.info("[IDEMPOTENCY] Replaying stored response for {} / {}", scope, key);
            return Optional.of(new StoredResponse(existing.getResponseStatus(), existing.getResponseBody(), existing.getResourceRef()));
        }
    }

    private void complete(String scope, String key, StoredResponse resp) {
        newTx.executeWithoutResult(s -> repository.findByScopeAndIdemKey(scope, key).ifPresent(r -> {
            r.setStatus("COMPLETED");
            r.setResponseStatus(resp.status());
            r.setResponseBody(resp.body());
            r.setResourceRef(resp.resourceRef());
            repository.save(r);
        }));
    }

    private void release(String scope, String key) {
        newTx.executeWithoutResult(s -> repository.findByScopeAndIdemKey(scope, key).ifPresent(repository::delete));
    }

    @Scheduled(cron = "0 17 3 * * *", zone = "Africa/Harare")
    public void purgeExpired() {
        Integer n = newTx.execute(s -> repository.deleteExpired(LocalDateTime.now()));
        if (n != null && n > 0) log.info("[IDEMPOTENCY] Purged {} expired keys", n);
    }

    static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.append('"').toString();
    }
}
