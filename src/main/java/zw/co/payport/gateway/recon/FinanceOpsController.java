package zw.co.payport.gateway.recon;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.settlement.SettlementBatchService;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 3 finance operations: statement reconciliation, exceptions, late payments and
 * settlement batches. FINANCE or ADMIN only; the acting person is always the authenticated
 * user, never a value from the request.
 */
@RestController
@RequestMapping("/api/v1/admin/finance")
@PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
public class FinanceOpsController {

    private final ReconciliationService recon;
    private final ReconExceptionStore exceptions;
    private final SettlementBatchService settlement;

    public FinanceOpsController(ReconciliationService recon, ReconExceptionStore exceptions,
                                SettlementBatchService settlement) {
        this.recon = recon;
        this.exceptions = exceptions;
        this.settlement = settlement;
    }

    // ─── reconciliation ───────────────────────────────────────────────────────

    /** Imports a provider statement (once per file) and reconciles it. format=CSV for Payport's generic statement CSV. */
    @PostMapping("/recon/statements")
    public ResponseEntity<ApiResponse<Map<String, Object>>> importStatement(@RequestParam String provider,
                                                                            @RequestParam(defaultValue = "NATIVE") String format,
                                                                            @RequestParam("file") MultipartFile file,
                                                                            HttpServletRequest req) throws Exception {
        byte[] bytes = file.getBytes();
        var lines = recon.parse(provider, format, bytes);
        var imported = recon.importStatement(provider, file.getOriginalFilename() == null ? "statement" : file.getOriginalFilename(), bytes, lines, actor());
        var run = recon.run(imported.statementId(), actor());
        return ok(Map.of("statement", imported, "run", run), req);
    }

    @PostMapping("/recon/statements/{id}/run")
    public ResponseEntity<ApiResponse<ReconciliationService.RunSummary>> rerun(@PathVariable long id, HttpServletRequest req) {
        return ok(recon.run(id, actor()), req);
    }

    @GetMapping("/recon/exceptions")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> openExceptions(@RequestParam(required = false) String category,
                                                                                  HttpServletRequest req) {
        return ok(exceptions.open(category, 500), req);
    }

    public record Resolve(String resolution, String note) {}

    @PostMapping("/recon/exceptions/{id}/resolve")
    public ResponseEntity<ApiResponse<Integer>> resolve(@PathVariable long id, @RequestBody Resolve body, HttpServletRequest req) {
        return ok(exceptions.resolveById(id, body.resolution(), actor(), body.note()), req);
    }

    // ─── settlement ───────────────────────────────────────────────────────────

    @GetMapping("/settlement/eligible")
    public ResponseEntity<ApiResponse<List<SettlementBatchService.EligibleSession>>> eligible(@RequestParam String currency,
                                                                                            HttpServletRequest req) {
        return ok(settlement.eligible(currency.toUpperCase(), LocalDateTime.now()), req);
    }

    public record DraftRequest(String currency) {}

    @PostMapping("/settlement/batches")
    public ResponseEntity<ApiResponse<Object>> draft(@RequestBody DraftRequest body, HttpServletRequest req) {
        SettlementBatchService.Batch b = settlement.draft(body.currency(), actor());
        return ok(b == null ? Map.of("message", "Nothing eligible") : b, req);
    }

    @GetMapping("/settlement/batches/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> batch(@PathVariable long id, HttpServletRequest req) {
        return ok(Map.of("batch", settlement.get(id), "items", settlement.items(id), "legacyComparison", settlement.compareWithLegacy(id)), req);
    }

    @PostMapping("/settlement/batches/{id}/approve")
    public ResponseEntity<ApiResponse<SettlementBatchService.Batch>> approve(@PathVariable long id, HttpServletRequest req) {
        return ok(settlement.approve(id, actor()), req);
    }

    @PostMapping("/settlement/batches/{id}/cancel")
    public ResponseEntity<ApiResponse<SettlementBatchService.Batch>> cancel(@PathVariable long id, HttpServletRequest req) {
        return ok(settlement.cancel(id, actor()), req);
    }

    @PostMapping("/settlement/batches/{id}/instruct")
    public ResponseEntity<ApiResponse<SettlementBatchService.PayoutFile>> instruct(@PathVariable long id, HttpServletRequest req) {
        return ok(settlement.instruct(id, actor()), req);
    }

    /** Bank confirmation/return file: instruction_ref,status,bank_reference,reason (status PAID, RETURNED or FAILED). */
    @PostMapping("/settlement/payout-results")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> payoutResults(@RequestParam("file") MultipartFile file, HttpServletRequest req)
            throws Exception {
        byte[] bytes = file.getBytes();
        List<SettlementBatchService.PayoutResult> results = parsePayoutResults(bytes);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return ok(settlement.applyPayoutResults(results, file.getOriginalFilename(), sha, actor()), req);
    }

    static List<SettlementBatchService.PayoutResult> parsePayoutResults(byte[] bytes) throws Exception {
        List<SettlementBatchService.PayoutResult> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] c = line.split(",", -1);
                if (first && c[0].trim().equalsIgnoreCase("instruction_ref")) { first = false; continue; }
                first = false;
                out.add(new SettlementBatchService.PayoutResult(c[0].trim(), c.length > 1 ? c[1].trim() : "",
                        c.length > 2 && !c[2].isBlank() ? c[2].trim() : null, c.length > 3 ? c[3].trim() : null));
            }
        }
        return out;
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static String actor() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || a.getName() == null) throw new IllegalStateException("no authenticated user");
        return a.getName();
    }

    private static Set<String> roles() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null) return Set.of();
        return a.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .map(s -> s.startsWith("ROLE_") ? s.substring(5) : s).collect(Collectors.toSet());
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T body, HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return ResponseEntity.ok(ApiResponse.success(body, id != null ? id : UUID.randomUUID().toString()));
    }
}
