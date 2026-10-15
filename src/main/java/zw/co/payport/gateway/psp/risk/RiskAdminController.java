package zw.co.payport.gateway.psp.risk;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.Api;
import zw.co.payport.gateway.psp.common.RiskPorts;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Fraud cases, blocklists, rule tuning, sanctions lists and AML alerts. */
@RestController
@RequestMapping("/api/v1/psp/admin/risk")
@PreAuthorize("hasAnyRole('COMPLIANCE','ADMIN')")
public class RiskAdminController {

    public record AssignBody(String assignee) {}
    public record CloseBody(String outcome, String note, boolean blockSubject) {}
    public record BlockBody(String entryType, String value, String reason, LocalDateTime expiresAt) {}
    public record RuleBody(Integer thresholdCount, Long thresholdAmountUsdMinor, Integer windowMinutes, Integer score, String action, Boolean enabled) {}
    public record DecisionBody(String decision, String note) {}
    public record ScreenBody(String subjectType, String subjectRef, String fullName, String dateOfBirth, String nationality) {}

    private final RiskCaseService cases;
    private final AmlScreeningService aml;
    private final TransactionMonitoringService monitoring;

    public RiskAdminController(RiskCaseService cases, AmlScreeningService aml, TransactionMonitoringService monitoring) {
        this.cases = cases;
        this.aml = aml;
        this.monitoring = monitoring;
    }

    @GetMapping("/cases")
    @PreAuthorize("hasAnyRole('COMPLIANCE','ADMIN','OPS')")
    public ResponseEntity<ApiResponse<List<RiskCase>>> openCases(HttpServletRequest req) {
        return Api.ok(cases.open(), req);
    }

    @PostMapping("/cases/{ref}/assign")
    @PreAuthorize("hasAnyRole('COMPLIANCE','ADMIN','OPS')")
    public ResponseEntity<ApiResponse<RiskCase>> assign(@PathVariable String ref, @RequestBody AssignBody b, HttpServletRequest req) {
        return Api.ok(cases.assign(ref, b.assignee(), Actor.current()), req);
    }

    @PostMapping("/cases/{ref}/close")
    public ResponseEntity<ApiResponse<RiskCase>> close(@PathVariable String ref, @RequestBody CloseBody b, HttpServletRequest req) {
        return Api.ok(cases.close(ref, b.outcome(), b.note(), b.blockSubject(), Actor.current()), req);
    }

    @GetMapping("/blocklist")
    public ResponseEntity<ApiResponse<List<BlocklistEntry>>> blocklist(HttpServletRequest req) {
        return Api.ok(cases.blocklist(), req);
    }

    @PostMapping("/blocklist")
    @PreAuthorize("hasAnyRole('COMPLIANCE','ADMIN','OPS')")
    public ResponseEntity<ApiResponse<BlocklistEntry>> block(@RequestBody BlockBody b, HttpServletRequest req) {
        return Api.created(cases.addToBlocklist(b.entryType(), b.value(), b.reason(), b.expiresAt(), Actor.current()), req);
    }

    @DeleteMapping("/blocklist/{id}")
    public ResponseEntity<ApiResponse<String>> unblock(@PathVariable Long id, @RequestParam String reason, HttpServletRequest req) {
        cases.removeFromBlocklist(id, reason, Actor.current());
        return Api.ok("removed", req);
    }

    @GetMapping("/rules")
    public ResponseEntity<ApiResponse<List<RiskRule>>> rules(HttpServletRequest req) {
        return Api.ok(cases.rules(), req);
    }

    @PatchMapping("/rules/{code}")
    public ResponseEntity<ApiResponse<RiskRule>> rule(@PathVariable String code, @RequestBody RuleBody b, HttpServletRequest req) {
        return Api.ok(cases.updateRule(code, b.thresholdCount(), b.thresholdAmountUsdMinor(), b.windowMinutes(), b.score(), b.action(),
                b.enabled(), Actor.current()), req);
    }

    // ── AML ──────────────────────────────────────────────────────────────

    @GetMapping("/aml/alerts")
    public ResponseEntity<ApiResponse<List<AmlAlert>>> alerts(HttpServletRequest req) {
        return Api.ok(aml.openAlerts(), req);
    }

    @PostMapping("/aml/alerts/{ref}/decision")
    public ResponseEntity<ApiResponse<AmlAlert>> decide(@PathVariable String ref, @RequestBody DecisionBody b, HttpServletRequest req) {
        return Api.ok(aml.decide(ref, b.decision(), b.note(), Actor.current()), req);
    }

    @PostMapping("/aml/screen")
    public ResponseEntity<ApiResponse<RiskPorts.ScreeningOutcome>> screen(@RequestBody ScreenBody b, HttpServletRequest req) {
        return Api.ok(aml.screen(b.subjectType(), b.subjectRef(), b.fullName(), b.dateOfBirth(), b.nationality()), req);
    }

    @GetMapping("/aml/lists")
    public ResponseEntity<ApiResponse<List<SanctionsListVersion>>> lists(HttpServletRequest req) {
        return Api.ok(aml.listVersions(), req);
    }

    @PostMapping(value = "/aml/lists/{listCode}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Integer>> upload(@PathVariable String listCode, @RequestParam("file") MultipartFile file,
                                                       HttpServletRequest req) throws Exception {
        return Api.created(aml.importList(listCode.toUpperCase(), file.getBytes(), Actor.current()), req);
    }

    @PostMapping("/aml/lists/refresh")
    public ResponseEntity<ApiResponse<String>> refresh(HttpServletRequest req) {
        aml.refreshLists();
        return Api.ok("refreshed", req);
    }

    @PostMapping("/aml/monitoring/run")
    public ResponseEntity<ApiResponse<Integer>> runMonitoring(@RequestParam String date, HttpServletRequest req) {
        return Api.ok(monitoring.run(LocalDate.parse(date)), req);
    }
}
