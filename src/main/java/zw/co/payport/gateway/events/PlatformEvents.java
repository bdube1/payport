package zw.co.payport.gateway.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The platform event stream (platform_events): one append-only record of each thing that happens to money, written
 * when it happens and read in order by Payport Commerce, reporting and later Payport Intelligence.
 *
 * Rules: an event is never changed or deleted before its retention ends; each state change gives exactly one event
 * (the dedupe key, e.g. "checkout:42:paid", is unique, so a repeated or concurrent record is ignored); payloads are
 * counts, amounts, references and codes only (no card data, no names, no phone numbers); events carry a version so a
 * consumer can read old and new shapes. The stream never replaces the transaction tables or the ledger: those stay
 * the authority on money.
 */
@Slf4j
@Service
public class PlatformEvents {

    public static final String SOURCE = "PSP";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private final int retentionDays;

    public PlatformEvents(JdbcTemplate jdbc, @Value("${payport.events.retention-days:400}") int retentionDays) {
        this.jdbc = jdbc;
        this.retentionDays = Math.max(30, retentionDays);
    }

    /**
     * Records one event; returns false when this state change was already recorded. Never throws into the payment
     * path: a failure is logged and the consumers' reconciliation (they also read status) covers the gap.
     */
    public boolean record(String type, Long merchantId, String subjectRef, String dedupeKey, boolean livemode, Map<String, Object> payload) {
        try {
            int n = jdbc.update("INSERT IGNORE INTO platform_events (event_id, event_type, version, source, merchant_id, subject_ref, dedupe_key, livemode, payload, occurred_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), type, 1, SOURCE, merchantId, clip(subjectRef, 100), clip(dedupeKey, 190), livemode,
                    json.writeValueAsString(payload == null ? Map.of() : payload), Timestamp.valueOf(LocalDateTime.now()));
            return n > 0;
        } catch (Exception e) {
            log.warn("[EVENTS] {} for {} not recorded: {}", type, subjectRef, e.getMessage());
            return false;
        }
    }

    /** Events after a cursor, oldest first (the consumer keeps the last id it handled). */
    public List<Map<String, Object>> after(long cursor, int limit, String typePrefix) {
        int n = Math.max(1, Math.min(limit, 500));
        String sql = "SELECT id, event_id, event_type, version, source, merchant_id, subject_ref, livemode, payload, occurred_at FROM platform_events WHERE id > ?"
                + (typePrefix == null || typePrefix.isBlank() ? "" : " AND event_type LIKE ?") + " ORDER BY id LIMIT " + n;
        List<Map<String, Object>> rows = typePrefix == null || typePrefix.isBlank()
                ? jdbc.queryForList(sql, cursor)
                : jdbc.queryForList(sql, cursor, typePrefix.replace("%", "") + "%");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", ((Number) r.get("id")).longValue());
            e.put("eventId", r.get("event_id"));
            e.put("type", r.get("event_type"));
            e.put("version", r.get("version"));
            e.put("source", r.get("source"));
            e.put("merchantId", r.get("merchant_id"));
            e.put("subjectRef", r.get("subject_ref"));
            e.put("livemode", Boolean.TRUE.equals(r.get("livemode")) || Integer.valueOf(1).equals(r.get("livemode")));
            e.put("occurredAt", String.valueOf(r.get("occurred_at")));
            try {
                Object p = r.get("payload");
                e.put("payload", json.readValue(p instanceof byte[] b ? new String(b, java.nio.charset.StandardCharsets.UTF_8) : String.valueOf(p), Map.class));
            } catch (Exception ex) {
                e.put("payload", Map.of());
            }
            out.add(e);
        }
        return out;
    }

    /** Events older than the retention period are removed in small batches (the ledger and transactions keep the history). */
    @Scheduled(cron = "${payport.events.retention-cron:0 40 3 * * *}")
    public void retention() {
        Timestamp before = Timestamp.valueOf(LocalDateTime.now().minusDays(retentionDays));
        int total = 0, n;
        do {
            n = jdbc.update("DELETE FROM platform_events WHERE occurred_at < ? ORDER BY id LIMIT 5000", before);
            total += n;
        } while (n == 5000 && total < 200_000);
        if (total > 0) log.info("[EVENTS] {} events older than {} days removed", total, retentionDays);
    }

    static String clip(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }
}
