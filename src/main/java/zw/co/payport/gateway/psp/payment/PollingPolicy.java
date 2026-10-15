package zw.co.payport.gateway.psp.payment;

import java.time.Duration;

/**
 * How hard to chase a payment whose outcome is unknown. Pure function of
 * the payment's age so it is easy to reason about and test.
 *
 *   age < 5 min    -> ask every 15 s   (customer is probably still on the USSD prompt)
 *   age < 30 min   -> every 1 min
 *   age < 24 h     -> every 10 min     (an investigation case is opened at 30 min)
 *   age < 72 h     -> every 1 h
 *   older          -> stop polling; only provider statements or a human (with evidence) can resolve it
 *
 * At no point does age alone turn a payment into FAILED.
 */
public final class PollingPolicy {

    public static final Duration ESCALATE_AFTER = Duration.ofMinutes(30);
    public static final Duration STOP_AUTO_AFTER = Duration.ofHours(72);
    public static final Duration MARK_UNKNOWN_AFTER = Duration.ofMinutes(3);

    private PollingPolicy() {}

    public static Duration nextDelay(Duration age) {
        if (age.compareTo(Duration.ofMinutes(5)) < 0) return Duration.ofSeconds(15);
        if (age.compareTo(Duration.ofMinutes(30)) < 0) return Duration.ofMinutes(1);
        if (age.compareTo(Duration.ofHours(24)) < 0) return Duration.ofMinutes(10);
        return Duration.ofHours(1);
    }

    public static boolean shouldEscalate(Duration age) {
        return age.compareTo(ESCALATE_AFTER) >= 0;
    }

    public static boolean shouldStopAutoPolling(Duration age) {
        return age.compareTo(STOP_AUTO_AFTER) >= 0;
    }

    /** When a still-PENDING payment should be shown as "outcome unknown" rather than "waiting for customer". */
    public static boolean shouldMarkUnknown(Duration age) {
        return age.compareTo(MARK_UNKNOWN_AFTER) >= 0;
    }

    /** Callback-inbox retry back-off: 30s, 1m, 2m, 4m ... capped at 30 min. */
    public static Duration inboxRetryDelay(int attempts) {
        long seconds = Math.min(1800, 30L * (1L << Math.min(attempts, 10)));
        return Duration.ofSeconds(seconds);
    }
}
