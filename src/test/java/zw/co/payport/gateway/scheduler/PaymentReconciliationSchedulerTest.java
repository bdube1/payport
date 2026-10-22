package zw.co.payport.gateway.scheduler;

import org.junit.jupiter.api.Test;
import zw.co.payport.gateway.scheduler.PaymentReconciliationScheduler.Decision;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** R6: unknown outcomes are polled with back-off and never failed by the clock. */
class PaymentReconciliationSchedulerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 12, 0);

    @Test
    void waitsForGracePeriod() {
        assertEquals(Decision.WAIT, PaymentReconciliationScheduler.decide(NOW.minusSeconds(5), null, NOW));
    }

    @Test
    void checksEveryRunInTheFirst30Minutes() {
        assertEquals(Decision.CHECK, PaymentReconciliationScheduler.decide(NOW.minusMinutes(5), NOW.minusSeconds(10), NOW));
        assertEquals(Decision.CHECK, PaymentReconciliationScheduler.decide(NOW.minusMinutes(29), NOW.minusSeconds(10), NOW));
    }

    @Test
    void backsOffToTenMinutesAfterThat() {
        assertEquals(Decision.WAIT, PaymentReconciliationScheduler.decide(NOW.minusHours(2), NOW.minusMinutes(3), NOW));
        assertEquals(Decision.CHECK, PaymentReconciliationScheduler.decide(NOW.minusHours(2), NOW.minusMinutes(10), NOW));
        assertEquals(Decision.CHECK, PaymentReconciliationScheduler.decide(NOW.minusHours(2), null, NOW), "first check after a restart");
    }

    @Test
    void stopsLateDetectionAfter24HoursByDefault() {
        assertEquals(Decision.STOP, PaymentReconciliationScheduler.decide(NOW.minusHours(24), NOW.minusMinutes(30), NOW));
        assertEquals(Decision.CHECK, PaymentReconciliationScheduler.decide(NOW.minusHours(23), NOW.minusMinutes(30), NOW));
    }
}
