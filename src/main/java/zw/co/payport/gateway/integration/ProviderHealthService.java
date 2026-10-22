package zw.co.payport.gateway.integration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Health of every provider PayPort calls, and the two ways traffic to one is stopped:
 *
 *  - automatically: a circuit breaker per provider (payport.integrations.breaker.failure-threshold
 *    consecutive transport failures, default 5, open for payport.integrations.breaker.open-seconds,
 *    default 60, then one trial request);
 *  - manually: operations suspend a provider (kill switch) with a reason; stored in the database
 *    so every instance obeys it and it survives restarts. Resuming is a separate, audited action.
 *
 * Both refuse BEFORE anything is sent, so they can never create an unknown outcome.
 * Breaker state is per instance (each instance protects itself); suspensions are shared.
 */
@Slf4j
@Service
public class ProviderHealthService {

    private final JdbcTemplate jdbc;
    private final int failureThreshold;
    private final Duration openDuration;
    private final Clock clock;
    private final boolean breakersEnabled;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final Map<String, LatencyStats> latency = new ConcurrentHashMap<>();
    private volatile Map<String, Map<String, Object>> suspensions = Map.of();
    private volatile long suspensionsLoadedAt;

    public ProviderHealthService(JdbcTemplate jdbc,
                                 @Value("${payport.integrations.breaker.failure-threshold:5}") int failureThreshold,
                                 @Value("${payport.integrations.breaker.open-seconds:60}") int openSeconds,
                                 @Value("${payport.integrations.breaker.enabled:true}") boolean breakersEnabled) {
        this.jdbc = jdbc;
        this.breakersEnabled = breakersEnabled;
        this.failureThreshold = failureThreshold;
        this.openDuration = Duration.ofSeconds(openSeconds);
        this.clock = Clock.systemUTC();
    }

    private CircuitBreaker breaker(String code) {
        return breakers.computeIfAbsent(code.toUpperCase(), c -> new CircuitBreaker(failureThreshold, openDuration, clock));
    }

    /**
     * Call before sending anything to the provider. Throws ProviderUnavailableException (nothing
     * sent) when the provider is suspended or its breaker is open.
     */
    public void acquire(String providerCode, String displayName) {
        String code = providerCode.toUpperCase();
        Map<String, Object> s = suspension(code);
        if (s != null) {
            throw new ProviderUnavailableException(code, displayName + " is temporarily unavailable. No money was taken. Please try again later or use another option.");
        }
        if (breakersEnabled && !breaker(code).tryAcquire()) {
            log.warn("[INTEGRATIONS] {} refused: circuit open", code);
            throw new ProviderUnavailableException(code, displayName + " is not responding right now. No money was taken. Please try again in a few minutes or use another option.");
        }
    }

    /** The provider answered (accepted or declined): it is up. */
    public void success(String providerCode, long millis) {
        breaker(providerCode).onSuccess();
        latency.computeIfAbsent(providerCode.toUpperCase(), c -> new LatencyStats()).record(millis);
    }

    /** Timeout, refused connection, 5xx or unknown outcome. */
    public void failure(String providerCode, long millis, String why) {
        CircuitBreaker b = breaker(providerCode);
        b.onFailure(why);
        latency.computeIfAbsent(providerCode.toUpperCase(), c -> new LatencyStats()).record(millis);
        if (breakersEnabled && b.snapshot().state() == CircuitBreaker.State.OPEN) {
            log.error("[INTEGRATIONS] {} circuit OPEN after {} consecutive failures (last: {})", providerCode, b.snapshot().consecutiveFailures(), why);
        }
    }

    // ─── manual suspension ────────────────────────────────────────────────────

    public void suspend(String providerCode, String reason, String by) {
        if (reason == null || reason.isBlank()) reason = "Switched off";
        Integer open = jdbc.queryForObject("SELECT COUNT(*) FROM provider_suspensions WHERE provider_code = ? AND resumed_at IS NULL",
                Integer.class, providerCode.toUpperCase());
        if (open != null && open > 0) return;   // already off
        jdbc.update("INSERT INTO provider_suspensions (provider_code, reason, suspended_by, suspended_at) VALUES (?, ?, ?, ?)",
                providerCode.toUpperCase(), reason.trim(), by, Timestamp.valueOf(LocalDateTime.now()));
        suspensionsLoadedAt = 0;
        log.warn("[INTEGRATIONS] {} SUSPENDED by {}: {}", providerCode, by, reason);
    }

    public void resume(String providerCode, String note, String by) {
        int n = jdbc.update("UPDATE provider_suspensions SET resumed_by = ?, resumed_at = ?, resume_note = ? WHERE provider_code = ? AND resumed_at IS NULL",
                by, Timestamp.valueOf(LocalDateTime.now()), note, providerCode.toUpperCase());
        if (n == 0) return;   // already on
        suspensionsLoadedAt = 0;
        log.warn("[INTEGRATIONS] {} RESUMED by {}", providerCode, by);
    }

    private Map<String, Object> suspension(String code) {
        long now = System.currentTimeMillis();
        if (now - suspensionsLoadedAt > 5_000) {        // shared across instances; at most 5 s stale
            Map<String, Map<String, Object>> m = new LinkedHashMap<>();
            for (Map<String, Object> r : jdbc.queryForList("SELECT provider_code, reason, suspended_by, suspended_at FROM provider_suspensions WHERE resumed_at IS NULL")) {
                m.put((String) r.get("provider_code"), r);
            }
            suspensions = m;
            suspensionsLoadedAt = now;
        }
        return suspensions.get(code);
    }

    /** Health for the console: breaker, counts, latency, suspension. */
    public Map<String, Object> health(String providerCode) {
        String code = providerCode.toUpperCase();
        Map<String, Object> m = new LinkedHashMap<>();
        CircuitBreaker.Snapshot s = breaker(code).snapshot();
        Map<String, Object> susp = suspension(code);
        m.put("status", susp != null ? "SUSPENDED" : s.state() == CircuitBreaker.State.CLOSED ? "UP"
                : s.state() == CircuitBreaker.State.OPEN ? "DOWN" : "RECOVERING");
        m.put("circuit", s.state().name());
        m.put("consecutiveFailures", s.consecutiveFailures());
        m.put("successes", s.successes());
        m.put("failures", s.failures());
        m.put("refused", s.refused());
        m.put("lastSuccessAt", s.lastSuccessAt());
        m.put("lastFailureAt", s.lastFailureAt());
        m.put("lastFailure", s.lastFailure());
        m.put("retryAfter", s.retryAfter());
        LatencyStats l = latency.get(code);
        if (l != null) m.putAll(l.summary());
        if (susp != null) m.put("suspension", susp);
        return m;
    }

    public List<Map<String, Object>> suspensionHistory(String providerCode) {
        return jdbc.queryForList("SELECT * FROM provider_suspensions WHERE provider_code = ? ORDER BY id DESC LIMIT 50", providerCode.toUpperCase());
    }

    /** Rolling latency over the last 200 calls. */
    static final class LatencyStats {
        private final long[] ring = new long[200];
        private int count, next;

        synchronized void record(long ms) {
            ring[next] = ms;
            next = (next + 1) % ring.length;
            count = Math.min(count + 1, ring.length);
        }

        synchronized Map<String, Object> summary() {
            long[] v = java.util.Arrays.copyOf(count == ring.length ? ring : java.util.Arrays.copyOf(ring, count), count);
            java.util.Arrays.sort(v);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("latencySamples", count);
            if (count > 0) {
                m.put("latencyP50Ms", v[(int) Math.floor(0.50 * (count - 1))]);
                m.put("latencyP95Ms", v[(int) Math.floor(0.95 * (count - 1))]);
            }
            return m;
        }
    }
}
