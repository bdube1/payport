package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.entity.Transaction;
import zw.co.payport.gateway.model.request.*;
import zw.co.payport.gateway.model.response.*;
import zw.co.payport.gateway.repository.TransactionRepository;
import zw.co.payport.gateway.service.TransactionAccessService;
import zw.co.payport.gateway.service.TransactionAccessService.Access;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;

import java.util.Map;

/**
 * Transaction Controller
 *
 * POST /api/v1/transactions/initiate   - Create new transaction
 * POST /api/v1/transactions/pay        - Submit payment
 * GET  /api/v1/transactions/{ref}      - Get status
 * GET  /api/v1/transactions/{ref}/timeline - Get full audit log
 * GET  /api/v1/transactions/{ref}/outcome?waitSeconds=N - Final outcome for one-session channels (USSD)
 * GET  /api/v1/transactions/my-history     - Get logged-in user's transaction history
 *
 * Access (R3): /initiate returns a one-time accessToken. Send it back as the
 * X-Transaction-Token header on every later call for this reference to get the full
 * view. Without it (and without being the logged-in owner or staff) callers get the
 * redacted public view, or 404 once payport.security.transaction-token.enforce=true.
 * See TransactionAccessService.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionOrchestratorService orchestratorService;
    private final TransactionRepository transactionRepository;
    private final TransactionAccessService accessService;
    private final zw.co.payport.gateway.payments.TransactionOutcomeService outcomes;

    /**
     * Step 1: Initiate a transaction
     * Client generates the reference, we validate and record it.
     */
    @PostMapping("/initiate")
    public ResponseEntity<ApiResponse<TransactionResponse>> initiate(
            @Valid @RequestBody InitiateTransactionRequest request,
            HttpServletRequest httpRequest) {

        String requestId = getRequestId(httpRequest);
        // Record the reference on the request so that if anything downstream
        // throws, GlobalExceptionHandler can actually tell the customer which
        // transaction it was about, instead of "Reference: null".
        httpRequest.setAttribute("txReference", request.getReference());
        log.info("[API] POST /initiate ref={} product={}", request.getReference(), request.getProductTypeCode());

        TransactionResponse tx = orchestratorService.initiateTransaction(
                request, httpRequest.getRemoteAddr());
        tx.setAccessToken(accessService.issueToken(tx.getReference()));

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(tx, requestId));
    }

    /**
     * Step 2: Submit payment for an INITIATED transaction
     */
    @PostMapping("/pay")
    public ResponseEntity<ApiResponse<TransactionResponse>> pay(
            @Valid @RequestBody PayTransactionRequest request,
            @RequestParam(defaultValue = "0") int waitSeconds,
            HttpServletRequest httpRequest) throws Exception {

        String requestId = getRequestId(httpRequest);
        httpRequest.setAttribute("txReference", request.getReference());
        log.info("[API] POST /pay ref={} method={}", request.getReference(), request.getPaymentMethodCode());

        Access access = accessService.accessFor(request.getReference(), httpRequest.getHeader(TransactionAccessService.TOKEN_HEADER));
        if (access == Access.NONE) return notFound(request.getReference(), requestId);

        TransactionResponse tx = orchestratorService.submitPayment(request);
        if (waitSeconds > 0) {
            // One-session channels (USSD) can ask for the final outcome in the same call.
            outcomes.waitForFinal(request.getReference(), waitSeconds);
            TransactionResponse latest = orchestratorService.getTransaction(request.getReference());
            latest.setWidgetJsUrl(tx.getWidgetJsUrl());
            latest.setPaymentCorrelator(tx.getPaymentCorrelator());
            tx = latest;
        }

        return ResponseEntity.ok(ApiResponse.success(view(tx, access), requestId));
    }

    public record ConfirmOmariOtpRequest(String otp) {}

    /**
     * Second step of the Omari flow -- see TransactionOrchestratorService.confirmOmariOtp
     * for the full explanation of why this exists (Omari confirms via a
     * texted code the customer enters here, not a phone-side PIN push).
     */
    @PostMapping("/{reference}/confirm-omari-otp")
    public ResponseEntity<ApiResponse<TransactionResponse>> confirmOmariOtp(
            @PathVariable String reference,
            @RequestBody ConfirmOmariOtpRequest request,
            HttpServletRequest httpRequest) throws Exception {

        String requestId = getRequestId(httpRequest);
        httpRequest.setAttribute("txReference", reference);
        log.info("[API] POST /transactions/{}/confirm-omari-otp", reference);

        Access access = accessService.accessFor(reference, httpRequest.getHeader(TransactionAccessService.TOKEN_HEADER));
        if (access == Access.NONE) return notFound(reference, requestId);

        TransactionResponse tx = orchestratorService.confirmOmariOtp(reference, request.otp());

        return ResponseEntity.ok(ApiResponse.success(view(tx, access), requestId));
    }

    /**
     * Status lookup used by the paying customer's screen. Full view with the access
     * token (or as owner/staff); otherwise the redacted public view.
     */
    @GetMapping("/{reference}")
    public ResponseEntity<ApiResponse<TransactionResponse>> getStatus(
            @PathVariable String reference,
            HttpServletRequest httpRequest) {

        httpRequest.setAttribute("txReference", reference);
        log.info("[API] GET /transactions/{}", reference);
        String requestId = getRequestId(httpRequest);
        Access access = accessService.accessFor(reference, httpRequest.getHeader(TransactionAccessService.TOKEN_HEADER));
        if (access == Access.NONE) return notFound(reference, requestId);
        TransactionResponse tx = orchestratorService.getTransaction(reference);
        return ResponseEntity.ok(ApiResponse.success(view(tx, access), requestId));
    }

    /**
     * Full timeline / audit log for a transaction.
     * Same as getStatus but includes timeline.
     */
    @GetMapping("/{reference}/timeline")
    public ResponseEntity<ApiResponse<TransactionResponse>> getTimeline(
            @PathVariable String reference,
            HttpServletRequest httpRequest) {

        log.info("[API] GET /transactions/{}/timeline", reference);
        String requestId = getRequestId(httpRequest);
        Access access = accessService.accessFor(reference, httpRequest.getHeader(TransactionAccessService.TOKEN_HEADER));
        if (access == Access.NONE) return notFound(reference, requestId);
        TransactionResponse tx = orchestratorService.getTransaction(reference);
        return ResponseEntity.ok(ApiResponse.success(view(tx, access), requestId));
    }

    /**
     * Get transaction history for logged-in user
     * GET /api/v1/transactions/my-history
     */
    @GetMapping("/my-history")
    public ResponseEntity<ApiResponse<Map<String, Object>>> myHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest httpRequest) {

        String requestId = getRequestId(httpRequest);

        // Get authentication from SecurityContext
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null) {
            log.warn("Authentication is NULL");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "Login required to view history", requestId));
        }

        // Check if user is authenticated
        if (!auth.isAuthenticated()) {
            log.warn("Authentication is not authenticated");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "Login required to view history", requestId));
        }

        if ("anonymousUser".equals(auth.getPrincipal())) {
            log.warn("Principal is anonymousUser");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "Login required to view history", requestId));
        }

        // Get the phone number from the authenticated user
        String phone = auth.getName(); // The username/principal should be the phone number

        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by("createdAt").descending());
        Page<Transaction> txPage = transactionRepository.findByCustomerPhone(phone, pageable);


        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("content", txPage.getContent().stream().map(t -> {
            TransactionResponse r = TransactionResponse.from(t);
            var o = outcomes.of(t);
            r.setOutcome(o.outcome().name());
            r.setOutcomeMessage(o.message());
            r.setPaymentAttempts(o.attempts());
            return r;
        }).toList());
        result.put("totalElements", txPage.getTotalElements());
        result.put("totalPages", txPage.getTotalPages());
        result.put("page", page);

        return ResponseEntity.ok(ApiResponse.success(result, requestId));
    }

    /** Full view, or the redacted public view (R3), always with the customer-facing outcome. */
    private TransactionResponse view(TransactionResponse tx, Access access) {
        outcomes.enrich(tx);
        return access == Access.FULL ? tx : TransactionResponse.publicView(tx);
    }

    /**
     * The final answer for one-session channels (USSD): waits up to waitSeconds (max 180) for
     * SUCCEEDED, FAILED (with the provider's message), TIMED_OUT or NO_RESPONSE and returns
     * whatever the outcome is by then (PROCESSING if the payment window is still open).
     * Waiting never changes anything; the payment window is closed by the expiry job.
     */
    @GetMapping("/{reference}/outcome")
    public ResponseEntity<ApiResponse<TransactionResponse>> outcome(
            @PathVariable String reference,
            @RequestParam(defaultValue = "0") int waitSeconds,
            HttpServletRequest httpRequest) throws InterruptedException {
        String requestId = getRequestId(httpRequest);
        Access access = accessService.accessFor(reference, httpRequest.getHeader(TransactionAccessService.TOKEN_HEADER));
        if (access == Access.NONE) return notFound(reference, requestId);
        outcomes.waitForFinal(reference, waitSeconds);
        return ResponseEntity.ok(ApiResponse.success(view(orchestratorService.getTransaction(reference), access), requestId));
    }

    /** Same shape as an unknown reference, so a caller cannot tell "exists but forbidden" from "does not exist". */
    private static <T> ResponseEntity<ApiResponse<T>> notFound(String reference, String requestId) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("TRANSACTION_NOT_FOUND", "Transaction not found: " + reference, requestId));
    }

    /**
     * Extract request ID from header or generate new one
     */
    private String getRequestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : java.util.UUID.randomUUID().toString();
    }
}