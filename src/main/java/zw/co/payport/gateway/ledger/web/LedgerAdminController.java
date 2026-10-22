package zw.co.payport.gateway.ledger.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.billing.TaxRateService;
import zw.co.payport.gateway.ledger.AccountKey;
import zw.co.payport.gateway.ledger.LedgerClient;
import zw.co.payport.gateway.ledger.LedgerException;
import zw.co.payport.gateway.ledger.LedgerJobs;
import zw.co.payport.gateway.ledger.shadow.ShadowComparison;
import zw.co.payport.gateway.ledger.shadow.ShadowLedgerSweeper;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.JobRunner;
import zw.co.payport.gateway.platform.PlatformException;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Finance and admin access to the Phase 1 ledger: balances, the shadow-vs-legacy comparison,
 * shadow failures, integrity, and four-eyes tax rates. Read-mostly; the only money-affecting
 * actions (tax rates) go through maker-checker.
 */
@RestController
@RequestMapping("/api/v1/admin/ledger")
@PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
public class LedgerAdminController {

    public record ProposeTaxRate(String entity, String taxCode, String currency, int rateBps, Long capMinor, boolean merchantBorne,
                                 LocalDate effectiveFrom, String notes) {}

    public record Decision(String note) {}

    public record ManualResolution(String idempotencyKey, String resolution) {}

    private final LedgerClient ledger;
    private final ShadowComparison comparison;
    private final ShadowLedgerSweeper sweeper;
    private final TaxRateService taxRates;
    private final JobRunner jobs;
    private final LedgerJobs ledgerJobs;

    public LedgerAdminController(LedgerClient ledger, ShadowComparison comparison, ShadowLedgerSweeper sweeper, TaxRateService taxRates,
                                 JobRunner jobs, LedgerJobs ledgerJobs) {
        this.ledger = ledger;
        this.comparison = comparison;
        this.sweeper = sweeper;
        this.taxRates = taxRates;
        this.jobs = jobs;
        this.ledgerJobs = ledgerJobs;
    }

    @GetMapping("/balance")
    public ResponseEntity<ApiResponse<LedgerClient.Balance>> balance(@RequestParam String account, HttpServletRequest req) {
        return ok(ledger.balance(AccountKey.parse(account)), req);
    }

    /** Shadow ledger vs legacy, per merchant and currency. Phase 1 exit evidence. */
    @GetMapping("/shadow/comparison")
    public ResponseEntity<ApiResponse<Map<String, Object>>> comparison(@RequestParam(defaultValue = "false") boolean differencesOnly,
                                                                       HttpServletRequest req) {
        List<ShadowComparison.Row> rows = comparison.compare();
        List<ShadowComparison.Row> diffs = rows.stream().filter(r -> !r.matches()).collect(Collectors.toList());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantCurrencyPairs", rows.size());
        body.put("matching", rows.size() - diffs.size());
        body.put("withDifferences", diffs.size());
        body.put("rows", differencesOnly ? diffs : rows);
        return ok(body, req);
    }

    @GetMapping("/shadow/failures")
    public ResponseEntity<ApiResponse<List<ShadowLedgerSweeper.Failure>>> failures(@RequestParam(defaultValue = "true") boolean openOnly,
                                                                                    @RequestParam(defaultValue = "200") int limit, HttpServletRequest req) {
        return ok(sweeper.failures(openOnly, limit), req);
    }

    @PostMapping("/shadow/failures/resolve")
    public ResponseEntity<ApiResponse<Boolean>> resolve(@RequestBody ManualResolution body, HttpServletRequest req) {
        if (body.resolution() == null || body.resolution().isBlank()) throw new PlatformException("RESOLUTION_REQUIRED", "explain why");
        return ok(sweeper.resolveManually(body.idempotencyKey(), body.resolution(), actor()), req);
    }

    @PostMapping("/shadow/run")
    public ResponseEntity<ApiResponse<JobRunner.RunRecord>> runShadow(HttpServletRequest req) {
        return ok(jobs.runExclusive(LedgerJobs.SHADOW_JOB, 900, () -> sweeper.runOnce().toString()), req);
    }

    /** Rescans gateway history from a date (idempotent: already-posted records are not posted again). */
    @PostMapping("/shadow/backfill")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<JobRunner.RunRecord>> backfill(@RequestParam LocalDate from, HttpServletRequest req) {
        return ok(jobs.runExclusive(LedgerJobs.SHADOW_JOB, 3_600, () -> {
            sweeper.resetWatermarks(from.atStartOfDay());
            return "backfill from " + from + " by " + actor() + ": " + sweeper.runOnce();
        }), req);
    }

    @GetMapping("/integrity")
    public ResponseEntity<ApiResponse<Map<String, Object>>> integrity(HttpServletRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        List<LedgerClient.Violation> v = ledger.integrityReport();
        body.put("healthy", v.isEmpty());
        body.put("violations", v);
        body.put("lastScheduledRun", jobs.lastRunSummary(LedgerJobs.INTEGRITY_JOB).orElse("never"));
        return ok(body, req);
    }

    @PostMapping("/integrity/run")
    public ResponseEntity<ApiResponse<JobRunner.RunRecord>> runIntegrity(HttpServletRequest req) {
        return ok(jobs.runExclusive(LedgerJobs.INTEGRITY_JOB, 3_600, ledgerJobs::sealAndCheckNow), req);
    }

    // ── Tax rates (four eyes) ───────────────────────────────────────────

    @GetMapping("/tax-rates")
    public ResponseEntity<ApiResponse<List<TaxRateService.TaxRate>>> taxRates(@RequestParam(defaultValue = "PAYPORT_ZW") String entity,
                                                                            HttpServletRequest req) {
        return ok(taxRates.all(entity), req);
    }

    @PostMapping("/tax-rates")
    @PreAuthorize("hasRole('FINANCE')")
    public ResponseEntity<ApiResponse<TaxRateService.TaxRate>> propose(@RequestBody ProposeTaxRate b, HttpServletRequest req) {
        return ok(taxRates.propose(b.entity() == null ? "PAYPORT_ZW" : b.entity(), b.taxCode(), b.currency(), b.rateBps(), b.capMinor(),
                b.merchantBorne(), b.effectiveFrom(), b.notes(), actor()), req);
    }

    @PostMapping("/tax-rates/{id}/approve")
    public ResponseEntity<ApiResponse<TaxRateService.TaxRate>> approve(@PathVariable long id, @RequestBody(required = false) Decision d,
                                                                      HttpServletRequest req) {
        return ok(taxRates.approve(id, actor(), roles(), d == null ? null : d.note()), req);
    }

    @PostMapping("/tax-rates/{id}/reject")
    public ResponseEntity<ApiResponse<TaxRateService.TaxRate>> reject(@PathVariable long id, @RequestBody(required = false) Decision d,
                                                                     HttpServletRequest req) {
        return ok(taxRates.reject(id, actor(), roles(), d == null ? null : d.note()), req);
    }

    // ── Errors ───────────────────────────────────────────────────────────

    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<ApiResponse<Object>> platform(PlatformException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.code(), e.getMessage(), requestId(req)));
    }

    @ExceptionHandler(LedgerException.class)
    public ResponseEntity<ApiResponse<Object>> ledger(LedgerException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.code(), e.getMessage(), requestId(req)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> badRequest(IllegalArgumentException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error("BAD_REQUEST", e.getMessage(), requestId(req)));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static <T> ResponseEntity<ApiResponse<T>> ok(T body, HttpServletRequest req) {
        return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
    }

    /** The authenticated staff member; never taken from the request body. */
    static String actor() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || a.getName() == null) throw new PlatformException("UNAUTHENTICATED", "no authenticated user");
        return a.getName();
    }

    static Set<String> roles() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null) return Set.of();
        return a.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .map(s -> s.startsWith("ROLE_") ? s.substring(5) : s).collect(Collectors.toSet());
    }

    private static String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
