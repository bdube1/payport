package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.entity.CheckoutSession;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.repository.MerchantRepository;
import zw.co.payport.gateway.service.CheckoutSessionService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Hit by the hosted checkout page itself, in the END CUSTOMER's browser --
 * deliberately unauthenticated (a customer has no API key), and
 * deliberately returns only what's safe for a stranger to see: the
 * merchant's name and the amount, never anything from the merchant's own
 * account (email, API keys, other sessions).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/checkout")
@RequiredArgsConstructor
public class CheckoutController {

    private final CheckoutSessionService checkoutSessionService;
    private final MerchantRepository merchantRepository;
    private final zw.co.payport.gateway.repository.TransactionRepository transactionRepository;

    public record PayBody(String customerPhone, String customerEmail, String paymentMethodCode) {}

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPublicInfo(@PathVariable Long id, HttpServletRequest req) {
        try {
            CheckoutSession session = checkoutSessionService.getPublic(id);
            Merchant merchant = merchantRepository.findById(session.getMerchantId()).orElse(null);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", session.getId());
            body.put("merchantName", merchant != null ? merchant.getBusinessName() : "Unknown merchant");
            body.put("amountCents", session.getAmountCents());
            body.put("currency", session.getCurrency());
            body.put("description", session.getDescription());
            body.put("status", session.getStatus());
            // Public endpoint keyed by a sequential id: never return the full phone number (R3).
            body.put("customerPhone", maskPhone(session.getCustomerPhone()));
            body.put("testMode", !Boolean.TRUE.equals(session.getLivemode()));
            return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("NOT_FOUND", e.getMessage(), requestId(req)));
        }
    }

    @PostMapping("/{id}/pay")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pay(
            @PathVariable Long id, @RequestBody PayBody body, HttpServletRequest req) {
        try {
            // Refreshes status and applies expiry before payment is attempted.
            checkoutSessionService.getPublic(id);

            String ip = req.getRemoteAddr();
            CheckoutSessionService.PaymentSubmissionResult result = checkoutSessionService.submitPayment(
                    id, body.customerPhone(), body.customerEmail(), body.paymentMethodCode(), ip);

            Map<String, Object> resultBody = new LinkedHashMap<>();
            resultBody.put("id", result.session().getId());
            resultBody.put("status", result.session().getStatus());
            resultBody.put("testMode", !Boolean.TRUE.equals(result.session().getLivemode()));
            // Card payments (ZimSwitch) need the customer's browser to load
            // this script directly -- it's what renders the actual card
            // form. EcoCash/OneMoney/Omari leave these null; the frontend
            // shows the "check your phone" wait state instead.
            if (result.widgetJsUrl() != null) {
                resultBody.put("widgetJsUrl", result.widgetJsUrl());
                resultBody.put("paymentCorrelator", result.paymentCorrelator());
            }
            // InnBucks: the code the customer approves in the InnBucks app (returned to the payer only).
            if ("INNBUCKS".equalsIgnoreCase(body.paymentMethodCode()) && result.session().getTransactionReference() != null) {
                transactionRepository.findByReference(result.session().getTransactionReference())
                        .map(zw.co.payport.gateway.entity.Transaction::getProviderReference)
                        .ifPresent(code -> resultBody.put("paymentCode", code));
            }
            return ResponseEntity.ok(ApiResponse.success(resultBody, requestId(req)));
        } catch (Exception e) {
            // Was `catch (RuntimeException e)` -- submitPayment() is
            // declared `throws Exception`, so any checked exception from
            // deep in the payment flow (a provider rejecting a wrong PIN
            // is exactly this shape) skipped this block entirely and
            // fell through to the generic global handler, which discards
            // the real message and shows "an unexpected error occurred"
            // regardless of what actually happened. This is the fix --
            // and it also replaces what used to be a second, separate
            // catch(Exception e) block below this one that always hid
            // the real message behind a generic "something went wrong"
            // text. That block is gone now, not just unreachable --
            // keeping it would have meant reverting the actual fix.
            log.warn("[CHECKOUT] Payment failed for session {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.error("PAYMENT_FAILED", e.getMessage(), requestId(req)));
        }
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }

    /** "+263771234567" -> "+263*******567". Null/short values are returned fully masked or null. */
    static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        if (phone.length() <= 7) return "*".repeat(phone.length());
        return phone.substring(0, 4) + "*".repeat(phone.length() - 7) + phone.substring(phone.length() - 3);
    }
}
