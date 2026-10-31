package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.config.MerchantAuthResolver;
import zw.co.payport.gateway.entity.CheckoutSession;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.entity.MerchantUser;
import zw.co.payport.gateway.entity.Refund;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.repository.CheckoutSessionRepository;
import zw.co.payport.gateway.service.RefundService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Refund tracking, OWNER/DEVELOPER only (matches API-key and FINANCE
 * boundaries used elsewhere -- FINANCE can see refunds happened via the
 * transaction list, but can't initiate one). See RefundService for what
 * "refund" actually means in this codebase right now -- tracking, not
 * automatic provider-side money movement.
 */
@RestController
@RequestMapping("/api/v1/merchant/checkout-sessions/{sessionId}/refunds")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MERCHANT_OWNER','MERCHANT_DEVELOPER')")
public class RefundController {

    private final RefundService refundService;
    private final CheckoutSessionRepository checkoutSessionRepository;

    public record RefundBody(Long amountCents, String reason) {}

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @PathVariable Long sessionId, @RequestBody RefundBody body, HttpServletRequest req) {
        try {
            Merchant merchant = MerchantAuthResolver.currentMerchant();
            CheckoutSession session = getOwnedSession(merchant, sessionId);

            String initiatedByType = "MERCHANT_USER";
            Long initiatedById;
            MerchantUser self = MerchantAuthResolver.currentMerchantUserOrNull();
            initiatedById = self != null ? self.getId() : 0L; // 0 = API-key/server-initiated, not a specific person

            Refund refund = refundService.createRefund(
                    session, new RefundService.RefundRequest(body.amountCents(), body.reason()),
                    initiatedByType, initiatedById);

            Map<String, Object> map = toMap(refund);
            map.put("note", "Taken from your balance. Payport pays your customer back and records the payout.");
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(map, requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("REFUND_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(@PathVariable Long sessionId, HttpServletRequest req) {
        Merchant merchant = MerchantAuthResolver.currentMerchant();
        getOwnedSession(merchant, sessionId);
        List<Refund> refunds = refundService.listForSession(sessionId);
        return ResponseEntity.ok(ApiResponse.success(refunds.stream().map(this::toMap).toList(), requestId(req)));
    }

    private CheckoutSession getOwnedSession(Merchant merchant, Long sessionId) {
        CheckoutSession session = checkoutSessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Checkout session not found: " + sessionId));
        if (!session.getMerchantId().equals(merchant.getId())) {
            throw new RuntimeException("Checkout session not found: " + sessionId);
        }
        return session;
    }

    private Map<String, Object> toMap(Refund r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("amountCents", r.getAmountCents());
        m.put("reason", r.getReason());
        m.put("status", r.getStatus());
        m.put("createdAt", r.getCreatedAt());
        return m;
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
