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

    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final ScreeningService screening;
    private final ComplianceAudit audit;
    private final ComplianceParameters parameters;
    private final FiuReportService reports;
    /** Report payloads hold database rows: dates are written as ISO text (no JSR-310 module on this classpath). */
    private final ObjectMapper json = new ObjectMapper().registerModule(new com.fasterxml.jackson.databind.module.SimpleModule()
            .addSerializer(LocalDateTime.class, com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance)
            .addSerializer(LocalDate.class, com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance)
            .addSerializer(java.sql.Timestamp.class, com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance)
            .addSerializer(java.time.OffsetDateTime.class, com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance));
    private final String reportingEntity;

    public ComplianceService(JdbcTemplate jdbc, ScreeningService screening, ComplianceAudit audit, ComplianceParameters parameters, FiuReportService reports,
                             @Value("${payport.compliance.reporting-entity:PayPort (Private) Limited}") String reportingEntity) {
        this.jdbc = jdbc;
        this.screening = screening;
        this.audit = audit;
        this.parameters = parameters;
        this.reports = reports;
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
            audit.record(by, "RULE_CHANGE_REQUESTED", "risk_rule:" + code, payload);
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
        if ("RISK_RULE_CREATE".equals(a.actionType())) return applyRuleCreate(a, approve, by);
        if (!"RISK_RULE_CHANGE".equals(a.actionType())) throw new IllegalStateException("not a rule change");
        if (approve) {
            try {
                JsonNode ch = json.readTree(a.payload());
                // The approved definition is recorded: only an approved rule can ever block (DB CHECK as well).
                int n = jdbc.update("UPDATE risk_rules SET mode = ?, action = ?, params = ?, version = version + 1, updated_by = ?, updated_at = ?, "
                                + "approved_by = ?, approved_at = ? WHERE code = ? AND version = ?", ch.path("mode").asText(), ch.path("action").asText(),
                        ch.path("params").asText(), a.requestedBy() + " / " + by, Timestamp.valueOf(LocalDateTime.now()),
                        a.requestedBy() + " / " + by, Timestamp.valueOf(LocalDateTime.now()), ch.path("code").asText(), ch.path("fromVersion").asInt());
                if (n == 0) throw new IllegalStateException("The rule changed since this request was made; request again");
                log.warn("[COMPLIANCE] Rule {} changed to {} / {} (requested {}, approved {})", ch.path("code").asText(), ch.path("mode").asText(),
                        ch.path("action").asText(), a.requestedBy(), by);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
        audit.record(by, approve ? "RULE_CHANGE_APPROVED" : "RULE_CHANGE_REJECTED", a.subjectRef(), a.payload());
        return Map.of("approvalId", a.id(), "status", a.status());
    }

    public static final Set<String> RULE_TYPES = Set.of("VELOCITY_COUNT", "VELOCITY_AMOUNT", "STRUCTURING", "LARGE_TRANSACTION", "SANCTIONS",
            "TX_LIMIT", "PERIOD_LIMIT", "CDD_THRESHOLD");

    /**
     * A new rule, proposed by one officer. On approval it is created in OBSERVE whatever was asked:
     * enforcing it is a separate, separately approved change.
     */
    @Transactional
    public Map<String, Object> requestRuleCreate(String code, String name, String type, JsonNode params, String action, String severity,
                                                 String source, String reason, String by) {
        if (code == null || !code.matches("[A-Z0-9_]{3,40}")) throw new IllegalArgumentException("code: 3-40 of A-Z 0-9 _");
        if (!RULE_TYPES.contains(type)) throw new IllegalArgumentException("type must be one of " + RULE_TYPES);
        if (!Set.of("ALERT", "BLOCK").contains(action)) throw new IllegalArgumentException("action must be ALERT or BLOCK");
        if (!Set.of("LOW", "MEDIUM", "HIGH").contains(severity)) throw new IllegalArgumentException("severity must be LOW, MEDIUM or HIGH");
        if (name == null || name.isBlank() || params == null) throw new IllegalArgumentException("name and params are required");
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM risk_rules WHERE code = ?", Integer.class, code);
        if (exists != null && exists > 0) throw new IllegalArgumentException("rule " + code + " exists");
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("code", code); def.put("name", name); def.put("type", type); def.put("params", params.toString());
        def.put("action", action); def.put("severity", severity); def.put("source", source == null ? "" : source);
        try {
            String payload = json.writeValueAsString(def);
            ApprovalStore.Approval a = jdbc.execute((ConnectionCallback<ApprovalStore.Approval>) c ->
                    ApprovalStore.request(c, "RISK_RULE_CREATE", "risk_rule:" + code, payload, reason, ROLE, by, 72));
            audit.record(by, "RULE_CREATE_REQUESTED", "risk_rule:" + code, payload);
            return Map.of("approvalId", a.id(), "approvalRef", a.ref(), "status", a.status(), "rule", def);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> applyRuleCreate(ApprovalStore.Approval a, boolean approve, String by) {
        if (approve) {
            try {
                JsonNode d = json.readTree(a.payload());
                jdbc.update("INSERT INTO risk_rules (code, name, rule_type, params, mode, action, severity, updated_by, updated_at, source, param_status) "
                                + "VALUES (?, ?, ?, ?, 'OBSERVE', ?, ?, ?, ?, ?, 'PROVISIONAL')", d.path("code").asText(), d.path("name").asText(),
                        d.path("type").asText(), d.path("params").asText(), d.path("action").asText(), d.path("severity").asText(),
                        a.requestedBy() + " / " + by, Timestamp.valueOf(LocalDateTime.now()), d.path("source").asText());
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
        audit.record(by, approve ? "RULE_CREATE_APPROVED" : "RULE_CREATE_REJECTED", a.subjectRef(), a.payload());
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
        audit.record(by, "CASE_ASSIGNED", "risk_case:" + caseId, assignee);
        return caseDetail(caseId);
    }

    @Transactional
    public void note(long caseId, String text, String by) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("note is empty");
        jdbc.update("INSERT INTO risk_case_notes (case_id, author, note, created_at) VALUES (?, ?, ?, ?)",
                caseId, by, text.length() > 4000 ? text.substring(0, 4000) : text, Timestamp.valueOf(LocalDateTime.now()));
        audit.record(by, "CASE_NOTE", "risk_case:" + caseId, text);
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
        audit.record(by, "CASE_OUTCOME_PROPOSED", "risk_case:" + caseId, o);
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
            audit.record(by, "CASE_PROPOSAL_RETURNED", "risk_case:" + caseId, note);
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
        audit.record(by, "CASE_CLOSED", "risk_case:" + caseId, status + " proposed by " + c.get("proposed_by"));
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
        if ("CONFIRMED_SUSPICIOUS".equals(outcome)) reports.draftStr(caseId, (String) c.get("proposed_by"));
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

    /**
     * CTR: for each customer and calendar day in the period, completed payments whose aggregate
     * reaches the threshold (CTR.THRESHOLD_MINOR, aggregated per CTR.AGGREGATION_HOURS = 24).
     */
    @Transactional
    public Map<String, Object> prepareCtr(LocalDateTime from, LocalDateTime to, String by) {
        JsonNode thresholds = parameters.get("CTR.THRESHOLD_MINOR");
        List<Map<String, Object>> lines = new ArrayList<>();
        for (var it = thresholds.fields(); it.hasNext(); ) {
            var f = it.next();
            List<Map<String, Object>> groups = jdbc.queryForList("SELECT customer_phone, DATE(completed_at) AS day, SUM(amount_cents) AS total FROM transactions "
                    + "WHERE status = 'COMPLETED' AND currency = ? AND completed_at >= ? AND completed_at < ? AND customer_phone IS NOT NULL "
                    + "GROUP BY customer_phone, DATE(completed_at) HAVING SUM(amount_cents) >= ?", f.getKey(), Timestamp.valueOf(from), Timestamp.valueOf(to), f.getValue().asLong());
            for (Map<String, Object> g : groups) {
                List<Map<String, Object>> txs = jdbc.queryForList("SELECT reference, completed_at, amount_cents, currency, customer_phone, recipient_identifier, "
                        + "payment_provider_code FROM transactions WHERE status = 'COMPLETED' AND currency = ? AND customer_phone = ? AND DATE(completed_at) = ? ORDER BY completed_at",
                        f.getKey(), g.get("customer_phone"), g.get("day"));
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("customerPhone", g.get("customer_phone"));
                line.put("day", String.valueOf(g.get("day")));
                line.put("currency", f.getKey());
                line.put("aggregateMinor", ((Number) g.get("total")).longValue());
                line.put("transactions", txs);
                lines.add(line);
            }
        }
        long id = reports.draftCtr(from, to, lines, by);
        return reports.view(id);
    }

    public Map<String, Object> report(long id) {
        return reports.view(id);
    }

    public Map<String, Object> approveReport(long id, String by) {
        return reports.approve(id, by);
    }

    public Map<String, Object> markSubmitted(long id, String fiuReference, String by) {
        return reports.markSubmitted(id, fiuReference, by);
    }
}
