package zw.co.payport.commerce;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Reads the PSP's event stream in order and keeps the cursor here. Each event is handled once (processed_events);
 * a replay or a repeated delivery changes nothing. For checkout and refund events the PSP's current status of that
 * checkout is read and applied (the status is the truth, the event is the signal), so out-of-order events are
 * harmless. If the PSP is down the reader waits and carries on from the same place.
 */
@Component
public class EventReader {

    private static final Logger log = LoggerFactory.getLogger(EventReader.class);
    private final JdbcTemplate jdbc;
    private final Psp psp;
    private final Payments payments;
    private volatile boolean pspUp = true;

    public EventReader(JdbcTemplate jdbc, Psp psp, Payments payments) {
        this.jdbc = jdbc;
        this.psp = psp;
        this.payments = payments;
    }

    public boolean pspUp() {
        return pspUp;
    }

    @Scheduled(fixedDelayString = "${commerce.events.poll-ms:4000}", initialDelay = 5_000)
    public void poll() {
        for (int round = 0; round < 10; round++) {
            long cursor = jdbc.queryForObject("SELECT last_id FROM event_cursor WHERE name = 'psp'", Long.class);
            JsonNode events;
            try {
                events = psp.events(cursor, 200);
                pspUp = true;
            } catch (Psp.Unavailable e) {
                if (pspUp) log.warn("[COMMERCE] PayPort Payments unavailable: {}", e.getMessage());
                pspUp = false;
                return;
            } catch (Psp.Refused e) {
                log.warn("[COMMERCE] event stream refused: {}", e.getMessage());
                return;
            }
            if (!events.isArray() || events.isEmpty()) return;
            for (JsonNode e : events) {
                if (!handle(e)) return;   // stop at the first event that could not be handled; it is read again next time
                jdbc.update("UPDATE event_cursor SET last_id = GREATEST(last_id, ?), updated_at = ? WHERE name = 'psp'", e.path("id").asLong(), Shop.now());
            }
            if (events.size() < 200) return;
        }
    }

    /** True when the event is handled (or not about Commerce); false to try again later. */
    boolean handle(JsonNode e) {
        String id = e.path("eventId").asText(""), type = e.path("type").asText("");
        if (id.isEmpty()) return true;
        if (!jdbc.queryForList("SELECT 1 FROM processed_events WHERE event_id = ?", Integer.class, id).isEmpty()) return true;
        if (type.startsWith("checkout.") || type.startsWith("refund.")) {
            long merchant = e.path("merchantId").asLong(0);
            String ref = e.path("payload").path("reference").asText("");
            List<Integer> ours = merchant == 0 || ref.isEmpty() ? List.of()
                    : jdbc.queryForList("SELECT 1 FROM order_attempts WHERE merchant_id = ? AND psp_reference = ?", Integer.class, merchant, ref);
            if (ours.isEmpty()) return true;   // not a Commerce order: nothing to do
            try {
                payments.apply(merchant, ref, psp.status(merchant, ref));
            } catch (Psp.Unavailable ex) {
                return false;
            } catch (Psp.Refused ex) {
                log.warn("[COMMERCE] status of {} refused: {}", ref, ex.getMessage());
            }
            try {
                jdbc.update("INSERT INTO processed_events (event_id, event_type, psp_id, processed_at) VALUES (?,?,?,?)", id, type, e.path("id").asLong(), Shop.now());
            } catch (DuplicateKeyException ignored) {
                // handled by another reader at the same moment
            }
        }
        return true;
    }

    @Scheduled(cron = "0 15 4 * * *")
    public void tidy() {
        jdbc.update("DELETE FROM processed_events WHERE processed_at < NOW() - INTERVAL 180 DAY");
    }
}
