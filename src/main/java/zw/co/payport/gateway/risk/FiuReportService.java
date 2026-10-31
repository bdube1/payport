package zw.co.payport.gateway.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
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
 * FIU reports from configurable templates.
 *
 * A template (fiu_report_templates) lists sections and fields; each field says where its value
 * comes from: a compliance parameter, the case, the subject, the preparing officer, or "manual"
 * (filled in by the officer). A report is generated as a DRAFT with every automatic field filled
 * and the missing required fields listed; the officer completes the manual fields; an officer
 * holding REPORT_APPROVER (not the preparer) approves it, which is only possible once nothing
 * required is missing. Output: goAML-structured XML (the FIU's portal, Guideline 01-2025 s3.106)
 * plus the field document. STR due date: STR.DEADLINE_WORKING_DAYS working days after the
 * suspicion was formed (the case decision).
 *
 * Templates are versioned. A new version is created as DRAFT and becomes ACTIVE only when a
 * different compliance officer activates it (database CHECK); the previous one is retired.
 */
@Slf4j
@Service
public class FiuReportService {

    static final Set<String> SOURCE_PREFIXES = Set.of("param:", "case.", "subject.", "officer.", "report.", "manual", "const:");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final ComplianceParameters params;
    private final ComplianceAudit audit;
    private final ComplianceAuthorities authorities;
    private final WorkingCalendar calendar;
    private final ObjectMapper json = new ObjectMapper().registerModule(new com.fasterxml.jackson.databind.module.SimpleModule()
            .addSerializer(LocalDateTime.class, com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance)
            .addSerializer(LocalDate.class, com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance)
            .addSerializer(Timestamp.class, com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance)
            .addSerializer(java.sql.Date.class, com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance));

    public FiuReportService(JdbcTemplate jdbc, ComplianceParameters params, ComplianceAudit audit, ComplianceAuthorities authorities, WorkingCalendar calendar) {
        this.jdbc = jdbc;
        this.params = params;
        this.audit = audit;
        this.authorities = authorities;
        this.calendar = calendar;
    }

    // ─── templates ────────────────────────────────────────────────────────────

    /** Installs the bundled templates (version 1) on first start. They are provisional until compliance replaces them. */
    @EventListener(ApplicationReadyEvent.class)
    public void seedTemplates() {
        seed("PAYPORT_STR", "fiu-templates/payport-str-v1.json");
        seed("PAYPORT_CTR", "fiu-templates/payport-ctr-v1.json");
    }

    private void seed(String code, String resource) {
        try {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM fiu_report_templates WHERE code = ?", Integer.class, code);
            if (n != null && n > 0) return;
            String def = new String(new ClassPathResource(resource).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode d = validate(def);
            jdbc.update("INSERT INTO fiu_report_templates (code, report_type, version, status, output_format, definition, source, created_by, created_at, approved_by, activated_at) "
                            + "VALUES (?, ?, 1, 'ACTIVE', ?, ?, ?, 'SEED', ?, 'SEED:Payport provisional template', ?)", code, d.path("reportType").asText(),
                    d.path("outputFormat").asText(), def, "Payport template aligned to goAML report structure and FATF/Egmont guidance; PROVISIONAL until the FIU's requirements are confirmed",
                    Timestamp.valueOf(LocalDateTime.now()), Timestamp.valueOf(LocalDateTime.now()));
        } catch (Exception e) {
            log.error("[FIU] could not seed template {}: {}", code, e.getMessage());
        }
    }

    JsonNode validate(String definition) {
        JsonNode d;
        try {
            d = json.readTree(definition);
        } catch (Exception e) {
            throw new IllegalArgumentException("definition must be JSON");
        }
        if (!Set.of("STR", "CTR").contains(d.path("reportType").asText())) throw new IllegalArgumentException("reportType must be STR or CTR");
        if (!Set.of("GOAML_XML", "JSON").contains(d.path("outputFormat").asText())) throw new IllegalArgumentException("outputFormat must be GOAML_XML or JSON");
        if (!d.path("sections").isArray() || d.path("sections").isEmpty()) throw new IllegalArgumentException("sections are required");
        Set<String> names = new java.util.HashSet<>();
        for (JsonNode s : d.path("sections")) {
            for (JsonNode f : s.path("fields")) {
                String name = f.path("name").asText(), src = f.path("source").asText();
                if (name.isBlank() || f.path("label").asText().isBlank()) throw new IllegalArgumentException("every field needs a name and a label");
                if (!names.add(name)) throw new IllegalArgumentException("duplicate field " + name);
                if (SOURCE_PREFIXES.stream().noneMatch(src::startsWith)) throw new IllegalArgumentException("field " + name + ": unknown source " + src);
            }
        }
        return d;
    }

    @Transactional
    public Map<String, Object> createTemplateVersion(String code, String definition, String source, String by) {
        if (code == null || !code.matches("[A-Z0-9_]{3,40}")) throw new IllegalArgumentException("code: 3-40 of A-Z 0-9 _");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source (the requirement this template implements) is required");
        JsonNode d = validate(definition);
        Integer v = jdbc.queryForObject("SELECT COALESCE(MAX(version), 0) + 1 FROM fiu_report_templates WHERE code = ?", Integer.class, code);
        jdbc.update("INSERT INTO fiu_report_templates (code, report_type, version, status, output_format, definition, source, created_by, created_at) "
                        + "VALUES (?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?)", code, d.path("reportType").asText(), v, d.path("outputFormat").asText(),
                definition, source, by, Timestamp.valueOf(LocalDateTime.now()));
        audit.record(by, "FIU_TEMPLATE_DRAFTED", "fiu_template:" + code + "@" + v, source);
        return jdbc.queryForMap("SELECT id, code, report_type, version, status, output_format, source, created_by FROM fiu_report_templates WHERE code = ? AND version = ?", code, v);
    }

    /** A second compliance officer activates a DRAFT; the previously active template of that type is retired. */
    @Transactional
    public Map<String, Object> activateTemplate(long id, String by) {
        Map<String, Object> t = jdbc.queryForMap("SELECT * FROM fiu_report_templates WHERE id = ? FOR UPDATE", id);
        if (!"DRAFT".equals(t.get("status"))) throw new IllegalStateException("Template is " + t.get("status"));
        if (by.equals(t.get("created_by"))) throw new IllegalStateException("A second compliance officer must activate the template");
        jdbc.update("UPDATE fiu_report_templates SET status = 'RETIRED' WHERE report_type = ? AND status = 'ACTIVE'", t.get("report_type"));
        jdbc.update("UPDATE fiu_report_templates SET status = 'ACTIVE', approved_by = ?, activated_at = ? WHERE id = ?", by, Timestamp.valueOf(LocalDateTime.now()), id);
        audit.record(by, "FIU_TEMPLATE_ACTIVATED", "fiu_template:" + t.get("code") + "@" + t.get("version"), "created by " + t.get("created_by"));
        return jdbc.queryForMap("SELECT id, code, report_type, version, status, approved_by FROM fiu_report_templates WHERE id = ?", id);
    }

    public List<Map<String, Object>> templates() {
        return jdbc.queryForList("SELECT id, code, report_type, version, status, output_format, source, created_by, created_at, approved_by, activated_at, definition "
                + "FROM fiu_report_templates ORDER BY report_type, code, version DESC");
    }

    private Map<String, Object> activeTemplate(String type) {
        List<Map<String, Object>> t = jdbc.queryForList("SELECT * FROM fiu_report_templates WHERE report_type = ? AND status = 'ACTIVE' ORDER BY id DESC LIMIT 1", type);
        if (t.isEmpty()) throw new IllegalStateException("No active " + type + " template");
        return t.get(0);
    }

    // ─── reports ──────────────────────────────────────────────────────────────

    /** STR draft from a confirmed case, prepared in the name of the officer who proposed the conclusion. */
    @Transactional
    public long draftStr(long caseId, String preparedBy) {
        Map<String, Object> c = jdbc.queryForMap("SELECT * FROM risk_cases WHERE id = ?", caseId);
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("case", caseContext(c));
        ctx.put("subject", subjectContext(c));
        LocalDateTime suspicion = c.get("decided_at") instanceof Timestamp t ? t.toLocalDateTime() : LocalDateTime.now();
        long id = insert("STR", caseId, null, null, preparedBy, ctx, suspicion,
                calendar.addWorkingDays(suspicion, (int) params.number("STR.DEADLINE_WORKING_DAYS"), "ZW"));
        audit.record(preparedBy, "STR_DRAFTED", "fiu_report:" + id, "case " + c.get("case_ref"));
        return id;
    }

    @Transactional
    public long draftCtr(LocalDateTime from, LocalDateTime to, List<Map<String, Object>> lines, String preparedBy) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        Map<String, Object> rep = new LinkedHashMap<>();
        rep.put("period", Map.of("from", from.toString(), "to", to.toString()));
        rep.put("lines", lines);
        ctx.put("report", rep);
        long id = insert("CTR", null, from, to, preparedBy, ctx, null, null);
        audit.record(preparedBy, "CTR_DRAFTED", "fiu_report:" + id, lines.size() + " customer-days");
        return id;
    }

    private long insert(String type, Long caseId, LocalDateTime from, LocalDateTime to, String preparedBy, Map<String, Object> ctx,
                        LocalDateTime suspicion, LocalDateTime due) {
        Map<String, Object> t = activeTemplate(type);
        byte[] b = new byte[4];
        RANDOM.nextBytes(b);
        String ref = type + "-" + LocalDate.now().toString().replace("-", "") + "-" + HexFormat.of().formatHex(b).toUpperCase();
        Map<String, Object> report = new LinkedHashMap<>(ctx.containsKey("report") ? castMap(ctx.get("report")) : Map.of());
        report.put("ref", ref);
        ctx.put("report", report);
        ctx.put("officer", officerContext(preparedBy));
        Map<String, Object> fields = evaluate(t, ctx, Map.of());
        String fieldsJson = write(fields);
        jdbc.update("INSERT INTO fiu_reports (report_ref, report_type, status, case_id, period_from, period_to, format_version, payload, prepared_by, prepared_at, "
                        + "template_id, fields_json, suspicion_formed_at, due_at) VALUES (?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ref, type, caseId, from == null ? null : Timestamp.valueOf(from), to == null ? null : Timestamp.valueOf(to),
                t.get("code") + "@" + t.get("version"), write(ctx), preparedBy, Timestamp.valueOf(LocalDateTime.now()), t.get("id"), fieldsJson,
                suspicion == null ? null : Timestamp.valueOf(suspicion), due == null ? null : Timestamp.valueOf(due));
        long id = jdbc.queryForObject("SELECT id FROM fiu_reports WHERE report_ref = ?", Long.class, ref);
        refresh(id);
        return id;
    }

    /** The officer fills in manual fields (DRAFT only). */
    @Transactional
    public Map<String, Object> updateFields(long id, Map<String, Object> values, String by) {
        Map<String, Object> r = jdbc.queryForMap("SELECT * FROM fiu_reports WHERE id = ? FOR UPDATE", id);
        if (!"DRAFT".equals(r.get("status"))) throw new IllegalStateException("Only a DRAFT report can be edited");
        Map<String, Object> t = jdbc.queryForMap("SELECT * FROM fiu_report_templates WHERE id = ?", r.get("template_id"));
        Set<String> manual = manualFields(t);
        for (String k : values.keySet()) if (!manual.contains(k)) throw new IllegalArgumentException(k + " is filled automatically and cannot be edited");
        Map<String, Object> fields = castMap(readAny((String) r.get("fields_json")));
        fields.putAll(values);
        jdbc.update("UPDATE fiu_reports SET fields_json = ? WHERE id = ?", write(fields), id);
        audit.record(by, "FIU_REPORT_EDITED", "fiu_report:" + id, String.join(",", values.keySet()));
        return refresh(id);
    }

    /** Recomputes missing fields and the rendered output. */
    Map<String, Object> refresh(long id) {
        Map<String, Object> r = jdbc.queryForMap("SELECT * FROM fiu_reports WHERE id = ?", id);
        Map<String, Object> t = jdbc.queryForMap("SELECT * FROM fiu_report_templates WHERE id = ?", r.get("template_id"));
        Map<String, Object> fields = castMap(readAny((String) r.get("fields_json")));
        if ("DRAFT".equals(r.get("status"))) {
            // Parameter-sourced fields follow the current parameter (e.g. the goAML ID once registered).
            boolean changed = false;
            for (JsonNode sec : read((String) t.get("definition")).path("sections")) {
                for (JsonNode f : sec.path("fields")) {
                    String src = f.path("source").asText();
                    if (!src.startsWith("param:")) continue;
                    JsonNode p = params.get(src.substring(6));
                    Object v = p.isValueNode() ? p.asText() : readAny(p.toString());
                    if (!java.util.Objects.equals(v, fields.get(f.path("name").asText()))) { fields.put(f.path("name").asText(), v); changed = true; }
                }
            }
            if (changed) jdbc.update("UPDATE fiu_reports SET fields_json = ? WHERE id = ?", write(fields), id);
        }
        List<String> missing = missing(t, fields);
        String rendered = "GOAML_XML".equals(t.get("output_format")) ? GoamlXml.render(read((String) t.get("definition")), fields, (String) r.get("report_type"),
                params.get("FIU.REPORTING_ENTITY_ID").asText(), LocalDateTime.now()) : write(fields);
        jdbc.update("UPDATE fiu_reports SET missing_fields = ?, rendered = ? WHERE id = ?", missing.isEmpty() ? null : String.join(",", missing), rendered, id);
        return view(id);
    }

    @Transactional
    public Map<String, Object> approve(long id, String by) {
        Map<String, Object> r = jdbc.queryForMap("SELECT * FROM fiu_reports WHERE id = ? FOR UPDATE", id);
        if (!"DRAFT".equals(r.get("status"))) throw new IllegalStateException("Report is " + r.get("status"));
        if (by.equals(r.get("prepared_by"))) throw new IllegalStateException("The preparer cannot approve the report");
        if (!authorities.holds(by, "REPORT_APPROVER")) throw new IllegalStateException("Only a designated REPORT_APPROVER can approve reports to the FIU");
        if (r.get("missing_fields") != null) throw new IllegalStateException("Complete the report first; missing: " + r.get("missing_fields"));
        jdbc.update("UPDATE fiu_reports SET status = 'APPROVED', approved_by = ?, approved_at = ? WHERE id = ?", by, Timestamp.valueOf(LocalDateTime.now()), id);
        audit.record(by, "FIU_REPORT_APPROVED", "fiu_report:" + id, (String) r.get("report_ref"));
        return view(id);
    }

    @Transactional
    public Map<String, Object> markSubmitted(long id, String fiuReference, String by) {
        Map<String, Object> r = jdbc.queryForMap("SELECT * FROM fiu_reports WHERE id = ? FOR UPDATE", id);
        if (!"APPROVED".equals(r.get("status"))) throw new IllegalStateException("Only an APPROVED report can be marked submitted");
        if (fiuReference == null || fiuReference.isBlank()) throw new IllegalArgumentException("The FIU's (goAML) acknowledgement reference is required");
        jdbc.update("UPDATE fiu_reports SET status = 'SUBMITTED', submitted_by = ?, submitted_at = ?, fiu_reference = ? WHERE id = ?",
                by, Timestamp.valueOf(LocalDateTime.now()), fiuReference.trim(), id);
        audit.record(by, "FIU_REPORT_SUBMITTED", "fiu_report:" + id, fiuReference.trim());
        return view(id);
    }

    public Map<String, Object> view(long id) {
        Map<String, Object> m = new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM fiu_reports WHERE id = ?", id));
        m.put("fields", readAny((String) m.get("fields_json")));
        m.remove("fields_json");
        Object due = m.get("due_at");
        LocalDateTime d = due instanceof Timestamp t ? t.toLocalDateTime() : (LocalDateTime) due;
        m.put("deadline", d == null ? "NO_DEADLINE" : "SUBMITTED".equals(m.get("status")) ? "MET" : LocalDateTime.now().isAfter(d) ? "OVERDUE" : "OPEN");
        return m;
    }

    // ─── evaluation ───────────────────────────────────────────────────────────

    private Map<String, Object> evaluate(Map<String, Object> template, Map<String, Object> ctx, Map<String, Object> manual) {
        JsonNode def = read((String) template.get("definition"));
        Map<String, Object> out = new LinkedHashMap<>();
        for (JsonNode s : def.path("sections")) {
            for (JsonNode f : s.path("fields")) {
                String name = f.path("name").asText(), src = f.path("source").asText();
                Object v;
                if (src.startsWith("param:")) {
                    JsonNode p = params.get(src.substring(6));
                    v = p.isValueNode() ? p.asText() : readAny(p.toString());
                } else if (src.startsWith("const:")) {
                    v = src.substring(6);
                } else if ("manual".equals(src)) {
                    v = manual.get(name);
                } else {
                    String[] parts = src.split("\\.", 2);
                    Object holder = ctx.get(parts[0]);
                    v = holder instanceof Map<?, ?> hm ? hm.get(parts[1]) : null;
                }
                out.put(name, v);
            }
        }
        return out;
    }

    private List<String> missing(Map<String, Object> template, Map<String, Object> fields) {
        JsonNode def = read((String) template.get("definition"));
        List<String> out = new ArrayList<>();
        for (JsonNode s : def.path("sections")) {
            for (JsonNode f : s.path("fields")) {
                if (!f.path("required").asBoolean()) continue;
                Object v = fields.get(f.path("name").asText());
                boolean empty = v == null || (v instanceof String str && str.isBlank()) || (v instanceof List<?> l && l.isEmpty())
                        || (v instanceof JsonNode n && (n.isNull() || (n.isTextual() && n.asText().isBlank()) || (n.isContainerNode() && n.isEmpty())));
                if (!empty && f.has("minLength") && v instanceof String str && str.trim().length() < f.path("minLength").asInt()) empty = true;
                if (empty) out.add(f.path("name").asText());
            }
        }
        return out;
    }

    private Set<String> manualFields(Map<String, Object> template) {
        Set<String> out = new java.util.HashSet<>();
        for (JsonNode s : read((String) template.get("definition")).path("sections")) {
            for (JsonNode f : s.path("fields")) {
                if ("manual".equals(f.path("source").asText()) || f.path("overridable").asBoolean()) out.add(f.path("name").asText());
            }
        }
        return out;
    }

    private Map<String, Object> caseContext(Map<String, Object> c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("case_ref", c.get("case_ref"));
        m.put("subject_type", "MERCHANT".equals(c.get("subject_type")) ? "ENTITY" : "PERSON");
        m.put("subject_name", c.get("subject_name") == null ? "" : c.get("subject_name"));
        m.put("proposal_note", c.get("proposal_note"));
        long caseId = ((Number) c.get("id")).longValue();
        List<Map<String, Object>> alerts = new ArrayList<>();
        for (Map<String, Object> a : jdbc.queryForList("SELECT rule_code, severity, created_at, hits, transaction_reference, details FROM risk_alerts WHERE case_id = ? ORDER BY id", caseId)) {
            Map<String, Object> x = new LinkedHashMap<>(a);
            x.put("details", readAny(String.valueOf(a.get("details"))));
            alerts.add(x);
        }
        m.put("alerts", alerts);
        String key = String.valueOf(c.get("subject_key"));
        List<Map<String, Object>> txs = List.of();
        if (key.startsWith("PHONE:TEL:")) {
            txs = jdbc.queryForList("SELECT reference, created_at, amount_cents, currency, status, channel, payment_provider_code, recipient_identifier "
                    + "FROM transactions WHERE RIGHT(REPLACE(customer_phone, '+', ''), 9) = ? ORDER BY created_at DESC LIMIT 200", key.substring(10));
        } else if (key.startsWith("MERCHANT:")) {
            txs = jdbc.queryForList("SELECT reference, created_at, amount_cents, currency, status, payment_method_code FROM checkout_sessions WHERE merchant_id = ? "
                    + "ORDER BY created_at DESC LIMIT 200", Long.parseLong(key.substring(9)));
        }
        m.put("transactions", txs);
        Map<String, Long> totals = new LinkedHashMap<>();
        for (Map<String, Object> t : txs) totals.merge(String.valueOf(t.get("currency")), ((Number) t.get("amount_cents")).longValue(), Long::sum);
        m.put("transaction_totals", totals);
        m.put("related_reports", jdbc.queryForList("SELECT r.report_ref, r.report_type, r.status, r.fiu_reference FROM fiu_reports r JOIN risk_cases c ON c.id = r.case_id "
                + "WHERE c.subject_key = ? AND c.id <> ?", key, caseId));
        return m;
    }

    private Map<String, Object> subjectContext(Map<String, Object> c) {
        String key = String.valueOf(c.get("subject_key"));
        Map<String, Object> m = new LinkedHashMap<>();
        if (key.startsWith("PHONE:TEL:")) m.put("phone", "+263" + key.substring(10));
        return m;
    }

    private Map<String, Object> officerContext(String officer) {
        Map<String, Object> m = new LinkedHashMap<>();
        String phone = officer != null && officer.startsWith("staff:") ? officer.substring(6) : officer;
        List<Map<String, Object>> u = jdbc.queryForList("SELECT full_name, email, phone FROM users WHERE phone = ?", phone);
        if (!u.isEmpty()) m.putAll(u.get(0));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        if (o instanceof Map<?, ?> m) return new LinkedHashMap<>((Map<String, Object>) m);
        return new LinkedHashMap<>();
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Object readAny(String s) {
        try {
            return s == null ? null : json.readValue(s, Object.class);
        } catch (Exception e) {
            return s;
        }
    }

    private JsonNode read(String s) {
        try {
            return s == null ? json.nullNode() : json.readTree(s);
        } catch (Exception e) {
            return json.getNodeFactory().textNode(s);
        }
    }
}
