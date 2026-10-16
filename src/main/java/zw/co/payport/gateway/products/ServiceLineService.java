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
 * Phase 8 product lines (QR, FX, wallets, agents, WhatsApp, POS), each behind its licensing question.
 *
 *  - The licence status (NOT_ASSESSED, APPLIED, GRANTED, NOT_REQUIRED, REFUSED) is a compliance
 *    determination: one COMPLIANCE officer requests it with the evidence (licence reference,
 *    regulator), a second approves.
 *  - A line can only be switched on when its status is GRANTED or NOT_REQUIRED (database CHECK too).
 *    Switching on is an ADMIN action with a reason; switching off is immediate and always allowed.
 *  - PARKED lines (wallets, agents, WhatsApp, POS) have no code on this branch yet: the switch is
 *    refused until they are built, whatever the licence status.
 */
@Slf4j
@Service
public class ServiceLineService {

    public static final String ACTION = "SERVICE_LINE_LICENCE";
    static final Set<String> STATUSES = Set.of("NOT_ASSESSED", "APPLIED", "GRANTED", "NOT_REQUIRED", "REFUSED");

    private final JdbcTemplate jdbc;
    private final ComplianceAudit audit;
    private final ObjectMapper json = new ObjectMapper();

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

    public Map<String, Object> requestLicenceStatus(String code, String status, String reference, String regulator, String reason, String by) {
        String s = status == null ? "" : status.toUpperCase();
        if (!STATUSES.contains(s)) throw new IllegalArgumentException("status must be one of " + STATUSES);
        if (("GRANTED".equals(s) || "APPLIED".equals(s)) && (reference == null || reference.isBlank())) {
            throw new IllegalArgumentException("A licence or application reference is required for " + s);
        }
        jdbc.queryForMap("SELECT code FROM service_lines WHERE code = ?", code);
        String payload = json.createObjectNode().put("code", code).put("status", s).put("reference", reference).put("regulator", regulator).toString();
        ApprovalStore.Approval a = jdbc.execute((ConnectionCallback<ApprovalStore.Approval>) c ->
                ApprovalStore.request(c, ACTION, "service_line:" + code, payload, reason, "COMPLIANCE", by, 168));
        audit.record(by, "LICENCE_STATUS_REQUESTED", "service_line:" + code, payload);
        return Map.of("approvalId", a.id(), "approvalRef", a.ref());
    }

    @Transactional
    public Map<String, Object> decide(long approvalId, boolean approve, String note, String by) {
        ApprovalStore.Approval a = jdbc.execute((ConnectionCallback<ApprovalStore.Approval>) c ->
                ApprovalStore.decide(c, approvalId, approve, by, Set.of("COMPLIANCE"), note));
        if (!ACTION.equals(a.actionType())) throw new IllegalStateException("not a licence decision");
        if (approve) {
            try {
                JsonNode p = json.readTree(a.payload());
                String s = p.path("status").asText();
                // A licence that is no longer GRANTED / NOT_REQUIRED switches the line (and gated channels) off.
                boolean keepOn = "GRANTED".equals(s) || "NOT_REQUIRED".equals(s);
                jdbc.update("UPDATE service_lines SET licence_status = ?, licence_reference = ?, regulator = ?, decided_by = ?, decided_at = ?, "
                                + "enabled = enabled AND ?, updated_by = ?, updated_at = ? WHERE code = ?",
                        s, p.path("reference").isNull() ? null : p.path("reference").asText(), p.path("regulator").isNull() ? null : p.path("regulator").asText(),
                        a.requestedBy() + " / " + by, now(), keepOn, by, now(), p.path("code").asText());
                if (!keepOn) jdbc.update("UPDATE platform_channels SET enabled = FALSE, reason = ?, updated_by = ?, updated_at = ? WHERE licence_gate = ?",
                        "licence status " + s, by, now(), p.path("code").asText());
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
        audit.record(by, approve ? "LICENCE_STATUS_APPROVED" : "LICENCE_STATUS_REJECTED", a.subjectRef(), a.payload());
        refreshChannels();
        return Map.of("approvalId", a.id(), "status", a.status());
    }

    @Transactional
    public Map<String, Object> setEnabled(String code, boolean enabled, String reason, String by) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("A reason is required");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM service_lines WHERE code = ? FOR UPDATE", code);
        if (enabled) {
            if ("PARKED".equals(row.get("harvest_status"))) throw new IllegalStateException(code + " is parked: it is not built on this branch yet");
            Object s = row.get("licence_status");
            if (!"GRANTED".equals(s) && !"NOT_REQUIRED".equals(s)) {
                throw new IllegalStateException(code + " cannot be switched on: licence status is " + s + " (needs GRANTED or NOT_REQUIRED)");
            }
        }
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
