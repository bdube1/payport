package zw.co.payport.gateway.risk;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/** Working days for regulatory deadlines: Monday to Friday, excluding the configured public holidays. */
@Component
public class WorkingCalendar {

    private final ComplianceParameters params;

    public WorkingCalendar(ComplianceParameters params) {
        this.params = params;
    }

    Set<LocalDate> holidays(String country) {
        Set<LocalDate> out = new HashSet<>();
        try {
            for (JsonNode d : params.get("CALENDAR." + country + ".HOLIDAYS")) out.add(LocalDate.parse(d.asText()));
        } catch (IllegalStateException missing) {
            // no calendar configured for this country: weekends only
        }
        return out;
    }

    public boolean isWorkingDay(LocalDate d, String country) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays(country).contains(d);
    }

    /** The same time of day, n working days later (a start on a non-working day counts from the next working day). */
    public LocalDateTime addWorkingDays(LocalDateTime start, int n, String country) {
        Set<LocalDate> h = holidays(country);
        LocalDate d = start.toLocalDate();
        int added = 0;
        while (added < n) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !h.contains(d)) added++;
        }
        return d.atTime(start.toLocalTime());
    }
}
