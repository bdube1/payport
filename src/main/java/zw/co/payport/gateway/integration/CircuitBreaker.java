package zw.co.payport.gateway.integration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * One provider's circuit breaker. Only transport failures count (timeouts, refused connections,
 * 5xx, unknown outcomes); a provider that answers "declined" is healthy.
 *
 *   CLOSED     requests flow; failureThreshold consecutive failures open it
 *   OPEN       new requests are refused before anything is sent, for openDuration
 *   HALF_OPEN  one trial request is let through; success closes, failure re-opens
 *
 * Refusing happens BEFORE a request is sent, so a refusal never leaves a payment or delivery in
 * an unknown state. Thread-safe.
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final Duration openDuration;
    private final Clock clock;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openedAt;
    private boolean trialInFlight;
    private Instant trialStartedAt;
    private long successes, failures, refused;
    private Instant lastSuccessAt, lastFailureAt;
    private String lastFailure;

    public CircuitBreaker(int failureThreshold, Duration openDuration, Clock clock) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDuration = openDuration;
        this.clock = clock;
    }

    /** True if a request may be sent now. In HALF_OPEN only one trial is allowed at a time. */
    public synchronized boolean tryAcquire() {
        if (state == State.OPEN && !clock.instant().isBefore(openedAt.plus(openDuration))) {
            state = State.HALF_OPEN;
            trialInFlight = false;
        }
        switch (state) {
            case CLOSED:
                return true;
            case HALF_OPEN:
                // A trial that never reported back (caller crashed) expires after openDuration.
                if (trialInFlight && clock.instant().isBefore(trialStartedAt.plus(openDuration))) { refused++; return false; }
                trialInFlight = true;
                trialStartedAt = clock.instant();
                return true;
            default:
                refused++;
                return false;
        }
    }

    public synchronized void onSuccess() {
        successes++;
        lastSuccessAt = clock.instant();
        consecutiveFailures = 0;
        trialInFlight = false;
        state = State.CLOSED;
    }

    public synchronized void onFailure(String why) {
        failures++;
        lastFailureAt = clock.instant();
        lastFailure = why;
        trialInFlight = false;
        consecutiveFailures++;
        if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openedAt = clock.instant();
        }
    }

    /** A request was acquired but ended without telling us anything about the provider. */
    public synchronized void release() {
        trialInFlight = false;
    }

    public synchronized Snapshot snapshot() {
        // Report OPEN that has expired as HALF_OPEN without mutating.
        State s = state == State.OPEN && !clock.instant().isBefore(openedAt.plus(openDuration)) ? State.HALF_OPEN : state;
        return new Snapshot(s, consecutiveFailures, successes, failures, refused, lastSuccessAt, lastFailureAt, lastFailure,
                state == State.OPEN ? openedAt.plus(openDuration) : null);
    }

    public record Snapshot(State state, int consecutiveFailures, long successes, long failures, long refused,
                           Instant lastSuccessAt, Instant lastFailureAt, String lastFailure, Instant retryAfter) {}
}
