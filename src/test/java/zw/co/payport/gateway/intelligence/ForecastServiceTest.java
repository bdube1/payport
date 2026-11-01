package zw.co.payport.gateway.intelligence;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** Forecasts follow the level and the weekly pattern of the history, and the busy case is above the expected case. */
class ForecastServiceTest {

    @Test
    void weeklyPatternAndLevelAreFollowed() {
        LocalDate last = LocalDate.of(2026, 11, 1);       // a Sunday
        double[] daily = new double[56];
        for (int i = 0; i < 56; i++) {
            DayOfWeek d = last.minusDays(55 - i).getDayOfWeek();
            daily[i] = d == DayOfWeek.SATURDAY ? 3000 : 1000;    // Saturdays three times busier
        }
        ForecastService.Forecast f = ForecastService.forecast(daily, last, 7);
        assertTrue(f.enoughHistory());
        // next Saturday is day 6 after a Sunday
        assertEquals(DayOfWeek.SATURDAY, last.plusDays(6).getDayOfWeek());
        assertEquals(3000, f.expected()[5], 150, "Saturday");
        assertEquals(1000, f.expected()[0], 80, "Monday");
        for (int h = 0; h < 7; h++) assertTrue(f.p90()[h] >= f.expected()[h]);
    }

    @Test
    void growthIsPickedUpAndShortHistoryIsSaid() {
        LocalDate last = LocalDate.of(2026, 11, 1);
        double[] daily = new double[56];
        for (int i = 0; i < 56; i++) daily[i] = i < 42 ? 500 : 1500;   // the last two weeks tripled
        assertTrue(ForecastService.forecast(daily, last, 7).expected()[0] > 1200, "recent days weigh more");
        assertFalse(ForecastService.forecast(new double[]{100, 120, 90}, last, 7).enoughHistory());
        assertEquals(0, ForecastService.forecast(new double[0], last, 7).expected()[0]);
    }
}
