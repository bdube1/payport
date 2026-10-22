package zw.co.payport.gateway.products;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.platform.ApprovalStore;
import zw.co.payport.gateway.risk.ComplianceAudit;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Product lines (QR, FX, wallets, agents, WhatsApp, POS). An administrator switches each one on or off
 * at any time; every switch is audited. Switching a line off also switches off the channels that
 * depend on it.
 */
@Slf4j
@Service
public class ServiceLineService {


    private final JdbcTemplate jdbc;
    private final ComplianceAudit audit;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private zw.co.payport.gateway.channels.ChannelService channels;

    private void refreshChannels() {
        if (channels == null) return;
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCompletion(int status) { channels.refresh(); }
                    });
        } else {
            channels.refresh();
        }
    }

    public ServiceLineService(JdbcTemplate jdbc, ComplianceAudit audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT * FROM service_lines ORDER BY harvest_status, code");
    }

    public boolean enabled(String code) {
        List<Boolean> v = jdbc.queryForList("SELECT enabled FROM service_lines WHERE code = ?", Boolean.class, code);
        return !v.isEmpty() && v.get(0);
    }

    public void require(String code, String what) {
        if (!enabled(code)) throw new zw.co.payport.gateway.channels.ChannelUnavailableException(code, what + " is not available at the moment.");
    }

    @Transactional
    public Map<String, Object> setEnabled(String code, boolean enabled, String reason, String by) {
        if (reason == null || reason.isBlank()) reason = enabled ? "Switched on" : "Switched off";
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM service_lines WHERE code = ? FOR UPDATE", code);
        if (rows.isEmpty()) throw new IllegalArgumentException("Unknown product line " + code);
        if (enabled && "PARKED".equals(rows.get(0).get("harvest_status"))) throw new IllegalStateException(code + " is not available in this version");
        jdbc.update("UPDATE service_lines SET enabled = ?, updated_by = ?, updated_at = ? WHERE code = ?", enabled, by, now(), code);
        if (!enabled) jdbc.update("UPDATE platform_channels SET enabled = FALSE, reason = ?, updated_by = ?, updated_at = ? WHERE licence_gate = ?", reason, by, now(), code);
        audit.record(by, enabled ? "SERVICE_LINE_ON" : "SERVICE_LINE_OFF", "service_line:" + code, reason);
        refreshChannels();
        log.warn("[PRODUCTS] {} switched {} by {}: {}", code, enabled ? "on" : "off", by, reason);
        return Map.of("code", code, "enabled", enabled);
    }

    private static Timestamp now() {
        return Timestamp.valueOf(LocalDateTime.now());
    }
}
