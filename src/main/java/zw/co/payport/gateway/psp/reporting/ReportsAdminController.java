package zw.co.payport.gateway.psp.reporting;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.Api;
import zw.co.payport.gateway.psp.disputes.CustomerRefund;
import zw.co.payport.gateway.psp.disputes.DisputeCase;
import zw.co.payport.gateway.psp.disputes.DisputesAndRefundsService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

/** Regulatory / tax returns (with four-eyes review) and the refunds & disputes desk. */
@RestController
@RequestMapping("/api/v1/psp/admin")
@PreAuthorize("hasAnyRole('FINANCE','COMPLIANCE','ADMIN')")
public class ReportsAdminController {

    public record GenerateBody(String reportType, LocalDateTime from, LocalDateTime to) {}
    public record SubmitBody(String submissionRef) {}
    public record PayRefundBody(String rail, String cashAccount, String payoutReference) {}

    private final ReportingService reporting;
    private final DisputesAndRefundsService disputes;

    public ReportsAdminController(ReportingService reporting, DisputesAndRefundsService disputes) {
        this.reporting = reporting;
        this.disputes = disputes;
    }

    @PostMapping("/reports")
    public ResponseEntity<ApiResponse<RegulatoryReport>> generate(@RequestBody GenerateBody b, HttpServletRequest req) {
        return Api.created(reporting.generate(b.reportType(), b.from(), b.to(), Actor.current()), req);
    }

    @GetMapping("/reports")
    public ResponseEntity<ApiResponse<List<RegulatoryReport>>> list(HttpServletRequest req) {
        return Api.ok(reporting.recent(), req);
    }

    @GetMapping("/reports/{ref}")
    public ResponseEntity<ApiResponse<RegulatoryReport>> get(@PathVariable String ref, HttpServletRequest req) {
        return Api.ok(reporting.get(ref), req);
    }

    @GetMapping("/reports/{ref}/csv")
    public ResponseEntity<byte[]> csv(@PathVariable String ref) {
        RegulatoryReport r = reporting.get(ref);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + r.getReportType().toLowerCase() + "-" + ref + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(r.getCsv().getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/reports/{ref}/review")
    public ResponseEntity<ApiResponse<RegulatoryReport>> review(@PathVariable String ref, HttpServletRequest req) {
        return Api.ok(reporting.review(ref, Actor.current()), req);
    }

    @PostMapping("/reports/{ref}/submitted")
    public ResponseEntity<ApiResponse<RegulatoryReport>> submitted(@PathVariable String ref, @RequestBody SubmitBody b, HttpServletRequest req) {
        return Api.ok(reporting.markSubmitted(ref, b.submissionRef(), Actor.current()), req);
    }

    // ── Refunds & disputes desk ──────────────────────────────────────────

    @GetMapping("/refunds/outstanding")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN','OPS')")
    public ResponseEntity<ApiResponse<List<CustomerRefund>>> outstanding(HttpServletRequest req) {
        return Api.ok(disputes.outstandingRefunds(), req);
    }

    @PostMapping("/refunds/{ref}/pay")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN')")
    public ResponseEntity<ApiResponse<CustomerRefund>> pay(@PathVariable String ref, @RequestBody PayRefundBody b, HttpServletRequest req) {
        return Api.ok(disputes.payRefund(ref, b.rail(), b.cashAccount(), b.payoutReference(), Actor.current()), req);
    }

    @GetMapping("/disputes/overdue")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN','OPS')")
    public ResponseEntity<ApiResponse<List<DisputeCase>>> overdue(HttpServletRequest req) {
        return Api.ok(disputes.overdueDisputes(), req);
    }
}
