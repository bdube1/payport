package zw.co.payport.gateway.merchant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.repository.UserRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Staff side of the merchant platform: KYB cases (four-eyes), settlement destination
 * verification, market enablements and the work queues, all without database access.
 */
@RestController
@RequestMapping("/api/v1/admin/platform")
@PreAuthorize("hasAnyRole('OPS','ADMIN','FINANCE')")
public class AdminPlatformController {

    private final MerchantPlatformService platform;
    private final JdbcTemplate jdbc;
    private final UserRepository users;

    public AdminPlatformController(MerchantPlatformService platform, JdbcTemplate jdbc, UserRepository users) {
        this.platform = platform;
        this.jdbc = jdbc;
        this.users = users;
    }

    // ─── KYB ──────────────────────────────────────────────────────────────────

    @GetMapping("/kyb/{merchantId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> kyb(@PathVariable long merchantId, HttpServletRequest req) {
        Map<String, Object> m = new LinkedHashMap<>(platform.kybCase(merchantId));
        m.put("requiredChecklist", MerchantPlatformService.KYB_CHECKLIST);
        return ok(m, req);
    }

    @PostMapping("/kyb/{merchantId}/assign")
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> assign(@PathVariable long merchantId, HttpServletRequest req) {
        return ok(platform.assign(merchantId, staff()), req);
    }

    public record RecommendBody(String recommendation, Map<String, Boolean> checklist, String note) {}

    @PostMapping("/kyb/{merchantId}/recommend")
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> recommend(@PathVariable long merchantId, @RequestBody RecommendBody b, HttpServletRequest req) {
        try {
            return ok(platform.recommend(merchantId, b.recommendation(), b.checklist(), b.note(), staff()), req);
        } catch (RuntimeException e) {
            return bad("KYB_RECOMMEND_FAILED", e, req);
        }
    }

    public record DecideBody(boolean accept, String note) {}

    /** The second person. Accepting an APPROVE recommendation activates the merchant and returns its first test key (once). */
    @PostMapping("/kyb/{merchantId}/decide")
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> decide(@PathVariable long merchantId, @RequestBody DecideBody b, HttpServletRequest req) {
        try {
            MerchantPlatformService.Decision d = platform.decide(merchantId, b.accept(), b.note(), staff(), staffUserId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kybCase", d.kybCase());
            if (d.approval() != null) {
                m.put("merchantStatus", d.approval().merchant().getStatus());
                m.put("testApiKey", Map.of("publicKey", d.approval().testKeyPair().publicKey(), "secretKey", d.approval().testKeyPair().secretKey(),
                        "warning", "This secret key is shown once. Hand it to the merchant securely or let them create their own in the dashboard."));
            }
            return ok(m, req);
        } catch (RuntimeException e) {
            return bad("KYB_DECIDE_FAILED", e, req);
        }
    }

    // ─── settlement destinations ──────────────────────────────────────────────

    @GetMapping("/settlement-destinations")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> pendingDestinations(@RequestParam(defaultValue = "PENDING_VERIFICATION") String status,
                                                                                      HttpServletRequest req) {
        return ok(jdbc.queryForList("SELECT d.*, m.business_name FROM merchant_settlement_destinations d JOIN merchants m ON m.id = d.merchant_id "
                + "WHERE d.status = ? ORDER BY d.id", status.toUpperCase()), req);
    }

    public record VerifyBody(boolean approve, String note) {}

    @PostMapping("/settlement-destinations/{id}/verify")
    @PreAuthorize("hasAnyRole('OPS','ADMIN','FINANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verify(@PathVariable long id, @RequestBody VerifyBody b, HttpServletRequest req) {
        try {
            return ok(platform.verifyDestination(id, b.approve(), b.note(), staff()), req);
        } catch (RuntimeException e) {
            return bad("DESTINATION_VERIFY_FAILED", e, req);
        }
    }

    // ─── enablements ──────────────────────────────────────────────────────────

    public record EnablementBody(String country, String currency, boolean enabled) {}

    @PostMapping("/merchants/{merchantId}/enablements")
    @PreAuthorize("hasAnyRole('OPS','ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> enable(@PathVariable long merchantId, @RequestBody EnablementBody b, HttpServletRequest req) {
        try {
            return ok(platform.enable(merchantId, b.country(), b.currency(), b.enabled(), staff()), req);
        } catch (RuntimeException e) {
            return bad("ENABLEMENT_FAILED", e, req);
        }
    }

    @GetMapping("/merchants/{merchantId}/readiness")
    public ResponseEntity<ApiResponse<Map<String, Object>>> readiness(@PathVariable long merchantId, HttpServletRequest req) {
        return ok(platform.readiness(merchantId), req);
    }

    // ─── work queues ──────────────────────────────────────────────────────────

    /** Everything waiting for a person, oldest first, with counts. */
    @GetMapping("/work-queues")
    public ResponseEntity<ApiResponse<Map<String, Object>>> workQueues(HttpServletRequest req) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("kybAwaitingRecommendation", queue("SELECT m.id AS merchant_id, m.business_name, m.status, m.created_at, k.assignee FROM merchants m "
                + "LEFT JOIN kyb_cases k ON k.merchant_id = m.id AND k.status IN ('OPEN','AWAITING_MERCHANT') "
                + "WHERE m.status IN ('PENDING','UNDER_REVIEW','UPDATE_REQUIRED') AND NOT EXISTS (SELECT 1 FROM kyb_cases x WHERE x.merchant_id = m.id "
                + "AND x.status = 'AWAITING_DECISION') ORDER BY m.created_at"));
        q.put("kybAwaitingDecision", queue("SELECT k.merchant_id, m.business_name, k.recommendation, k.recommended_by, k.recommended_at FROM kyb_cases k "
                + "JOIN merchants m ON m.id = k.merchant_id WHERE k.status = 'AWAITING_DECISION' ORDER BY k.recommended_at"));
        q.put("destinationsToVerify", queue("SELECT d.id, d.merchant_id, m.business_name, d.currency, d.type, d.institution, d.requested_at "
                + "FROM merchant_settlement_destinations d JOIN merchants m ON m.id = d.merchant_id WHERE d.status = 'PENDING_VERIFICATION' ORDER BY d.requested_at"));
        q.put("settlementBatchesToApprove", queue("SELECT id, batch_ref, currency, mode, total_net_minor, approvals_required, created_by, created_at "
                + "FROM stl_batches WHERE status = 'DRAFT' ORDER BY created_at"));
        q.put("refundsRequiredForLatePayments", queue("SELECT id, category, transaction_reference, provider_code, actual_minor, currency, created_at "
                + "FROM recon_exceptions WHERE status = 'OPEN' AND category IN ('PAID_AFTER_TIMEOUT','PAID_ON_SUPERSEDED_ATTEMPT','PAID_AFTER_FAILURE') ORDER BY created_at"));
        q.put("reconExceptions", queue("SELECT id, category, transaction_reference, provider_code, expected_minor, actual_minor, currency, created_at "
                + "FROM recon_exceptions WHERE status = 'OPEN' AND category NOT IN ('PAID_AFTER_TIMEOUT','PAID_ON_SUPERSEDED_ATTEMPT','PAID_AFTER_FAILURE') ORDER BY created_at"));
        q.put("disputesNeedingResponse", queue("SELECT d.id, cs.merchant_id, d.amount_cents, d.status, d.created_at FROM disputes d "
                + "JOIN checkout_sessions cs ON cs.id = d.checkout_session_id WHERE d.status IN ('OPEN','NEEDS_RESPONSE') ORDER BY d.created_at"));
        q.put("failedWebhooks", queue("SELECT wd.id, w.merchant_id, wd.event_type, wd.attempt_count, wd.response_status, wd.created_at FROM webhook_deliveries wd "
                + "JOIN webhooks w ON w.id = wd.webhook_id WHERE wd.status = 'FAILED' AND wd.created_at > NOW() - INTERVAL 7 DAY ORDER BY wd.created_at"));
        q.put("manualFulfilment", queue("SELECT reference, product_name, amount_cents, currency, updated_at FROM transactions "
                + "WHERE status = 'MANUAL_FULFILMENT' ORDER BY updated_at"));
        Map<String, Object> counts = new LinkedHashMap<>();
        q.forEach((k, v) -> counts.put(k, ((Map<?, ?>) v).get("count")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("counts", counts);
        out.put("queues", q);
        return ok(out, req);
    }

    private Map<String, Object> queue(String sql) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql + " LIMIT 200");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", rows.size());
        m.put("items", rows);
        return m;
    }

    private static String staff() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new IllegalStateException("Not authenticated");
        return "staff:" + auth.getName();
    }

    private Long staffUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : users.findByPhone(auth.getName()).map(u -> u.getId()).orElse(null);
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T body, HttpServletRequest req) {
        return ResponseEntity.ok(ApiResponse.success(body, rid(req)));
    }

    private static <T> ResponseEntity<ApiResponse<T>> bad(String code, RuntimeException e, HttpServletRequest req) {
        int status = e instanceof IllegalStateException ? 409 : 400;
        return ResponseEntity.status(status).body(ApiResponse.error(code, e.getMessage(), rid(req)));
    }

    private static String rid(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
