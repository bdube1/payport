package zw.co.payport.gateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import zw.co.payport.gateway.entity.ReconciliationMatch;
import zw.co.payport.gateway.entity.ReconciliationRun;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.repository.ReconciliationMatchRepository;
import zw.co.payport.gateway.repository.ReconciliationRunRepository;
import zw.co.payport.gateway.service.reconciliation.ProviderStatementLine;
import zw.co.payport.gateway.service.reconciliation.StatementReconciliationService;
import zw.co.payport.gateway.service.reconciliation.StatementParser;
import zw.co.payport.gateway.service.reconciliation.parsers.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ops-only: upload a provider's settlement/statement export (Excel or
 * PDF), get back a matched/mismatched/provider-only/payport-only
 * reconciliation report. Mounted at /statements specifically to sit
 * alongside the existing internal-consistency ReconciliationController
 * at /api/v1/admin/reconciliation (CheckoutSession vs Transaction
 * agreement) without colliding with it -- this is a different, external
 * check: does what actually happened at the provider's end agree with
 * what Payport itself recorded.
 *
 * See StatementReconciliationService for the matching logic and each
 * parser class for exactly what's been confirmed about that specific
 * provider's file format and reference fields.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/reconciliation/statements")
@RequiredArgsConstructor
public class StatementReconciliationController {

    private final StatementReconciliationService reconciliationService;
    private final ReconciliationRunRepository runRepository;
    private final ReconciliationMatchRepository matchRepository;

    private final EcoCashStatementParser ecoCashParser;
    private final OneMoneyStatementParser oneMoneyParser;
    private final NetOneStatementParser netOneParser;
    private final TelecelStatementParser telecelParser;
    private final InnbucksStatementParser innbucksParser;
    private final OmariStatementParser omariParser;

    @PreAuthorize("hasAnyRole('OPS', 'ADMIN', 'FINANCE')")
    @PostMapping("/upload")
    public ResponseEntity<ApiResponse<Map<String, Object>>> upload(
            @RequestParam String provider,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest httpRequest) throws Exception {

        String requestId = getRequestId(httpRequest);
        String uploadedBy = currentUsername();
        log.info("[RECONCILIATION] Statement upload received: provider={}, file={}, by={}", provider, file.getOriginalFilename(), uploadedBy);

        StatementParser parser;
        try {
            parser = resolveParser(provider);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorResponse("UNKNOWN_PROVIDER", e.getMessage()));
        }

        List<ProviderStatementLine> parsedLines;
        try {
            parsedLines = parser.parse(file.getInputStream());
        } catch (StatementParser.StatementParseException e) {
            log.error("[RECONCILIATION] Parse failed for provider={}: {}", provider, e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse("PARSE_FAILED",
                    "Could not read this file as a " + provider + " statement: " + e.getMessage()));
        }

        if (parsedLines.isEmpty()) {
            log.warn("[RECONCILIATION] Parser found zero transaction lines in {} for provider={} -- check the file is the right format/sheet", file.getOriginalFilename(), provider);
        }

        ReconciliationRun run = reconciliationService.reconcile(provider, file.getOriginalFilename(), uploadedBy, parsedLines);

        Map<String, Object> data = Map.of(
                "runId", run.getId(),
                "provider", run.getProvider(),
                "totalProviderLines", run.getTotalProviderLines(),
                "matchedCount", run.getMatchedCount(),
                "amountMismatchCount", run.getAmountMismatchCount(),
                "providerOnlyCount", run.getProviderOnlyCount(),
                "payportOnlyCount", run.getPayportOnlyCount(),
                "periodStart", String.valueOf(run.getPeriodStart()),
                "periodEnd", String.valueOf(run.getPeriodEnd())
        );
        return ResponseEntity.ok(ApiResponse.success(data, requestId));
    }

    @PreAuthorize("hasAnyRole('OPS', 'ADMIN', 'FINANCE')")
    @GetMapping("/runs")
    public ResponseEntity<ApiResponse<List<ReconciliationRun>>> listRuns(HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success(runRepository.findAllByOrderByUploadedAtDesc(), getRequestId(httpRequest)));
    }

    @PreAuthorize("hasAnyRole('OPS', 'ADMIN', 'FINANCE')")
    @GetMapping("/runs/{id}/matches")
    public ResponseEntity<ApiResponse<List<ReconciliationMatch>>> getMatches(
            @PathVariable Long id,
            @RequestParam(required = false) String type,
            HttpServletRequest httpRequest) {

        List<ReconciliationMatch> matches = (type != null && !type.isBlank())
                ? matchRepository.findByRunIdAndMatchType(id, type)
                : matchRepository.findByRunId(id);

        return ResponseEntity.ok(ApiResponse.success(matches, getRequestId(httpRequest)));
    }

    private StatementParser resolveParser(String provider) {
        if (provider == null) throw new IllegalArgumentException("provider is required");
        return switch (provider.toUpperCase()) {
            case "ECOCASH" -> ecoCashParser;
            case "ONEMONEY" -> oneMoneyParser;
            case "NETONE" -> netOneParser;
            case "TELECEL" -> telecelParser;
            case "INNBUCKS" -> innbucksParser;
            case "OMARI" -> omariParser;
            default -> throw new IllegalArgumentException("Unknown provider: " + provider);
        };
    }

    private ApiResponse<Map<String, Object>> errorResponse(String code, String message) {
        return ApiResponse.<Map<String, Object>>builder()
                .success(false)
                .message(message)
                .error(ApiResponse.ErrorDetail.builder().code(code).message(message).build())
                .build();
    }

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.isAuthenticated()) ? auth.getName() : "unknown";
    }

    private String getRequestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
