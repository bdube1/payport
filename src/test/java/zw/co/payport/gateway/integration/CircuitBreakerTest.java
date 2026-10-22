package zw.co.payport.gateway.integration;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-16T10:00:00Z");
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    @Test
    void opensAfterConsecutiveTransportFailures_refusesWhileOpen_thenOneTrialDecides() {
        MutableClock clock = new MutableClock();
        CircuitBreaker b = new CircuitBreaker(3, Duration.ofSeconds(60), clock);

        for (int i = 0; i < 2; i++) { assertTrue(b.tryAcquire()); b.onFailure("timeout"); }
        assertTrue(b.tryAcquire());
        b.onSuccess();                                   // an answer (even a decline) resets the count
        for (int i = 0; i < 3; i++) { assertTrue(b.tryAcquire()); b.onFailure("timeout"); }
        assertEquals(CircuitBreaker.State.OPEN, b.snapshot().state());
        assertFalse(b.tryAcquire(), "open: refused before anything is sent");
        assertEquals(1, b.snapshot().refused());

        clock.advance(Duration.ofSeconds(61));
        assertTrue(b.tryAcquire(), "half-open: one trial");
        assertFalse(b.tryAcquire(), "only one trial at a time");
        b.onFailure("still down");
        assertEquals(CircuitBreaker.State.OPEN, b.snapshot().state(), "a failed trial re-opens");

        clock.advance(Duration.ofSeconds(61));
        assertTrue(b.tryAcquire());
        b.onSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, b.snapshot().state());
        assertTrue(b.tryAcquire());
    }

    @Test
    void aTrialThatNeverReportsBackExpires() {
        MutableClock clock = new MutableClock();
        CircuitBreaker b = new CircuitBreaker(1, Duration.ofSeconds(30), clock);
        b.tryAcquire();
        b.onFailure("down");
        clock.advance(Duration.ofSeconds(31));
        assertTrue(b.tryAcquire());                      // trial taken, caller crashes
        assertFalse(b.tryAcquire());
        clock.advance(Duration.ofSeconds(31));
        assertTrue(b.tryAcquire(), "the lost trial does not block the provider forever");
    }

    @Test
    void registerIsWellFormed_andDeclaresWhatTheCodeRelies() {
        var all = IntegrationRegister.load();
        assertTrue(all.keySet().containsAll(java.util.List.of("ECOCASH", "ONEMONEY", "OMARI", "ZIMSWITCH", "ECONET", "NETONE", "TELECEL",
                "ZESA", "NYARADZO", "MOONLIGHT", "ZOL", "BULAWAYO", "MERCHANT")), all.keySet().toString());
        for (var p : all.values()) {
            assertFalse(p.name().isBlank(), p.code());
            assertFalse(p.currencies().isEmpty(), p.code());
            if ("BILLER".equals(p.kind())) {
                assertTrue(p.operations().containsAll(java.util.List.of("lookup", "deliver", "resend_lookup")), p.code() + " biller contract");
            }
            if ("PAYMENT".equals(p.kind())) {
                assertTrue(p.operations().contains("submit") && p.operations().contains("status_query"), p.code() + " payment contract");
                assertNotEquals("NONE", p.callbackAuth(), p.code() + ": a payment provider's confirmations must be authenticated");
            }
            assertNotEquals(IntegrationRegister.Verification.SANDBOX_VERIFIED, p.verification(),
                    p.code() + ": nothing is sandbox-verified yet (no sandbox access); raise only with recorded evidence");
        }
    }
}
