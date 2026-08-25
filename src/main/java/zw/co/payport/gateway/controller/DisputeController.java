package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.config.MerchantAuthResolver;
import zw.co.payport.gateway.entity.CheckoutSession;
import zw.co.payport.gateway.entity.Dispute;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.repository.CheckoutSessionRepository;
import zw.co.payport.gateway.service.DisputeService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchant/checkout-sessions/{sessionId}/disputes")
@RequiredArgsConstructor
@PreAuthorize("hasRole('MERCHANT')")
public class DisputeController {

    private final DisputeService disputeService;
    private final CheckoutSessionRepository checkoutSessionRepository;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(@PathVariable Long sessionId, HttpServletRequest req) {
        Merchant merchant = MerchantAuthResolver.currentMerchant();
        CheckoutSession session = checkoutSessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Checkout session not found: " + sessionId));
        if (!session.getMerchantId().equals(merchant.getId())) {
            throw new RuntimeException("Checkout session not found: " + sessionId);
        }
        List<Dispute> disputes = disputeService.listForSession(sessionId);
        return ResponseEntity.ok(ApiResponse.success(disputes.stream().map(this::toMap).toList(), requestId(req)));
    }

    private Map<String, Object> toMap(Dispute d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("amountCents", d.getAmountCents());
        m.put("reason", d.getReason());
        m.put("status", d.getStatus());
        m.put("createdAt", d.getCreatedAt());
        m.put("resolvedAt", d.getResolvedAt());
        return m;
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
