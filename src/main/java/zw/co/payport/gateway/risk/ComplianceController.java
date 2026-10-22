package zw.co.payport.gateway.risk;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.PlatformException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Compliance console API. COMPLIANCE role only: operations, finance and even ADMIN cannot read
 * alerts, cases or FIU reports (confidentiality; no tipping off). Every write is attributed.
 */
@RestController
@RequestMapping("/api/v1/admin/compliance")
@PreAuthorize("hasRole('COMPLIANCE')")
public class ComplianceController {

    private final ComplianceService compliance;
    private final ScreeningService screening;
    private final RiskEngine engine;
    private final JdbcTemplate jdbc;
    private final ComplianceParameters parameters;
    private final ComplianceAudit audit;
    private final ComplianceAuthorities authorities;
    @org.springframework.beans.factory.annotation.Autowired
    private UnSanctionsList unList;
    @org.springframework.beans.factory.annotation.Autowired
    private FiuReportService reportService;
    private static final com.fasterxml.jackson.databind.ObjectMapper PARAMS = new com.fasterxml.jackson.databind.ObjectMapper();

    public ComplianceController(ComplianceService compliance, ScreeningService screening, RiskEngine engine, JdbcTemplate jdbc,
                                ComplianceParameters parameters, ComplianceAudit audit, ComplianceAuthorities authorities) {
        this.compliance = compliance;
        this.screening = screening;
        this.engine = engine;
        this.jdbc = jdbc;
        this.parameters = parameters;
        this.audit = audit;
        this.authorities = authorities;
    }

    // ─── parameters (thresholds, deadlines, screening, calendars) ─────────────

    @GetMapping("/parameters")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> parameters(HttpServletRequest req) {
        return ok(parameters.all(), req);
    }

    public record ParameterChangeBody(Object value, boolean confirm, String source, String reason) {}

    /** Change a value and/or confirm it (CONFIRMED needs this two-person flow). */
    @PostMapping("/parameters/{key}/change-requests")
    public ResponseEntity<ApiResponse<Map<String, Object>>> requestParameterChange(@PathVariable String key, @RequestBody ParameterChangeBody b, HttpServletRequest req) {
        return run(() -> parameters.requestChange(key, b.value() == null ? null : PARAMS.valueToTree(b.value()), b.confirm(), b.source(), b.reason(), officer()), req);
    }

    @PostMapping("/parameter-changes/{approvalId}/decide")
    public ResponseEntity<ApiResponse<Map<String, Object>>> decideParameterChange(@PathVariable long approvalId, @RequestBody DecisionBody b, HttpServletRequest req) {
        return run(() -> parameters.decide(approvalId, b.approve(), b.note(), officer()), req);
    }

    public record RuleCreateBody(String code, String name, String type, Map<String, Object> params, String action, String severity, String source, String reason) {}

    /** Propose a new rule. Approved rules start in OBSERVE; enforcing is a separate approved change. */
    @PostMapping("/rules")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createRule(@RequestBody RuleCreateBody b, HttpServletRequest req) {
        return run(() -> compliance.requestRuleCreate(b.code(), b.name(), b.type(), b.params() == null ? null : PARAMS.valueToTree(b.params()),
                b.action(), b.severity(), b.source(), b.reason(), officer()), req);
    }

    // ─── audit and authorities ────────────────────────────────────────────────

    @GetMapping("/audit/verify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyAudit(HttpServletRequest req) {
        return ok(audit.verify(), req);
    }

    @GetMapping("/audit")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> auditTrail(@RequestParam String subject, HttpServletRequest req) {
        return ok(audit.trail(subject), req);
    }

    @PostMapping("/authority-requests/{approvalId}/decide")
    public ResponseEntity<ApiResponse<Map<String, Object>>> decideAuthority(@PathVariable long approvalId, @RequestBody DecisionBody b, HttpServletRequest req) {
        return run(() -> authorities.decide(approvalId, b.approve(), b.note(), officer()), req);
    }

    @GetMapping("/authorities")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> authorityList(HttpServletRequest req) {
        return ok(authorities.list(), req);
    }

    // ─── rules ────────────────────────────────────────────────────────────────

    @GetMapping("/rules")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> rules(HttpServletRequest req) {
        return ok(jdbc.queryForList("SELECT * FROM risk_rules ORDER BY code"), req);
    }

    public record RuleChangeBody(String mode, String action, Map<String, Object> params, String reason) {}

    @PostMapping("/rules/{code}/change-requests")
    public ResponseEntity<ApiResponse<Map<String, Object>>> requestChange(@PathVariable String code, @RequestBody RuleChangeBody b, HttpServletRequest req) {
        return run(() -> compliance.requestRuleChange(code, b.mode(), b.action(), b.params() == null ? null : PARAMS.valueToTree(b.params()), b.reason(), officer()), req);
    }

    public record DecisionBody(boolean approve, String note) {}

    @PostMapping("/rule-changes/{approvalId}/decide")
    public ResponseEntity<ApiResponse<Map<String, Object>>> decideChange(@PathVariable long approvalId, @RequestBody DecisionBody b, HttpServletRequest req) {
        return run(() -> compliance.decideRuleChange(approvalId, b.approve(), b.note(), officer()), req);
    }

    @GetMapping("/rule-changes")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> pendingChanges(HttpServletRequest req) {
        // Every compliance change that needs a second officer: rule changes and creations,
        // parameter changes and authority grants (each is decided on its own endpoint).
        return ok(jdbc.queryForList("SELECT id, action_type, approval_ref, subject_ref, payload, reason, status, requested_by, requested_at, decided_by, expires_at "
                + "FROM platform_approvals WHERE action_type IN ('RISK_RULE_CHANGE','RISK_RULE_CREATE','COMPLIANCE_PARAMETER_CHANGE','COMPLIANCE_AUTHORITY_GRANT') "
                + "ORDER BY id DESC LIMIT 100"), req);
    }

    // ─── screening lists ──────────────────────────────────────────────────────

    @GetMapping("/screening/lists")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> lists(HttpServletRequest req) {
        return ok(screening.lists(), req);
    }

    public record ListBody(String code, String name, String source) {}

    @PostMapping("/screening/lists")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createList(@RequestBody ListBody b, HttpServletRequest req) {
        return run(() -> Map.of("id", screening.createList(b.code(), b.name(), b.source(), officer())), req);
    }

    public record LoadBody(List<ScreeningService.Entry> entries) {}

    /** Loads a new version of the list, then re-screens merchants under review or active against it. */
    @PostMapping("/screening/lists/{code}/load")
    public ResponseEntity<ApiResponse<Map<String, Object>>> load(@PathVariable String code, @RequestBody LoadBody b, HttpServletRequest req) {
        return run(() -> {
            int version = screening.load(code, b.entries(), officer());
            audit.record(officer(), "SCREENING_LIST_LOADED", "screening_list:" + code, "version " + version + ", " + b.entries().size() + " entries");
            Map<String, Integer> r = engine.rescreenAll("RESCREEN_" + code + "_V" + version);
            return Map.of("code", code, "version", version, "merchantsRescreened", r.get("merchants"), "customersRescreened", r.get("customers"),
                    "potentialMatches", r.get("potentialMatches"));
        }, req);
    }

    public record ScreenBody(List<String> names, List<String> identifiers) {}

    /** Ad-hoc check (no alert): who on the lists matches these names/identifiers. */
    @PostMapping("/screening/check")
    public ResponseEntity<ApiResponse<ScreeningService.Result>> check(@RequestBody ScreenBody b, HttpServletRequest req) {
        // The whole result: an INCOMPLETE screening (lists unavailable or outdated) must never look like "no matches".
        return ok(screening.screen("ADHOC:" + officer(), "ADHOC", b.names(), b.identifiers(), parameters.number("SCREENING.MIN_SCORE")), req);
    }

    @GetMapping("/screening/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> screeningStatus(HttpServletRequest req) {
        String reason = screening.incompleteReason();
        return ok(Map.of("complete", reason == null, "reason", reason == null ? "" : reason, "lists", screening.lists(), "unFetches", unList.fetches()), req);
    }

    /** Fetch the UN list now (normally hourly). */
    @PostMapping("/screening/lists/UN-CONSOLIDATED/refresh")
    public ResponseEntity<ApiResponse<UnSanctionsList.Fetched>> refreshUn(HttpServletRequest req) {
        return ok(unList.refresh(officer()), req);
    }

    @GetMapping("/screening/runs")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> runs(@RequestParam String subjectKey, HttpServletRequest req) {
        return ok(screening.runs(subjectKey), req);
    }

    // ─── alerts and cases ─────────────────────────────────────────────────────

    @GetMapping("/alerts")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> alerts(@RequestParam(defaultValue = "OPEN") String status, HttpServletRequest req) {
        return ok(jdbc.queryForList("SELECT * FROM risk_alerts WHERE status = ? ORDER BY FIELD(severity,'HIGH','MEDIUM','LOW'), created_at LIMIT 500",
                status.toUpperCase()), req);
    }

    public record CaseFromAlerts(List<Long> alertIds) {}

    @PostMapping("/cases")
    public ResponseEntity<ApiResponse<Map<String, Object>>> openCase(@RequestBody CaseFromAlerts b, HttpServletRequest req) {
        return run(() -> compliance.openCaseFromAlerts(b.alertIds(), officer()), req);
    }

    @GetMapping("/cases")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> cases(@RequestParam(required = false) String status, HttpServletRequest req) {
        return ok(status == null
                ? jdbc.queryForList("SELECT * FROM risk_cases WHERE status NOT LIKE 'CLOSED%' ORDER BY sla_due_at")
                : jdbc.queryForList("SELECT * FROM risk_cases WHERE status = ? ORDER BY sla_due_at", status.toUpperCase()), req);
    }

    @GetMapping("/cases/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> caseDetail(@PathVariable long id, HttpServletRequest req) {
        return run(() -> compliance.caseDetail(id), req);
    }

    public record AssignBody(String assignee) {}

    @PostMapping("/cases/{id}/assign")
    public ResponseEntity<ApiResponse<Map<String, Object>>> assign(@PathVariable long id, @RequestBody AssignBody b, HttpServletRequest req) {
        return run(() -> compliance.assign(id, b.assignee() == null ? officer() : b.assignee(), officer()), req);
    }

    public record NoteBody(String note) {}

    @PostMapping("/cases/{id}/notes")
    public ResponseEntity<ApiResponse<Map<String, Object>>> note(@PathVariable long id, @RequestBody NoteBody b, HttpServletRequest req) {
        return run(() -> { compliance.note(id, b.note(), officer()); return compliance.caseDetail(id); }, req);
    }

    public record ProposeBody(String outcome, String rationale) {}

    @PostMapping("/cases/{id}/propose")
    public ResponseEntity<ApiResponse<Map<String, Object>>> propose(@PathVariable long id, @RequestBody ProposeBody b, HttpServletRequest req) {
        return run(() -> compliance.propose(id, b.outcome(), b.rationale(), officer()), req);
    }

    public record CaseDecisionBody(boolean accept, String note) {}

    @PostMapping("/cases/{id}/decide")
    public ResponseEntity<ApiResponse<Map<String, Object>>> decide(@PathVariable long id, @RequestBody CaseDecisionBody b, HttpServletRequest req) {
        return run(() -> compliance.decide(id, b.accept(), b.note(), officer()), req);
    }

    // ─── FIU reports ──────────────────────────────────────────────────────────

    @GetMapping("/reports")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> reports(HttpServletRequest req) {
        return ok(jdbc.queryForList("SELECT id, report_ref, report_type, status, case_id, period_from, period_to, format_version, prepared_by, "
                + "approved_by, submitted_at, fiu_reference, missing_fields, due_at FROM fiu_reports ORDER BY id DESC LIMIT 200"), req);
    }

    /** The officer completes the manual fields of a DRAFT. */
    @PatchMapping("/reports/{id}/fields")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reportFields(@PathVariable long id, @RequestBody Map<String, Object> values, HttpServletRequest req) {
        return run(() -> reportService.updateFields(id, values, officer()), req);
    }

    @GetMapping("/report-templates")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> templates(HttpServletRequest req) {
        return ok(reportService.templates(), req);
    }

    public record TemplateBody(String code, Map<String, Object> definition, String source) {}

    @PostMapping("/report-templates")
    public ResponseEntity<ApiResponse<Map<String, Object>>> newTemplate(@RequestBody TemplateBody b, HttpServletRequest req) {
        return run(() -> {
            try {
                return reportService.createTemplateVersion(b.code(), PARAMS.writeValueAsString(b.definition()), b.source(), officer());
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalArgumentException("definition must be JSON");
            }
        }, req);
    }

    @PostMapping("/report-templates/{id}/activate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> activateTemplate(@PathVariable long id, HttpServletRequest req) {
        return run(() -> reportService.activateTemplate(id, officer()), req);
    }

    @GetMapping("/reports/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> report(@PathVariable long id, HttpServletRequest req) {
        return run(() -> compliance.report(id), req);
    }

    @PostMapping("/reports/ctr")
    public ResponseEntity<ApiResponse<Map<String, Object>>> ctr(@RequestParam LocalDate from, @RequestParam LocalDate to, HttpServletRequest req) {
        return run(() -> compliance.prepareCtr(from.atStartOfDay(), to.plusDays(1).atStartOfDay(), officer()), req);
    }

    @PostMapping("/reports/{id}/approve")
    public ResponseEntity<ApiResponse<Map<String, Object>>> approve(@PathVariable long id, HttpServletRequest req) {
        return run(() -> compliance.approveReport(id, officer()), req);
    }

    public record SubmittedBody(String fiuReference) {}

    @PostMapping("/reports/{id}/submitted")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitted(@PathVariable long id, @RequestBody SubmittedBody b, HttpServletRequest req) {
        return run(() -> compliance.markSubmitted(id, b.fiuReference(), officer()), req);
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    static String officer() {
        return "staff:" + SecurityContextHolder.getContext().getAuthentication().getName();
    }

    private static <T> ResponseEntity<ApiResponse<T>> run(Supplier<T> s, HttpServletRequest req) {
        try {
            return ok(s.get(), req);
        } catch (PlatformException e) {
            int status = "APPROVAL_NOT_FOUND".equals(e.code()) ? 404 : 409;
            return ResponseEntity.status(status).body(ApiResponse.error(e.code(), e.getMessage(), rid(req)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(ApiResponse.error("COMPLIANCE_CONFLICT", e.getMessage(), rid(req)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("COMPLIANCE_INVALID", e.getMessage(), rid(req)));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return ResponseEntity.status(404).body(ApiResponse.error("NOT_FOUND", "Not found", rid(req)));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return ResponseEntity.status(409).body(ApiResponse.error("DUPLICATE", "Already exists", rid(req)));
        }
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T body, HttpServletRequest req) {
        return ResponseEntity.ok(ApiResponse.success(body, rid(req)));
    }

    private static String rid(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
