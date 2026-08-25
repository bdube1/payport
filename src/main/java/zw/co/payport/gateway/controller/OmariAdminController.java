package zw.co.payport.gateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.omari.*;
import zw.co.payport.gateway.service.adaptor.omari.OmariPaymentAdaptor;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** Staff-only -- triggers real Omari payments, queries, and voids. */
@Slf4j
@RestController
@RequestMapping("/api/v1/omari/admin")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OPS','ADMIN','FINANCE')")
public class OmariAdminController {

    private final OmariPaymentAdaptor omariAdaptor;

    /**
     * Auth endpoint - Sends OTP to customer
     * Returns raw Omari auth response
     */
    @PostMapping("/auth")
    public ResponseEntity<?> auth(@RequestBody OmariAuthRequest request) {
        try {
            log.info("[OMARI ADMIN] Auth request - MSISDN: {}, Reference: {}", request.getMsisdn(), request.getReference());
            OmariAuthResponse response = omariAdaptor.callAuth(request);
            
            if (response.isError()) {
                log.error("[OMARI ADMIN] ❌ Auth failed: {}", response.getMessage());
            } else {
                log.info("[OMARI ADMIN] ✅ Auth successful. OTP Reference: {}", response.getOtpReference());
            }
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[OMARI ADMIN] ❌ Auth error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", true,
                    "message", "Auth failed: " + e.getMessage()
            ));
        }
    }

    /**
     * Payment endpoint - Completes payment with OTP
     * Returns raw Omari payment response
     */
    @PostMapping("/payment")
    public ResponseEntity<?> payment(@RequestBody OmariPaymentRequest request) {
        try {
            log.info("[OMARI ADMIN] Payment request - Reference: {}, OTP: {}", request.getReference(), request.getOtp());
            OmariPaymentResponse response = omariAdaptor.callPayment(request);
            
            if (!response.isError()) {
                log.info("[OMARI ADMIN] ✅ Payment successful. Payment Reference: {}", response.getPaymentReference());
            } else {
                log.error("[OMARI ADMIN] ❌ Payment failed: {}", response.getMessage());
            }
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[OMARI ADMIN] ❌ Payment error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", true,
                    "message", "Payment failed: " + e.getMessage()
            ));
        }
    }

    /**
     * Query endpoint - Check transaction status
     * Returns raw Omari query response
     */
    @GetMapping("/query/{reference}")
    public ResponseEntity<?> query(@PathVariable String reference) {
        try {
            log.info("[OMARI ADMIN] Query request - Reference: {}", reference);
            OmariQueryResponse response = omariAdaptor.callQuery(reference);
            
            if (!response.isError()) {
                log.info("[OMARI ADMIN] ✅ Query successful - Status: {}, Payment Ref: {}", 
                        response.getStatus(), response.getPaymentReference());
            } else {
                log.error("[OMARI ADMIN] ❌ Query failed: {}", response.getMessage());
            }
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[OMARI ADMIN] ❌ Query error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", true,
                    "message", "Query failed: " + e.getMessage()
            ));
        }
    }

    /**
     * Void endpoint - Cancel transaction
     * Returns raw Omari void response
     */
    @PostMapping("/void")
    public ResponseEntity<?> voidPayment(@RequestBody Map<String, String> request) {
        try {
            String reference = request.get("reference");
            log.info("[OMARI ADMIN] Void request - Reference: {}", reference);
            OmariVoidResponse response = omariAdaptor.callVoid(reference);
            
            if (!response.isError()) {
                log.info("[OMARI ADMIN] ✅ Void successful");
            } else {
                log.error("[OMARI ADMIN] ❌ Void failed: {}", response.getMessage());
            }
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[OMARI ADMIN] ❌ Void error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", true,
                    "message", "Void failed: " + e.getMessage()
            ));
        }
    }

    /**
     * TEST FLOW - Complete auth flow (generates reference for you)
     * Returns RAW Omari auth response (same as /auth)
     */
    @PostMapping("/test-flow")
    public ResponseEntity<?> testFlow(@RequestBody Map<String, String> request) {
        try {
            String msisdn = request.getOrDefault("msisdn", "263776988135");
            BigDecimal amount = new BigDecimal(request.getOrDefault("amount", "1.0"));
            String currency = request.getOrDefault("currency", "USD");
            
            String reference = UUID.randomUUID().toString();

            OmariAuthRequest authRequest = OmariAuthRequest.builder()
                    .msisdn(msisdn)
                    .reference(reference)
                    .amount(amount)
                    .currency(currency)
                    .channel("WEB")
                    .build();

            log.info("[OMARI ADMIN] Test flow - Auth request for reference: {}", reference);
            OmariAuthResponse authResponse = omariAdaptor.callAuth(authRequest);

            if (authResponse.isError()) {
                log.error("[OMARI ADMIN] ❌ Test flow - Auth failed: {}", authResponse.getMessage());
            } else {
                log.info("[OMARI ADMIN] ✅ Test flow - Auth successful. OTP Reference: {}", authResponse.getOtpReference());
            }

            // Return RAW Omari auth response (same as /auth endpoint)
            return ResponseEntity.ok(authResponse);

        } catch (Exception e) {
            log.error("[OMARI ADMIN] ❌ Test flow error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", true,
                    "message", "Test flow failed: " + e.getMessage()
            ));
        }
    }

    /**
     * TEST PAYMENT - Complete payment with OTP
     * Returns RAW Omari payment response (same as /payment)
     */
    @PostMapping("/test-payment")
    public ResponseEntity<?> testPayment(@RequestBody Map<String, String> request) {
        try {
            String reference = request.get("reference");
            String otp = request.get("otp");
            String msisdn = request.getOrDefault("msisdn", "263776988135");

            if (reference == null || otp == null) {
                log.error("[OMARI ADMIN] ❌ Test payment - Missing reference or OTP");
                return ResponseEntity.badRequest().body(Map.of(
                        "error", true,
                        "message", "Reference and OTP are required"
                ));
            }

            OmariPaymentRequest paymentRequest = OmariPaymentRequest.builder()
                    .msisdn(msisdn)
                    .reference(reference)
                    .otp(otp)
                    .build();

            log.info("[OMARI ADMIN] Test payment - Reference: {}, OTP: {}", reference, otp);
            OmariPaymentResponse paymentResponse = omariAdaptor.callPayment(paymentRequest);

            if (!paymentResponse.isError()) {
                log.info("[OMARI ADMIN] ✅ Test payment - Successful. Payment Ref: {}", paymentResponse.getPaymentReference());
            } else {
                log.error("[OMARI ADMIN] ❌ Test payment - Failed: {}", paymentResponse.getMessage());
            }

            // Return RAW Omari payment response (same as /payment endpoint)
            return ResponseEntity.ok(paymentResponse);

        } catch (Exception e) {
            log.error("[OMARI ADMIN] ❌ Test payment error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", true,
                    "message", "Test payment failed: " + e.getMessage()
            ));
        }
    }
}
