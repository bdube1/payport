package zw.co.payport.gateway.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.platform.ApprovalStore;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compliance workflow: rule changes (two officers), cases (four-eyes closure), FIU reports
 * (preparer and approver differ). Only the COMPLIANCE role reaches this (ComplianceController).
 */
@Slf4j
@Service
public class ComplianceService {

    public static final String ROLE = "COMPLIANCE";
    public static final Set<String> OUTCOMES = Set.of("FALSE_POSITIVE", "NO_ACTION", "CONFIRMED_SUSPICIOUS");
    /** Provisional report structure until the FIU confirms its submission format. */
    public static final String FIU_FORMAT = "PAYPORT-FIU-PROVISIONAL-v0";

    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final ScreeningService screening;
    private final ObjectMapper json = new ObjectMapper();
    private final String reportingEntity;

    public ComplianceService(JdbcTemplate jdbc, ScreeningService screening,
                             @Value("${payport.compliance.reporting-entity:PayPort (Private) Limited}") String reportingEntity) {
        this.jdbc = jdbc;
        this.screening = screening;
        this.reportingEntity = reportingEntity;
    }

    // ─── rules: changes need two compliance officers ─────────────────────────

    @Transactional
    public Map<String, Object> requestRuleChange(String code, String mode, String action, JsonNode params, String reason, String by) {
        Map<String, Object> rule = jdbc.queryForMap("SELECT * FROM risk_rules WHERE code = ?", code);
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("code", code);
        change.put("mode", mode == null ? rule.get("mode") : mode.toUpperCase());
        change.put("action", action == null ? rule.get("action") : action.toUpperCase());
        change.put("params", params == null ? rule.get("params") : params.toString());
        change.put("fromVersion", rule.get("version"));
        if (!Set.of("OFF", "OBSERVE", "ENFORCE").contains(change.get("mode"))) throw new IllegalArgumentException("mode must be OFF, OBSERVE or ENFORCE");
        if (!Set.of("ALERT", "BLOCK").contains(change.get("action"))) throw new IllegalArgumentException("action must be ALERT or BLOCK");
        try {
            json.readTree((String) change.get("params"));
            String payload = json.writeValueAsString(change);
            ApprovalStore.Approval a = jdbc.execute((ConnectionCallback<ApprovalStore.Approval>) c ->
                    ApprovalStore.request(c, "RISK_RULE_CHANGE", "risk_rule:" + code, payload, reason, ROLE, by, 72));
            return Map.of("approvalId", a.id(), "approvalRef", a.ref(), "status", a.status(), "change", change);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("params must be JSON");
        }
    }

    /** A second officer approves or rejects; an approved change is applied at once (version + 1). */
    @Transactional
    public Map<String, Object> decideRuleChange(long approvalId, boolean approve, String note, String by) {
        ApprovalStore.Approval a = jdbc.execute((ConnectionCallback<ApprovalStore.Approval>) c ->
                ApprovalStore.decide(c, approvalId, approve, by, Set.of(ROLE), note));
        if (!"RISK_RULE_CHANGE".equals(a.actionType())) throw new IllegalStateException("not a rule change");
        if (approve) {
            try {
                JsonNode ch = json.readTree(a.payload());
                int n = jdbc.update("UPDATE risk_rules SET mode = ?, action = ?, params = ?, version = version + 1, updated_by = ?, updated_at = ? "
                                + "WHERE code = ? AND version = ?", ch.path("mode").asText(), ch.path("action").asText(), ch.path("params").asText(),
                        a.requestedBy() + " / " + by, Timestamp.valueOf(LocalDateTime.now()), ch.path("code").asText(), ch.path("fromVersion").asInt());
                if (n == 0) throw new IllegalStateException("The rule changed since this request was made; request again");
                log.warn("[COMPLIANCE] Rule {} changed to {} / {} (requested {}, approved {})", ch.path("code").asText(), ch.path("mode").asText(),
                        ch.path("action").asText(), a.requestedBy(), by);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
        return Map.of("approvalId", a.id(), "status", a.status());
    }

    // ─── cases ────────────────────────────────────────────────────────────────

    public Map<String, Object> caseDetail(long caseId) {
        Map<String, Object> m = new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM risk_cases WHERE id = ?", caseId));
        m.put("alerts", jdbc.queryForList("SELECT * FROM risk_alerts WHERE case_id = ? ORDER BY id", caseId));
        m.put("notes", jdbc.queryForList("SELECT author, note, created_at FROM risk_case_notes WHERE case_id = ? ORDER BY id", caseId));
        m.put("reports", jdbc.queryForList("SELECT id, report_ref, report_type, status FROM fiu_reports WHERE case_id = ?", caseId));
        return m;
    }

    @Transactional
    public Map<String, Object> openCaseFromAlerts(List<Long> alertIds, String by) {
        if (alertIds == null || alertIds.isEmpty()) throw new IllegalArgumentException("alertIds required");
        Map<String, Object> first = jdbc.queryForMap("SELECT * FROM risk_alerts WHERE id = ?", alertIds.get(0));
        long caseId;
        for (Long id : alertIds) {
            Map<String, Object> a = jdbc.queryForMap("SELECT * FROM risk_alerts WHERE id = ?", id);
            if (!a.get("subject_key").equals(first.get("subject_key"))) throw new IllegalArgumentException("alerts must share one subject");
        }
        caseId = attach(alertIds, (String) first.get("subject_type"), (String) first.get("subject_key"), (String) first.get("subject_name"), by);
        return caseDetail(caseId);
    }

    private long attach(List<Long> alertIds, String type, String key, String name, String by) {
        List<Long> open = jdbc.queryForList("SELECT id FROM risk_cases WHERE subject_type = ? AND subject_key = ? AND status NOT LIKE 'CLOSED%' LIMIT 1",
                Long.class, type, key);
        long caseId;
        if (open.isEmpty()) {
            byte[] b = new byte[4];
            RANDOM.nextBytes(b);
            String ref = "CASE-" + LocalDate.now().toString().replace("-", "") + "-" + HexFormat.of().formatHex(b).toUpperCase();
            jdbc.update("INSERT INTO risk_cases (case_ref, subject_type, subject_key, subject_name, status, priority, opened_at, sla_due_at) "
                    + "VALUES (?, ?, ?, ?, 'OPEN', 'MEDIUM', ?, ?)", ref, type, key, name, Timestamp.valueOf(LocalDateTime.now()),
                    Timestamp.valueOf(LocalDateTime.now().plusDays(5)));
            caseId = jdbc.queryForObject("SELECT id FROM risk_cases WHERE case_ref = ?", Long.class, ref);
            note(caseId, "Case opened by " + by + " from alerts " + alertIds, by);
        } else {
            caseId = open.get(0);
        }
        for (Long id : alertIds) jdbc.update("UPDATE risk_alerts SET status = 'IN_CASE', case_id = ? WHERE id = ?", caseId, id);
        return caseId;
    }

    @Transactional
    public Map<String, Object> assign(long caseId, String assignee, String by) {
        Map<String, Object> c = lockCase(caseId);
        requireOpen(c);
        jdbc.update("UPDATE risk_cases SET assignee = ?, status = IF(status = 'OPEN', 'INVESTIGATING', status) WHERE id = ?", assignee, caseId);
        note(caseId, "Assigned to " + assignee, by);
        return caseDetail(caseId);
    }

    @Transactional
    public void note(long caseId, String text, String by) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("note is empty");
        jdbc.update("INSERT INTO risk_case_notes (case_id, author, note, created_at) VALUES (?, ?, ?, ?)",
                caseId, by, text.length() > 4000 ? text.substring(0, 4000) : text, Timestamp.valueOf(LocalDateTime.now()));
    }

    /** Step 1: the investigating officer proposes how to close the case, with reasons. */
    @Transactional
    public Map<String, Object> propose(long caseId, String outcome, String rationale, String by) {
        Map<String, Object> c = lockCase(caseId);
        requireOpen(c);
        String o = outcome == null ? "" : outcome.toUpperCase();
        if (!OUTCOMES.contains(o)) throw new IllegalArgumentException("outcome must be one of " + OUTCOMES);
        if (rationale == null || rationale.trim().length() < 10) throw new IllegalArgumentException("a rationale is required");
        jdbc.update("UPDATE risk_cases SET status = 'PENDING_DECISION', proposed_outcome = ?, proposed_by = ?, proposed_at = ?, proposal_note = ? WHERE id = ?",
                o, by, Timestamp.valueOf(LocalDateTime.now()), rationale, caseId);
        note(caseId, "Proposed " + o + ": " + rationale, by);
        return caseDetail(caseId);
    }

    /**
     * Step 2: a different officer accepts or sends it back. Accepting:
     *   FALSE_POSITIVE        alerts closed; screening matches suppressed for this subject
     *   NO_ACTION             alerts closed
     *   CONFIRMED_SUSPICIOUS  alerts closed; an STR draft is prepared for the FIU
     */
    @Transactional
    public Map<String, Object> decide(long caseId, boolean accept, String note, String by) {
        Map<String, Object> c = lockCase(caseId);
        if (!"PENDING_DECISION".equals(c.get("status"))) throw new IllegalStateException("No proposal to decide on");
        if (by.equals(c.get("proposed_by"))) throw new IllegalStateException("A second compliance officer must decide");
        if (!accept) {
            jdbc.update("UPDATE risk_cases SET status = 'INVESTIGATING', proposed_outcome = NULL, proposed_by = NULL, proposed_at = NULL WHERE id = ?", caseId);
            note(caseId, "Proposal sent back: " + (note == null ? "" : note), by);
            return caseDetail(caseId);
        }
        String outcome = (String) c.get("proposed_outcome");
        String status = switch (outcome) {
            case "FALSE_POSITIVE" -> "CLOSED_FALSE_POSITIVE";
            case "NO_ACTION" -> "CLOSED_NO_ACTION";
            default -> "CLOSED_CONFIRMED";
        };
        jdbc.update("UPDATE risk_cases SET status = ?, decided_by = ?, decided_at = ? WHERE id = ?", status, by, Timestamp.valueOf(LocalDateTime.now()), caseId);
        jdbc.update("UPDATE risk_alerts SET status = 'CLOSED' WHERE case_id = ?", caseId);
        note(caseId, "Decision: " + status + (note == null ? "" : " (" + note + ")"), by);
        if ("FALSE_POSITIVE".equals(outcome)) {
            List<String[]> entries = new ArrayList<>();
            for (Map<String, Object> a : jdbc.queryForList("SELECT details FROM risk_alerts WHERE case_id = ?", caseId)) {
                try {
                    for (JsonNode m : json.readTree(String.valueOf(a.get("details"))).path("matches")) {
                        entries.add(new String[]{m.path("listCode").asText(), m.path("entryRef").asText()});
                    }
                } catch (Exception ignored) { }
            }
            screening.suppress((String) c.get("subject_key"), entries, caseId, by);
        }
        if ("CONFIRMED_SUSPICIOUS".equals(outcome)) prepareStr(caseId, (String) c.get("proposed_by"));
        log.info("[COMPLIANCE] Case {} {} (proposed {}, decided {})", c.get("case_ref"), status, c.get("proposed_by"), by);
        return caseDetail(caseId);
    }

    private Map<String, Object> lockCase(long id) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT * FROM risk_cases WHERE id = ? FOR UPDATE", id);
        if (r.isEmpty()) throw new IllegalArgumentException("No case " + id);
        return r.get(0);
    }

    private static void requireOpen(Map<String, Object> c) {
        if (String.valueOf(c.get("status")).startsWith("CLOSED")) throw new IllegalStateException("Case is closed");
    }

    // ─── FIU reports ──────────────────────────────────────────────────────────

    private long prepareStr(long caseId, String preparedBy) {
        Map<String, Object> c = jdbc.queryForMap("SELECT * FROM risk_cases WHERE id = ?", caseId);
        List<Map<String, Object>> alerts = jdbc.queryForList("SELECT rule_code, severity, transaction_reference, details, created_at FROM risk_alerts WHERE case_id = ?", caseId);
        List<Map<String, Object>> txs = new ArrayList<>();
        String phone = String.valueOf(c.get("subject_key")).startsWith("PHONE:TEL:") ? String.valueOf(c.get("subject_key")).substring(10) : null;
        if (phone != null) {
            txs = jdbc.queryForList("SELECT reference, created_at, amount_cents, currency, status, customer_phone, recipient_identifier, payment_provider_code "
                    + "FROM transactions WHERE RIGHT(REPLACE(customer_phone, '+', ''), 9) = ? ORDER BY created_at DESC LIMIT 200", phone);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("format", FIU_FORMAT);
        payload.put("formatStatus", "PROVISIONAL: the FIU submission format is not yet confirmed; transcribe into the FIU's format when filing");
        payload.put("reportType", "STR");
        payload.put("reportingEntity", reportingEntity);
        payload.put("caseRef", c.get("case_ref"));
        payload.put("subject", Map.of("type", c.get("subject_type"), "key", c.get("subject_key"), "name", String.valueOf(c.get("subject_name"))));
        payload.put("grounds", c.get("proposal_note"));
        payload.put("alerts", alerts);
        payload.put("transactions", txs);
        return insertReport("STR", caseId, null, null, payload, preparedBy);
    }

    /** CTR: every completed payment at or above the LARGE_TRANSACTION threshold in the period. */
    @Transactional
    public Map<String, Object> prepareCtr(LocalDateTime from, LocalDateTime to, String by) {
        JsonNode params;
        try {
            params = json.readTree(jdbc.queryForObject("SELECT params FROM risk_rules WHERE code = 'LARGE_TRANSACTION'", String.class)).path("thresholdMinor");
        } catch (Exception e) {
            throw new IllegalStateException("LARGE_TRANSACTION rule missing");
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (var it = params.fields(); it.hasNext(); ) {
            var f = it.next();
            lines.addAll(jdbc.queryForList("SELECT reference, completed_at, amount_cents, currency, customer_phone, recipient_identifier, payment_provider_code "
                    + "FROM transactions WHERE status = 'COMPLETED' AND currency = ? AND amount_cents >= ? AND completed_at >= ? AND completed_at < ? ORDER BY completed_at",
                    f.getKey(), f.getValue().asLong(), Timestamp.valueOf(from), Timestamp.valueOf(to)));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("format", FIU_FORMAT);
        payload.put("formatStatus", "PROVISIONAL: thresholds and format to be confirmed against the FIU's requirements");
        payload.put("reportType", "CTR");
        payload.put("reportingEntity", reportingEntity);
        payload.put("period", Map.of("from", from.toString(), "to", to.toString()));
        payload.put("thresholdsMinor", params);
        payload.put("transactions", lines);
        long id = insertReport("CTR", null, from, to, payload, by);
        return report(id);
    }

    private long insertReport(String type, Long caseId, LocalDateTime from, LocalDateTime to, Map<String, Object> payload, String by) {
        byte[] b = new byte[4];
        RANDOM.nextBytes(b);
        String ref = type + "-" + LocalDate.now().toString().replace("-", "") + "-" + HexFormat.of().formatHex(b).toUpperCase();
        try {
            jdbc.update("INSERT INTO fiu_reports (report_ref, report_type, status, case_id, period_from, period_to, format_version, payload, prepared_by, prepared_at) "
                            + "VALUES (?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?)", ref, type, caseId, from == null ? null : Timestamp.valueOf(from),
                    to == null ? null : Timestamp.valueOf(to), FIU_FORMAT, json.writeValueAsString(payload), by, Timestamp.valueOf(LocalDateTime.now()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return jdbc.queryForObject("SELECT id FROM fiu_reports WHERE report_ref = ?", Long.class, ref);
    }

    public Map<String, Object> report(long id) {
        return jdbc.queryForMap("SELECT * FROM fiu_reports WHERE id = ?", id);
    }

    @Transactional
    public Map<String, Object> approveReport(long id, String by) {
        Map<String, Object> r = jdbc.queryForMap("SELECT * FROM fiu_reports WHERE id = ? FOR UPDATE", id);
        if (!"DRAFT".equals(r.get("status"))) throw new IllegalStateException("Report is " + r.get("status"));
        if (by.equals(r.get("prepared_by"))) throw new IllegalStateException("The preparer cannot approve the report");
        jdbc.update("UPDATE fiu_reports SET status = 'APPROVED', approved_by = ?, approved_at = ? WHERE id = ?", by, Timestamp.valueOf(LocalDateTime.now()), id);
        return report(id);
    }

    /** The MLRO filed it with the FIU (outside PayPort until the format and channel are confirmed). */
    @Transactional
    public Map<String, Object> markSubmitted(long id, String fiuReference, String by) {
        Map<String, Object> r = jdbc.queryForMap("SELECT * FROM fiu_reports WHERE id = ? FOR UPDATE", id);
        if (!"APPROVED".equals(r.get("status"))) throw new IllegalStateException("Only an APPROVED report can be marked submitted");
        if (fiuReference == null || fiuReference.isBlank()) throw new IllegalArgumentException("The FIU's acknowledgement reference is required");
        jdbc.update("UPDATE fiu_reports SET status = 'SUBMITTED', submitted_by = ?, submitted_at = ?, fiu_reference = ? WHERE id = ?",
                by, Timestamp.valueOf(LocalDateTime.now()), fiuReference.trim(), id);
        return report(id);
    }
}
