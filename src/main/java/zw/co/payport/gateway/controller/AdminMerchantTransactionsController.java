package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.entity.CheckoutSession;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.repository.CheckoutSessionRepository;
import zw.co.payport.gateway.repository.MerchantRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/merchant-transactions")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OPS','ADMIN')")
public class AdminMerchantTransactionsController {

    private final CheckoutSessionRepository checkoutSessionRepository;
    private final MerchantRepository merchantRepository;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> search(
            @RequestParam(required = false) Long merchantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String paymentMethodCode,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            HttpServletRequest req) {

        java.time.LocalDateTime from = parseDate(dateFrom, java.time.LocalDateTime.MIN);
        java.time.LocalDateTime to = parseDate(dateTo, java.time.LocalDateTime.MAX);

        List<CheckoutSession> sessions = merchantId != null
                ? checkoutSessionRepository.findByMerchantId(merchantId)
                : checkoutSessionRepository.findAll();

        List<Map<String, Object>> body = sessions.stream()
                .filter(s -> status == null || status.isBlank() || s.getStatus().equalsIgnoreCase(status))
                .filter(s -> paymentMethodCode == null || paymentMethodCode.isBlank() || paymentMethodCode.equalsIgnoreCase(s.getPaymentMethodCode()))
                .filter(s -> currency == null || currency.isBlank() || currency.equalsIgnoreCase(s.getCurrency()))
                .filter(s -> !s.getCreatedAt().isBefore(from) && !s.getCreatedAt().isAfter(to))
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(this::toMap)
                .toList();

        return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
    }

    private java.time.LocalDateTime parseDate(String value, java.time.LocalDateTime fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return java.time.LocalDateTime.parse(value, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            try {
                return java.time.LocalDate.parse(value).atStartOfDay();
            } catch (Exception e2) {
                return fallback;
            }
        }
    }

    private Map<String, Object> toMap(CheckoutSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("merchantId", s.getMerchantId());
        m.put("merchantName", merchantRepository.findById(s.getMerchantId()).map(Merchant::getBusinessName).orElse("Unknown"));
        m.put("reference", s.getReference());
        m.put("description", s.getDescription());
        m.put("amountCents", s.getAmountCents());
        m.put("currency", s.getCurrency());
        m.put("paymentMethodCode", s.getPaymentMethodCode());
        m.put("status", s.getStatus());
        m.put("livemode", Boolean.TRUE.equals(s.getLivemode()));
        m.put("commissionAmountCents", s.getCommissionAmountCents());
        m.put("netAmountCents", s.getNetAmountCents());
        m.put("customerPhone", s.getCustomerPhone());
        m.put("customerEmail", s.getCustomerEmail());
        m.put("createdAt", s.getCreatedAt());
        return m;
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
