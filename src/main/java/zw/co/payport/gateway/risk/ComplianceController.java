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
    private static final com.fasterxml.jackson.databind.ObjectMapper PARAMS = new com.fasterxml.jackson.databind.ObjectMapper();

    public ComplianceController(ComplianceService compliance, ScreeningService screening, RiskEngine engine, JdbcTemplate jdbc) {
        this.compliance = compliance;
        this.screening = screening;
        this.engine = engine;
        this.jdbc = jdbc;
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
        return ok(jdbc.queryForList("SELECT id, approval_ref, subject_ref, payload, reason, status, requested_by, requested_at, decided_by, expires_at "
                + "FROM platform_approvals WHERE action_type = 'RISK_RULE_CHANGE' ORDER BY id DESC LIMIT 100"), req);
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
            int screened = 0;
            for (Map<String, Object> m : jdbc.queryForList("SELECT m.id, m.business_name, m.registration_number, "
                    + "(SELECT u.full_name FROM merchant_users u WHERE u.merchant_id = m.id AND u.role = 'OWNER' LIMIT 1) AS owner "
                    + "FROM merchants m WHERE m.status IN ('PENDING','UNDER_REVIEW','UPDATE_REQUIRED','ACTIVE')")) {
                engine.screenMerchant(((Number) m.get("id")).longValue(), (String) m.get("business_name"), (String) m.get("owner"), (String) m.get("registration_number"));
                screened++;
            }
            return Map.of("code", code, "version", version, "merchantsRescreened", screened);
        }, req);
    }

    public record ScreenBody(List<String> names, List<String> identifiers) {}

    /** Ad-hoc check (no alert): who on the lists matches these names/identifiers. */
    @PostMapping("/screening/check")
    public ResponseEntity<ApiResponse<List<ScreeningService.Match>>> check(@RequestBody ScreenBody b, HttpServletRequest req) {
        return ok(screening.screen("ADHOC:" + UUID.randomUUID(), b.names(), b.identifiers(), 0.8), req);
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
                + "approved_by, submitted_at, fiu_reference FROM fiu_reports ORDER BY id DESC LIMIT 200"), req);
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
