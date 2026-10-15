package zw.co.payport.gateway.psp.recon;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.Api;
import zw.co.payport.gateway.psp.platform.approval.ApprovalRequest;

import java.util.List;
import java.util.Map;

/** Reconciliation: the breaks queue and trust-account statement matching. Nothing here edits balances directly. */
@RestController
@RequestMapping("/api/v1/psp/admin/recon")
@PreAuthorize("hasAnyRole('FINANCE','ADMIN','OPS')")
public class ReconAdminController {

    public record AssignBody(String assignee) {}
    public record ResolveBody(String resolution, String note) {}
    public record ProviderSettlementBody(String provider, long grossMinor, long feesMinor) {}
    public record ExplainBody(String matchedType, String matchedRef) {}

    private final ReconBreakService breaks;
    private final BankReconciliationService bank;

    public ReconAdminController(ReconBreakService breaks, BankReconciliationService bank) {
        this.breaks = breaks;
        this.bank = bank;
    }

    @GetMapping("/breaks")
    public ResponseEntity<ApiResponse<List<ReconBreak>>> open(HttpServletRequest req) {
        return Api.ok(breaks.open(), req);
    }

    @GetMapping("/breaks/summary")
    public ResponseEntity<ApiResponse<Map<String, Long>>> summary(HttpServletRequest req) {
        return Api.ok(breaks.openCountsByType(), req);
    }

    @GetMapping("/breaks/{ref}")
    public ResponseEntity<ApiResponse<ReconBreak>> get(@PathVariable String ref, HttpServletRequest req) {
        return Api.ok(breaks.get(ref), req);
    }

    @PostMapping("/breaks/{ref}/assign")
    public ResponseEntity<ApiResponse<ReconBreak>> assign(@PathVariable String ref, @RequestBody AssignBody b, HttpServletRequest req) {
        return Api.ok(breaks.assign(ref, b.assignee(), Actor.current()), req);
    }

    @PostMapping("/breaks/{ref}/resolve")
    public ResponseEntity<ApiResponse<ReconBreak>> resolve(@PathVariable String ref, @RequestBody ResolveBody b, HttpServletRequest req) {
        return Api.ok(breaks.resolveWithoutAdjustment(ref, b.resolution(), b.note(), Actor.current()), req);
    }

    @PostMapping("/breaks/{ref}/adjustment-requests")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN')")
    public ResponseEntity<ApiResponse<ApprovalRequest>> adjust(@PathVariable String ref, @RequestBody ReconBreakService.AdjustmentRequest b,
                                                               HttpServletRequest req) {
        return Api.created(breaks.proposeAdjustment(ref, b, Actor.current()), req);
    }

    @PostMapping(value = "/bank-statements", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN')")
    public ResponseEntity<ApiResponse<BankReconciliationService.ImportResult>> importStatement(@RequestParam String cashAccount,
                                                                                               @RequestParam String currency,
                                                                                               @RequestParam("file") MultipartFile file,
                                                                                               HttpServletRequest req) throws Exception {
        return Api.created(bank.importCsv(cashAccount, currency, file.getBytes(), Actor.current()), req);
    }

    @GetMapping("/bank-statements/open-lines")
    public ResponseEntity<ApiResponse<List<BankStatementLine>>> openLines(HttpServletRequest req) {
        return Api.ok(bank.openLines(), req);
    }

    @PostMapping("/bank-statements/lines/{id}/provider-settlement")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN')")
    public ResponseEntity<ApiResponse<BankStatementLine>> providerSettlement(@PathVariable Long id, @RequestBody ProviderSettlementBody b,
                                                                            HttpServletRequest req) {
        return Api.ok(bank.recordProviderSettlement(id, b.provider(), b.grossMinor(), b.feesMinor(), Actor.current()), req);
    }

    @PostMapping("/bank-statements/lines/{id}/explain")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN')")
    public ResponseEntity<ApiResponse<BankStatementLine>> explain(@PathVariable Long id, @RequestBody ExplainBody b, HttpServletRequest req) {
        return Api.ok(bank.markExplained(id, b.matchedType(), b.matchedRef(), Actor.current()), req);
    }
}
