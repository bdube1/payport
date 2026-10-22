package zw.co.payport.gateway.channels;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.risk.ComplianceAudit;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Channels (web, mobile app, USSD, merchant API, payment links, hosted checkout, QR, WhatsApp, POS)
 * switched on and off from the ops console. A switched-off channel refuses new payments before
 * anything is sent ("No money was taken"); payments already in progress finish normally.
 *
 * Switching off is immediate. Switching on is one person with a reason, except for a channel behind
 * a licence gate (Phase 8), which can only be switched on once its service line's licence question
 * is answered (GRANTED or NOT_REQUIRED). Every switch is in the hash-chained audit log.
 * Instances follow a switch within 10 seconds.
 */
@Slf4j
@Service
public class ChannelService {

    private static final long CACHE_MS = 10_000;

    private final JdbcTemplate jdbc;
    private final ComplianceAudit audit;
    private final Map<String, Boolean> cache = new ConcurrentHashMap<>();
    private volatile long loadedAt;

    public ChannelService(JdbcTemplate jdbc, ComplianceAudit audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public boolean enabled(String code) {
        if (code == null) return false;
        if (System.currentTimeMillis() - loadedAt > CACHE_MS) refresh();
        return Boolean.TRUE.equals(cache.get(code.toUpperCase(Locale.ROOT)));
    }

    public void require(String code) {
        String c = code == null ? "" : code.toUpperCase(Locale.ROOT);
        if (System.currentTimeMillis() - loadedAt > CACHE_MS) refresh();
        if (!cache.containsKey(c)) throw new IllegalArgumentException("Unknown channel " + code);
        if (!Boolean.TRUE.equals(cache.get(c))) {
            throw new ChannelUnavailableException(c, "Payments through this channel are switched off at the moment. No money was taken. Please try again later or use another option.");
        }
    }

    public synchronized void refresh() {
        Map<String, Boolean> m = new ConcurrentHashMap<>();
        jdbc.query("SELECT code, enabled FROM platform_channels", rs -> { m.put(rs.getString("code"), rs.getBoolean("enabled")); });
        cache.clear();
        cache.putAll(m);
        loadedAt = System.currentTimeMillis();
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT c.code, c.name, c.description, c.enabled, c.licence_gate AS product_line, c.reason, c.updated_by, c.updated_at FROM platform_channels c ORDER BY c.code");
    }

    @Transactional
    public Map<String, Object> set(String code, boolean enabled, String reason, String by) {
        if (reason == null || reason.isBlank()) reason = enabled ? "Switched on" : "Switched off";
        String c = code.toUpperCase(Locale.ROOT);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT code FROM platform_channels WHERE code = ? FOR UPDATE", c);
        if (rows.isEmpty()) throw new IllegalArgumentException("Unknown channel " + code);
        jdbc.update("UPDATE platform_channels SET enabled = ?, reason = ?, updated_by = ?, updated_at = ? WHERE code = ?",
                enabled, reason, by, Timestamp.valueOf(LocalDateTime.now()), c);
        audit.record(by, enabled ? "CHANNEL_ON" : "CHANNEL_OFF", "channel:" + c, reason);
        log.warn("[CHANNELS] {} switched {} by {}: {}", c, enabled ? "on" : "off", by, reason);
        refresh();
        return Map.of("code", c, "enabled", enabled);
    }
}
