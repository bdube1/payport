package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.config.MerchantAuthResolver;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.entity.PaymentLink;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.service.PaymentLinkService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchant/payment-links")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MERCHANT_OWNER','MERCHANT_DEVELOPER')")
public class PaymentLinkController {

    private final PaymentLinkService paymentLinkService;

    @Value("${payport.consumer.base-url}")
    private String consumerBaseUrl;

    public record CreateBody(Long amountCents, String currency, String description, Boolean reusable) {}

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody CreateBody body, HttpServletRequest req) {
        try {
            Merchant merchant = MerchantAuthResolver.currentMerchant();
            PaymentLink link = paymentLinkService.create(merchant,
                    new PaymentLinkService.CreateLinkRequest(body.amountCents(), body.currency(), body.description(), body.reusable()));
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toMap(link), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("PAYMENT_LINK_CREATE_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest req) {
        Merchant merchant = MerchantAuthResolver.currentMerchant();
        List<PaymentLink> links = paymentLinkService.listForMerchant(merchant.getId());
        return ResponseEntity.ok(ApiResponse.success(links.stream().map(this::toMap).toList(), requestId(req)));
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<ApiResponse<Map<String, Object>>> disable(@PathVariable Long id, HttpServletRequest req) {
        try {
            Merchant merchant = MerchantAuthResolver.currentMerchant();
            PaymentLink link = paymentLinkService.setStatus(merchant, id, "DISABLED");
            return ResponseEntity.ok(ApiResponse.success(toMap(link), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("DISABLE_FAILED", e.getMessage(), requestId(req)));
        }
    }

    private Map<String, Object> toMap(PaymentLink l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("slug", l.getSlug());
        m.put("url", consumerBaseUrl + "/pay/" + l.getSlug());
        m.put("amountCents", l.getAmountCents());
        m.put("currency", l.getCurrency());
        m.put("description", l.getDescription());
        m.put("status", l.getStatus());
        m.put("reusable", l.getReusable());
        m.put("createdAt", l.getCreatedAt());
        return m;
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
