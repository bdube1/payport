package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.request.AuthRequests;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.service.AuthService;

import java.util.Map;
import java.util.UUID;

/**
 * Auth Controller
 *
 * POST /api/v1/auth/register      - Consumer registration (fullName, phone, 4-digit PIN)
 * POST /api/v1/auth/login         - Consumer login
 * POST /api/v1/auth/ops/login     - Ops/Admin login
 *
 * Guest transactions are always allowed - registration is optional.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Map<String, Object>>> register(
            @Valid @RequestBody AuthRequests.RegisterRequest req,
            HttpServletRequest httpReq) {

        log.info("[AUTH] Register request for phone: {}", maskPhone(req.getPhone()));
        Map<String, Object> result = authService.register(req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(result, requestId(httpReq)));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(
            @Valid @RequestBody AuthRequests.LoginRequest req,
            HttpServletRequest httpReq) {

        log.info("[AUTH] Login request for phone: {}", maskPhone(req.getPhone()));
        Map<String, Object> result = authService.login(req);
        return ResponseEntity.ok(ApiResponse.success(result, requestId(httpReq)));
    }

    @PostMapping("/ops/login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> opsLogin(
            @Valid @RequestBody AuthRequests.OpsLoginRequest req,
            HttpServletRequest httpReq) {

        log.info("[AUTH] Ops login request for email: {}", maskEmail(req.getEmail()));
        Map<String, Object> result = authService.opsLogin(req);
        return ResponseEntity.ok(ApiResponse.success(result, requestId(httpReq)));
    }

    @PostMapping("/ops/forgot-password")
    public ResponseEntity<ApiResponse<String>> forgotPassword(
            @Valid @RequestBody AuthRequests.ForgotPasswordRequest req,
            HttpServletRequest httpReq) {

        log.info("[AUTH] Password reset requested for: {}", maskEmail(req.getEmail()));
        authService.forgotPassword(req);
        // Always the same response, whether or not the email exists -- this
        // endpoint must not be usable to enumerate valid staff accounts.
        return ResponseEntity.ok(ApiResponse.success(
                "If that email is registered, a reset link has been sent.", requestId(httpReq)));
    }

    @PostMapping("/ops/reset-password")
    public ResponseEntity<ApiResponse<String>> resetPassword(
            @Valid @RequestBody AuthRequests.ResetPasswordRequest req,
            HttpServletRequest httpReq) {

        authService.resetPassword(req);
        return ResponseEntity.ok(ApiResponse.success("Password reset successful.", requestId(httpReq)));
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return "****";
        return phone.substring(0, Math.min(5, phone.length())) + "****";
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "****";
        int at = email.indexOf('@');
        return email.substring(0, Math.min(2, at)) + "****" + email.substring(at);
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
