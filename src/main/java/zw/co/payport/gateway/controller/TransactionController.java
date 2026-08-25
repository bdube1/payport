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
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;

import java.util.Map;

/**
 * Transaction Controller
 *
 * POST /api/v1/transactions/initiate   - Create new transaction
 * POST /api/v1/transactions/pay        - Submit payment
 * GET  /api/v1/transactions/{ref}      - Get status (public)
 * GET  /api/v1/transactions/{ref}/timeline - Get full audit log (public)
 * GET  /api/v1/transactions/my-history     - Get logged-in user's transaction history
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionOrchestratorService orchestratorService;
    private final TransactionRepository transactionRepository;

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
            HttpServletRequest httpRequest) throws Exception {

        String requestId = getRequestId(httpRequest);
        httpRequest.setAttribute("txReference", request.getReference());
        log.info("[API] POST /pay ref={} method={}", request.getReference(), request.getPaymentMethodCode());

        TransactionResponse tx = orchestratorService.submitPayment(request);

        return ResponseEntity.ok(ApiResponse.success(tx, requestId));
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

        TransactionResponse tx = orchestratorService.confirmOmariOtp(reference, request.otp());

        return ResponseEntity.ok(ApiResponse.success(tx, requestId));
    }

    /**
     * Public status lookup - no auth required.
     * Used by customer to check their transaction status.
     */
    @GetMapping("/{reference}")
    public ResponseEntity<ApiResponse<TransactionResponse>> getStatus(
            @PathVariable String reference,
            HttpServletRequest httpRequest) {

        httpRequest.setAttribute("txReference", reference);
        log.info("[API] GET /transactions/{}", reference);
        TransactionResponse tx = orchestratorService.getTransaction(reference);
        return ResponseEntity.ok(ApiResponse.success(tx, getRequestId(httpRequest)));
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
        TransactionResponse tx = orchestratorService.getTransaction(reference);
        return ResponseEntity.ok(ApiResponse.success(tx, getRequestId(httpRequest)));
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

        // DEBUG: Log all headers
        log.info("===== DEBUG: /my-history request =====");
        log.info("Request ID: {}", requestId);
        log.info("Remote IP: {}", httpRequest.getRemoteAddr());

        // Log all headers
        java.util.Enumeration<String> headerNames = httpRequest.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            String headerValue = httpRequest.getHeader(headerName);
            if (headerName.toLowerCase().contains("authorization")) {
                log.info("Header: {} = {}", headerName, "Bearer [REDACTED]");
            } else {
                log.info("Header: {} = {}", headerName, headerValue);
            }
        }

        // Get authentication from SecurityContext
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        log.info("Authentication object: {}", auth);

        if (auth == null) {
            log.warn("Authentication is NULL");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "Login required to view history", requestId));
        }

        log.info("Authentication - isAuthenticated: {}", auth.isAuthenticated());
        log.info("Authentication - principal: {}", auth.getPrincipal());
        log.info("Authentication - principal class: {}", auth.getPrincipal().getClass().getName());
        log.info("Authentication - name: {}", auth.getName());
        log.info("Authentication - authorities: {}", auth.getAuthorities());
        log.info("Authentication - details: {}", auth.getDetails());

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
        log.info("Successfully authenticated user with phone: {}", phone);

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Transaction> txPage = transactionRepository.findByCustomerPhone(phone, pageable);

        log.info("Found {} transactions for user {}", txPage.getContent().size(), phone);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("content", txPage.getContent().stream().map(TransactionResponse::from).toList());
        result.put("totalElements", txPage.getTotalElements());
        result.put("totalPages", txPage.getTotalPages());
        result.put("page", page);

        return ResponseEntity.ok(ApiResponse.success(result, requestId));
    }

    /**
     * Extract request ID from header or generate new one
     */
    private String getRequestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : java.util.UUID.randomUUID().toString();
    }
}