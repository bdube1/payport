package zw.co.payport.gateway.observability;

import io.micrometer.core.instrument.Metrics;

/**
 * Security events for the Operations Control Centre (payport_security_events_total{event, who}): counted where they
 * happen, never with a phone number, email or token attached.
 */
public final class SecurityEvents {

    private SecurityEvents() {}

    public static final String RATE_LIMITED = "rate_limited", LOGIN_FAILED = "login_failed", ACCOUNT_LOCKED = "account_locked",
            PIN_REJECTED = "pin_rejected", PERMISSION_DENIED = "permission_denied", SIGNATURE_INVALID = "webhook_signature_invalid";

    public static void count(String event, String who) {
        try {
            Metrics.counter("payport.security.events", "event", event, "who", who == null ? "unknown" : who).increment();
        } catch (RuntimeException ignored) {
            // metrics never break a request
        }
    }

    public static void duplicateRequest() {
        try {
            Metrics.counter("payport.duplicate.requests").increment();
        } catch (RuntimeException ignored) {
            // metrics never break a request
        }
    }
}
