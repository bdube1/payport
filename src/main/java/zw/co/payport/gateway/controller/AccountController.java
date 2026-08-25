package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.service.AuthService;

import java.util.UUID;

/**
 * Self-service account actions for the currently logged-in user (customer
 * or staff). Not under /api/v1/auth/** on purpose -- that prefix is
 * permitAll; this needs the default authenticated-only rule to actually
 * apply.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/account")
@RequiredArgsConstructor
public class AccountController {

    private final AuthService authService;

    public record ChangeCredentialRequest(String currentValue, String newValue) {}

    @PostMapping("/change-pin")
    public ResponseEntity<ApiResponse<String>> changePin(
            @RequestBody ChangeCredentialRequest req, HttpServletRequest httpReq) {
        String phone = requireAuthenticatedPhone();
        if (phone == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "Login required", requestId(httpReq)));
        }
        try {
            authService.changeCredential(phone, req.currentValue(), req.newValue());
            return ResponseEntity.ok(ApiResponse.success("PIN changed successfully", requestId(httpReq)));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("CHANGE_FAILED", e.getMessage(), requestId(httpReq)));
        }
    }

    /** Same underlying logic, staff-facing name/copy to match the ops portal's vocabulary. */
    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse<String>> changePassword(
            @RequestBody ChangeCredentialRequest req, HttpServletRequest httpReq) {
        String phone = requireAuthenticatedPhone();
        if (phone == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "Login required", requestId(httpReq)));
        }
        try {
            authService.changeCredential(phone, req.currentValue(), req.newValue());
            return ResponseEntity.ok(ApiResponse.success("Password changed successfully", requestId(httpReq)));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("CHANGE_FAILED", e.getMessage(), requestId(httpReq)));
        }
    }

    /** Same defensive pattern as BeneficiaryController -- redundant with
     *  the URL-level rule, but cheap insurance if that rule ever changes. */
    private String requireAuthenticatedPhone() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        return auth.getName();
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
