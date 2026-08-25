package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.model.request.MerchantRequests;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.service.MerchantAuthService;
import zw.co.payport.gateway.service.MerchantService;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Public-facing merchant onboarding: signup (creates a PENDING application)
 * and login (once approved). Deliberately under /api/v1/merchant-portal/**
 * rather than /api/v1/merchant/** -- that prefix is reserved for the
 * API-key-authenticated developer API (checkout sessions etc.) built in
 * later phases. Mixing a public signup form and a secret-key-authenticated
 * API under the same prefix invites exactly the kind of security-config
 * mistake this project has deliberately avoided elsewhere.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/merchant-portal")
@RequiredArgsConstructor
public class MerchantOnboardingController {

    private final MerchantService merchantService;
    private final MerchantAuthService merchantAuthService;

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<Map<String, Object>>> signup(
            @Valid @RequestBody MerchantRequests.SignupRequest req, HttpServletRequest httpReq) {
        try {
            Merchant merchant = merchantService.signup(req);
            Map<String, Object> body = new HashMap<>();
            body.put("id", merchant.getId());
            body.put("applicationReference", merchant.getApplicationReference());
            body.put("businessName", merchant.getBusinessName());
            body.put("status", merchant.getStatus());
            body.put("message", "Application received. Your reference is " + merchant.getApplicationReference() +
                    " -- we've also emailed it to you. Use it to track your application any time, no login needed.");
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(body, requestId(httpReq)));
        } catch (RuntimeException e) {
            log.warn("[MERCHANT] Signup failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.error("SIGNUP_FAILED", e.getMessage(), requestId(httpReq)));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(
            @Valid @RequestBody MerchantRequests.LoginRequest req, HttpServletRequest httpReq) {
        try {
            Map<String, Object> result = merchantAuthService.login(req);
            return ResponseEntity.ok(ApiResponse.success(result, requestId(httpReq)));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("LOGIN_FAILED", e.getMessage(), requestId(httpReq)));
        }
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
