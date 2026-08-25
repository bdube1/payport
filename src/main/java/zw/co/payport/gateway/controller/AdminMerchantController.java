package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.entity.ApiKey;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.model.request.MerchantRequests;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.repository.MerchantRepository;
import zw.co.payport.gateway.repository.UserRepository;
import zw.co.payport.gateway.service.ApiKeyService;
import zw.co.payport.gateway.service.CommissionService;
import zw.co.payport.gateway.service.MerchantService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ops-facing merchant review and approval. Staff-authenticated (existing
 * JWT scheme) -- OPS and ADMIN can review/approve/reject/suspend, matching
 * the same role convention used everywhere else in the ops console.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/merchants")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OPS','ADMIN')")
public class AdminMerchantController {

    private final MerchantService merchantService;
    private final ApiKeyService apiKeyService;
    private final UserRepository userRepository;
    private final CommissionService commissionService;
    private final MerchantRepository merchantRepository;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            HttpServletRequest req) {
        List<Merchant> merchants = merchantService.listAll();
        if (status != null && !status.isBlank()) {
            merchants = merchants.stream().filter(m -> m.getStatus().equalsIgnoreCase(status)).toList();
        }
        if (search != null && !search.isBlank()) {
            String needle = search.trim().toLowerCase();
            merchants = merchants.stream().filter(m ->
                    (m.getBusinessName() != null && m.getBusinessName().toLowerCase().contains(needle)) ||
                    (m.getRegistrationNumber() != null && m.getRegistrationNumber().toLowerCase().contains(needle)) ||
                    (m.getContactEmail() != null && m.getContactEmail().toLowerCase().contains(needle)) ||
                    (m.getContactPhone() != null && m.getContactPhone().contains(needle))
            ).toList();
        }
        List<Map<String, Object>> body = merchants.stream().map(this::toMap).toList();
        return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable Long id, HttpServletRequest req) {
        Merchant merchant = merchantService.listAll().stream()
                .filter(m -> m.getId().equals(id)).findFirst()
                .orElseThrow(() -> new RuntimeException("Merchant not found: " + id));
        Map<String, Object> body = toMap(merchant);
        List<ApiKey> keys = apiKeyService.listForMerchant(id);
        body.put("apiKeyCount", keys.size());
        return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<Map<String, Object>>> approve(@PathVariable Long id, HttpServletRequest req) {
        try {
            Long staffUserId = currentStaffUserId();
            MerchantService.ApprovalResult result = merchantService.approve(id, staffUserId);

            Map<String, Object> body = toMap(result.merchant());
            // The generated TEST secret key is surfaced here, and only here --
            // see the comment on MerchantService.approve() for why.
            Map<String, Object> keyInfo = new LinkedHashMap<>();
            keyInfo.put("publicKey", result.testKeyPair().publicKey());
            keyInfo.put("secretKey", result.testKeyPair().secretKey());
            keyInfo.put("warning", "This secret key is shown once. Copy it now — it cannot be retrieved again, only rotated.");
            body.put("testApiKey", keyInfo);

            log.info("[ADMIN_MERCHANT] Merchant {} approved by staff user {}", id, staffUserId);
            return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("APPROVE_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reject(
            @PathVariable Long id, @Valid @RequestBody MerchantRequests.RejectRequest body, HttpServletRequest req) {
        try {
            Merchant merchant = merchantService.reject(id, body.getReason());
            return ResponseEntity.ok(ApiResponse.success(toMap(merchant), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("REJECT_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @PostMapping("/{id}/suspend")
    public ResponseEntity<ApiResponse<Map<String, Object>>> suspend(@PathVariable Long id, HttpServletRequest req) {
        try {
            Merchant merchant = merchantService.suspend(id);
            return ResponseEntity.ok(ApiResponse.success(toMap(merchant), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("SUSPEND_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @PostMapping("/{id}/reactivate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reactivate(@PathVariable Long id, HttpServletRequest req) {
        try {
            Merchant merchant = merchantService.reactivate(id);
            return ResponseEntity.ok(ApiResponse.success(toMap(merchant), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("REACTIVATE_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @PostMapping("/{id}/start-review")
    public ResponseEntity<ApiResponse<Map<String, Object>>> startReview(@PathVariable Long id, HttpServletRequest req) {
        try {
            Merchant merchant = merchantService.startReview(id);
            return ResponseEntity.ok(ApiResponse.success(toMap(merchant), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("START_REVIEW_FAILED", e.getMessage(), requestId(req)));
        }
    }

    public record RequestInfoBody(String note) {}

    @PostMapping("/{id}/request-info")
    public ResponseEntity<ApiResponse<Map<String, Object>>> requestInfo(
            @PathVariable Long id, @RequestBody RequestInfoBody body, HttpServletRequest req) {
        try {
            var note = merchantService.requestMoreInfo(id, currentStaffUserId(), body.note());
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("noteId", note.getId());
            map.put("note", note.getNote());
            map.put("status", "UPDATE_REQUIRED");
            return ResponseEntity.ok(ApiResponse.success(map, requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("REQUEST_INFO_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @GetMapping("/{id}/review-notes")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listReviewNotes(@PathVariable Long id, HttpServletRequest req) {
        List<Map<String, Object>> notes = merchantService.listReviewNotes(id).stream().map(n -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.getId());
            m.put("note", n.getNote());
            m.put("status", n.getStatus());
            m.put("createdAt", n.getCreatedAt());
            m.put("resolvedAt", n.getResolvedAt());
            return m;
        }).toList();
        return ResponseEntity.ok(ApiResponse.success(notes, requestId(req)));
    }

    @PostMapping("/{id}/review-notes/{noteId}/resolve")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resolveReviewNote(
            @PathVariable Long id, @PathVariable Long noteId, HttpServletRequest req) {
        try {
            merchantService.resolveReviewNote(id, noteId);
            return ResponseEntity.ok(ApiResponse.success(Map.of("resolved", true), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("RESOLVE_FAILED", e.getMessage(), requestId(req)));
        }
    }

    public record UpdateDetailsBody(String businessName, String businessCategory, String contactEmail, String contactPhone) {}

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateDetails(
            @PathVariable Long id, @RequestBody UpdateDetailsBody body, HttpServletRequest req) {
        try {
            Merchant merchant = merchantService.updateDetails(id, new zw.co.payport.gateway.service.MerchantService.UpdateDetailsRequest(
                    body.businessName(), body.businessCategory(), body.contactEmail(), body.contactPhone()));
            return ResponseEntity.ok(ApiResponse.success(toMap(merchant), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("UPDATE_FAILED", e.getMessage(), requestId(req)));
        }
    }

    // --- Commission configuration ---

    public record SetCommissionBody(Integer bps) {} // null = clear the override, revert to platform default

    @GetMapping("/commission/default")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDefaultCommission(HttpServletRequest req) {
        Map<String, Object> body = Map.of("defaultBps", commissionService.getPlatformDefaultBps());
        return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
    }

    @PutMapping("/commission/default")
    public ResponseEntity<ApiResponse<Map<String, Object>>> setDefaultCommission(@RequestBody SetCommissionBody body, HttpServletRequest req) {
        try {
            if (body.bps() == null) throw new RuntimeException("bps is required for the platform default");
            commissionService.setPlatformDefaultBps(body.bps());
            return ResponseEntity.ok(ApiResponse.success(Map.of("defaultBps", body.bps()), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_COMMISSION", e.getMessage(), requestId(req)));
        }
    }

    @PutMapping("/{id}/commission")
    public ResponseEntity<ApiResponse<Map<String, Object>>> setMerchantCommission(
            @PathVariable Long id, @RequestBody SetCommissionBody body, HttpServletRequest req) {
        try {
            Merchant merchant = merchantRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Merchant not found: " + id));
            if (body.bps() != null) commissionService.validateBps(body.bps());
            merchant.setCommissionRateBps(body.bps()); // null clears the override -- platform default applies again
            merchantRepository.save(merchant);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("merchantId", id);
            result.put("commissionRateBps", body.bps());
            result.put("effectiveBps", commissionService.resolveEffectiveBps(merchant));
            log.info("[ADMIN_MERCHANT] Commission override for merchant {} set to {}", id, body.bps());
            return ResponseEntity.ok(ApiResponse.success(result, requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_COMMISSION", e.getMessage(), requestId(req)));
        }
    }

    private Map<String, Object> toMap(Merchant m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.getId());
        map.put("businessName", m.getBusinessName());
        map.put("registrationNumber", m.getRegistrationNumber());
        map.put("businessCategory", m.getBusinessCategory());
        map.put("contactEmail", m.getContactEmail());
        map.put("contactPhone", m.getContactPhone());
        map.put("status", m.getStatus());
        map.put("rejectionReason", m.getRejectionReason());
        map.put("defaultCurrency", m.getDefaultCurrency());
        map.put("riskScore", m.getRiskScore());
        map.put("riskFlags", m.getRiskFlags());
        map.put("commissionRateBps", m.getCommissionRateBps()); // null = using platform default
        map.put("effectiveCommissionBps", commissionService.resolveEffectiveBps(m));
        map.put("createdAt", m.getCreatedAt());
        map.put("approvedAt", m.getApprovedAt());
        return map;
    }

    /** Same pattern as AccountController -- resolves the JWT subject (phone) to a numeric staff user id. */
    private Long currentStaffUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new RuntimeException("Not authenticated");
        }
        String phone = auth.getName();
        return userRepository.findByPhone(phone)
                .map(u -> u.getId())
                .orElseThrow(() -> new RuntimeException("Staff user not found"));
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
