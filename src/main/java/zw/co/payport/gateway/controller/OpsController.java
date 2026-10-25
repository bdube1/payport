package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.entity.Transaction;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.model.response.TransactionResponse;
import zw.co.payport.gateway.repository.*;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OPS','ADMIN','FINANCE','COMPLIANCE')")
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class OpsController {

    private final TransactionRepository transactionRepository;
    private final TransactionAuditLogRepository auditLogRepository;
    private final ProviderHealthRepository providerHealthRepository;
    private final TransactionOrchestratorService orchestratorService;
    private final zw.co.payport.gateway.repository.UserRepository userRepository;

    // ─── DASHBOARD KPIs ───────────────────────────────────────────────────────

    @GetMapping("/dashboard/kpis")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getKpis(HttpServletRequest req) {
        LocalDateTime startOfToday = LocalDate.now().atStartOfDay();
        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);

        long totalToday = transactionRepository.countByStatusAndCreatedAtAfter(TransactionStatus.COMPLETED, startOfToday)
                + transactionRepository.countByStatusAndCreatedAtAfter(TransactionStatus.PAYMENT_FAILED, startOfToday)
                + transactionRepository.countByStatusAndCreatedAtAfter(TransactionStatus.FULFILMENT_FAILED, startOfToday)
                + transactionRepository.countByStatusAndCreatedAtAfter(TransactionStatus.PAYMENT_PENDING, startOfToday)
                + transactionRepository.countByStatusAndCreatedAtAfter(TransactionStatus.INITIATED, startOfToday);

        long completedToday = transactionRepository.countByStatusAndCreatedAtAfter(TransactionStatus.COMPLETED, startOfToday);
        Long revenueToday = transactionRepository.sumAmountCentsSince(startOfToday);
        long pendingPayment = transactionRepository.countByStatus(TransactionStatus.PAYMENT_PENDING);
        long fulfilmentFailed = transactionRepository.countByStatus(TransactionStatus.FULFILMENT_FAILED);
        long paymentFailed = transactionRepository.countByStatusAndCreatedAtAfter(TransactionStatus.PAYMENT_FAILED, startOfToday);
        long completedLastHour = transactionRepository.countCompletedSince(oneHourAgo);
        long totalLastHour = transactionRepository.countByStatusAndCreatedAtAfter(TransactionStatus.COMPLETED, oneHourAgo)
                + transactionRepository.countByStatusAndCreatedAtAfter(TransactionStatus.PAYMENT_FAILED, oneHourAgo);

        double successRate = totalLastHour > 0 ? (completedLastHour * 100.0 / totalLastHour) : 100.0;

        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("totalTransactionsToday", totalToday);
        kpis.put("totalRevenueToday", revenueToday != null ? revenueToday : 0L);
        kpis.put("completedToday", completedToday);
        kpis.put("pendingPayment", pendingPayment);
        kpis.put("needAttention", fulfilmentFailed);
        kpis.put("failedToday", paymentFailed);
        kpis.put("successRate1h", Math.round(successRate * 10) / 10.0);
        kpis.put("generatedAt", LocalDateTime.now().toString());

        return ResponseEntity.ok(ApiResponse.success(kpis, requestId(req)));
    }

    // ─── ATTENTION QUEUE ──────────────────────────────────────────────────────

    @GetMapping("/dashboard/attention-queue")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAttentionQueue(HttpServletRequest req) {
        List<TransactionStatus> attentionStatuses = List.of(
                TransactionStatus.FULFILMENT_FAILED,
                TransactionStatus.MANUAL_FULFILMENT,
                TransactionStatus.PAYMENT_TIMEOUT
        );

        // Only recent ones -- was every matching transaction ever,
        // unbounded, meaning something from weeks ago that nobody ever
        // actioned would sit here forever, permanently burying anything
        // actually recent and worth a look. Retrying fulfilment on
        // something this old isn't meaningful action anyway; refunding it
        // still might be, which is why refund stays available past this
        // window on the transaction detail page itself -- this only
        // controls what surfaces in the queue, not what's still possible.
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);

        List<Map<String, Object>> queue = transactionRepository.findByStatusIn(attentionStatuses)
                .stream()
                .filter(tx -> tx.getInitiatedAt() != null && tx.getInitiatedAt().isAfter(cutoff))
                .sorted(Comparator.comparing(Transaction::getCreatedAt).reversed())
                .limit(50)
                .map(tx -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("reference", tx.getReference());
                    item.put("status", tx.getStatus().name());
                    item.put("amountCents", tx.getAmountCents());
                    item.put("amountFormatted", tx.getAmountFormatted());
                    item.put("customerPhone", tx.getCustomerPhone());
                    item.put("recipientIdentifier", tx.getRecipientIdentifier());
                    item.put("productName", tx.getProductType() != null ? tx.getProductType().getName() : null);
                    item.put("retryCount", tx.getRetryCount());
                    item.put("failureReason", tx.getFailureReason());
                    item.put("createdAt", tx.getCreatedAt());
                    item.put("priority", tx.getRetryCount() > 2 ? "CRITICAL" : tx.getRetryCount() > 0 ? "HIGH" : "MEDIUM");
                    return item;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(queue, requestId(req)));
    }

    // ─── TRANSACTION SEARCH (Enhanced) ────────────────────────────────────────

    @GetMapping("/transactions-search")
    public ResponseEntity<ApiResponse<Map<String, Object>>> searchTransactions(
            @RequestParam(required = false) String reference,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String customerPhone,
            @RequestParam(required = false) String recipientIdentifier,
            @RequestParam(required = false) String productTypeCode,
            @RequestParam(required = false) String providerReference,
            @RequestParam(required = false) String paymentCorrelator,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(defaultValue = "false") boolean payportOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest req) {

        TransactionStatus statusEnum = null;
        if (status != null && !status.isBlank()) {
            try { statusEnum = TransactionStatus.valueOf(status.toUpperCase()); } catch (Exception ignored) {}
        }

        LocalDateTime from = null, to = null;
        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        try { if (dateFrom != null && !dateFrom.isBlank()) from = LocalDateTime.parse(dateFrom, fmt); } catch (Exception ignored) {}
        try { if (dateTo != null && !dateTo.isBlank()) to = LocalDateTime.parse(dateTo, fmt); } catch (Exception ignored) {}

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        // Payport-only view: Payport's own airtime/data/bills business,
        // with merchant checkout activity explicitly excluded -- lets ops
        // see their own core business cleanly, independent of whether any
        // merchants are even active on the platform.
        Page<Transaction> txPage = payportOnly
                ? transactionRepository.searchPayportOnlyTransactions(
                        nullIfBlank(reference), statusEnum,
                        nullIfBlank(customerPhone), nullIfBlank(recipientIdentifier),
                        nullIfBlank(productTypeCode), from, to, pageable)
                : transactionRepository.searchTransactions(
                        nullIfBlank(reference), statusEnum,
                        nullIfBlank(customerPhone), nullIfBlank(recipientIdentifier),
                nullIfBlank(productTypeCode), nullIfBlank(providerReference),
                nullIfBlank(paymentCorrelator), from, to, pageable);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", txPage.getContent().stream().map(this::txSummary).collect(Collectors.toList()));
        result.put("totalElements", txPage.getTotalElements());
        result.put("totalPages", txPage.getTotalPages());
        result.put("page", page);
        result.put("size", size);

        return ResponseEntity.ok(ApiResponse.success(result, requestId(req)));
    }

    // ─── CUSTOMER STATEMENT ───────────────────────────────────────────────────

    @GetMapping("/customer-statement")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCustomerStatement(
            @RequestParam String phone,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            HttpServletRequest req) {

        // Normalize phone
        String normalizedPhone = normalizePhone(phone);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Transaction> txPage = transactionRepository.findByCustomerPhone(normalizedPhone, pageable);

        // Summary stats
        List<Transaction> all = txPage.getContent();
        long totalTx = txPage.getTotalElements();
        long completed = all.stream().filter(t -> t.getStatus() == TransactionStatus.COMPLETED).count();
        long totalSpentCents = all.stream().filter(t -> t.getStatus() == TransactionStatus.COMPLETED)
                .mapToLong(Transaction::getAmountCents).sum();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phone", normalizedPhone);
        userRepository.findByPhone(normalizedPhone).ifPresent(u -> {
            result.put("customerName", u.getFullName());
            result.put("idNumber", u.getIdNumber());
            result.put("registeredAt", u.getRegisteredAt());
        });
        result.put("generatedAt", LocalDateTime.now().toString());
        result.put("totalTransactions", totalTx);
        result.put("completedTransactions", completed);
        result.put("totalSpentCents", totalSpentCents);
        result.put("totalSpentFormatted", String.format("USD %.2f", totalSpentCents / 100.0));
        result.put("transactions", all.stream().map(this::txSummary).collect(Collectors.toList()));
        result.put("totalPages", txPage.getTotalPages());
        result.put("page", page);

        return ResponseEntity.ok(ApiResponse.success(result, requestId(req)));
    }

    // ─── MANUAL ACTIONS ───────────────────────────────────────────────────────

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OPS','ADMIN','FINANCE')")
    @PostMapping("/transactions/{ref}/retry-fulfilment")
    public ResponseEntity<ApiResponse<TransactionResponse>> retryFulfilment(
            @PathVariable String ref,
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest req) {
        String operator = zw.co.payport.gateway.platform.Web.staff();   // Phase 9: from the login, never from the request body
        TransactionResponse tx = orchestratorService.manualRetryFulfilment(ref, operator);
        return ResponseEntity.ok(ApiResponse.success(tx, requestId(req)));
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OPS','ADMIN','FINANCE')")
    @PostMapping("/transactions/{ref}/refund")
    public ResponseEntity<ApiResponse<TransactionResponse>> refund(
            @PathVariable String ref,
            @RequestBody Map<String, String> body,
            HttpServletRequest req) {
        String reason = body.getOrDefault("reason", "Ops-initiated refund");
        String operator = zw.co.payport.gateway.platform.Web.staff();   // Phase 9: from the login, never from the request body
        TransactionResponse tx = orchestratorService.initiateRefund(ref, operator, reason);
        return ResponseEntity.ok(ApiResponse.success(tx, requestId(req)));
    }

    /**
     * Actually completes the refund -- see
     * TransactionOrchestratorService.completeManualRefund for why this
     * exists as its own endpoint (the /refund endpoint above only ever
     * parks a transaction at REFUND_PENDING; nothing previously finished
     * it). Ops supplies proof of the manual payout they already made
     * outside the system.
     */
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OPS','ADMIN','FINANCE')")
    @PostMapping("/transactions/{ref}/complete-refund")
    public ResponseEntity<ApiResponse<TransactionResponse>> completeRefund(
            @PathVariable String ref,
            @RequestBody Map<String, String> body,
            HttpServletRequest req) {
        String refundReference = body.get("refundReference");
        String refundMethod = body.get("refundMethod");
        String reason = body.get("reason");
        String operator = zw.co.payport.gateway.platform.Web.staff();   // Phase 9: from the login, never from the request body
        TransactionResponse tx = orchestratorService.completeManualRefund(ref, operator, refundReference, refundMethod, reason);
        return ResponseEntity.ok(ApiResponse.success(tx, requestId(req)));
    }

    // ─── PROVIDER HEALTH ──────────────────────────────────────────────────────

    @GetMapping("/provider-health")
    public ResponseEntity<ApiResponse<Object>> getProviderHealth(HttpServletRequest req) {
        return ResponseEntity.ok(ApiResponse.success(providerHealthRepository.findAll(), requestId(req)));
    }

    // ─── HELPERS ──────────────────────────────────────────────────────────────

    private Map<String, Object> txSummary(Transaction tx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reference", tx.getReference());
        m.put("status", tx.getStatus().name());
        m.put("channel", tx.getChannel() != null ? tx.getChannel().name() : null);
        m.put("customerPhone", tx.getCustomerPhone());
        m.put("recipientIdentifier", tx.getRecipientIdentifier());
        m.put("amountCents", tx.getAmountCents());
        m.put("amountFormatted", tx.getAmountFormatted());
        m.put("currency", tx.getCurrency());
        m.put("productName", tx.getProductType() != null ? tx.getProductType().getName() : null);
        m.put("productTypeCode", tx.getProductType() != null ? tx.getProductType().getCode() : null);
        m.put("paymentMethodCode", tx.getPaymentMethod() != null ? tx.getPaymentMethod().getCode() : null);
        m.put("paymentMethodName", tx.getPaymentMethodName());
        m.put("providerReference", tx.getProviderReference());
        m.put("paymentCorrelator", tx.getPaymentCorrelator());
        m.put("retryCount", tx.getRetryCount());
        m.put("failureReason", tx.getFailureReason());
        m.put("initiatedAt", tx.getInitiatedAt());
        m.put("completedAt", tx.getCompletedAt());
        m.put("createdAt", tx.getCreatedAt());
        m.put("recipientNetwork", tx.getRecipientNetwork());
        m.put("customerName", tx.getCustomerName());
        return m;
    }

    // ─── EXPORT TRANSACTIONS (CSV) ────────────────────────────────────────────

    @GetMapping("/transactions-export/csv")
    public ResponseEntity<byte[]> exportTransactionsCsv(
            @RequestParam(required = false) String customerPhone,
            @RequestParam(required = false) String recipientIdentifier,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String productTypeCode,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            HttpServletRequest req) {

        TransactionStatus statusEnum = null;
        if (status != null && !status.isBlank()) {
            try { statusEnum = TransactionStatus.valueOf(status.toUpperCase()); } catch (Exception ignored) {}
        }
        LocalDateTime from = null, to = null;
        try { if (dateFrom != null && !dateFrom.isBlank()) from = LocalDateTime.parse(dateFrom, DateTimeFormatter.ISO_LOCAL_DATE_TIME); } catch (Exception ignored) {}
        try { if (dateTo != null && !dateTo.isBlank()) to = LocalDateTime.parse(dateTo, DateTimeFormatter.ISO_LOCAL_DATE_TIME); } catch (Exception ignored) {}

        Pageable pageable = PageRequest.of(0, 5000, Sort.by("createdAt").descending());
        Page<Transaction> txPage = transactionRepository.searchTransactions(
                null, statusEnum, nullIfBlank(customerPhone), nullIfBlank(recipientIdentifier),
                nullIfBlank(productTypeCode), null, null, from, to, pageable);

        StringBuilder csv = new StringBuilder();
        csv.append("Reference,Status,Product,CustomerPhone,RecipientPhone,AmountUSD,PaymentMethod,ProviderRef,InitiatedAt,CompletedAt\n");
        for (Transaction tx : txPage.getContent()) {
            csv.append(String.join(",",
                quote(tx.getReference()),
                quote(tx.getStatus().name()),
                quote(tx.getProductType() != null ? tx.getProductType().getName() : ""),
                quote(tx.getCustomerPhone()),
                quote(tx.getRecipientIdentifier()),
                quote(String.format("%.2f", tx.getAmountCents() / 100.0)),
                quote(tx.getPaymentMethod() != null ? tx.getPaymentMethod().getCode() : ""),
                quote(tx.getProviderReference()),
                quote(tx.getInitiatedAt() != null ? tx.getInitiatedAt().toString() : ""),
                quote(tx.getCompletedAt() != null ? tx.getCompletedAt().toString() : "")
            )).append("\n");
        }

        byte[] bytes = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payport-transactions.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(bytes);
    }

    // ─── USER TRANSACTION HISTORY ─────────────────────────────────────────────

    @GetMapping("/user-history")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getUserHistory(
            @RequestParam String phone,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest req) {
        String normalizedPhone = normalizePhone(phone);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Transaction> txPage = transactionRepository.findByCustomerPhone(normalizedPhone, pageable);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transactions", txPage.getContent().stream().map(this::txSummary).toList());
        result.put("totalElements", txPage.getTotalElements());
        result.put("totalPages", txPage.getTotalPages());
        result.put("page", page);
        return ResponseEntity.ok(ApiResponse.success(result, requestId(req)));
    }

    private String quote(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private String normalizePhone(String phone) {
        if (phone == null) return null;
        String p = phone.replaceAll("\\s+", "").replace("-", "");
        if (p.startsWith("+263")) return p;
        if (p.startsWith("263")) return "+" + p;
        if (p.startsWith("0")) return "+263" + p.substring(1);
        return p;
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
