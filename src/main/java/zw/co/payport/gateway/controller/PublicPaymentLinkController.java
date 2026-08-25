package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.entity.PaymentLink;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.service.PaymentLinkService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/pay")
@RequiredArgsConstructor
public class PublicPaymentLinkController {

    private final PaymentLinkService paymentLinkService;

    public record StartBody(Long amountCents) {}

    @GetMapping("/{slug}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> info(@PathVariable String slug, HttpServletRequest req) {
        try {
            PaymentLink link = paymentLinkService.getBySlug(slug);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("amountCents", link.getAmountCents());
            body.put("currency", link.getCurrency());
            body.put("description", link.getDescription());
            body.put("status", link.getStatus());
            body.put("requiresCustomAmount", link.getAmountCents() == null);
            return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("NOT_FOUND", e.getMessage(), requestId(req)));
        }
    }

    @PostMapping("/{slug}/start")
    public ResponseEntity<ApiResponse<Map<String, Object>>> start(
            @PathVariable String slug, @RequestBody(required = false) StartBody body, HttpServletRequest req) {
        try {
            Long customAmount = body != null ? body.amountCents() : null;
            Long sessionId = paymentLinkService.startCheckoutFromLink(slug, customAmount);
            return ResponseEntity.ok(ApiResponse.success(Map.of("checkoutSessionId", sessionId), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("START_FAILED", e.getMessage(), requestId(req)));
        }
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
