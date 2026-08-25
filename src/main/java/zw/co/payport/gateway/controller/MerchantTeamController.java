package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.config.MerchantAuthResolver;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.entity.MerchantUser;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.service.MerchantTeamService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Team management, OWNER-only for everything except self-service password
 * change (any role can change their own password). API keys are also
 * granted MERCHANT_OWNER (see ApiKeyAuthenticationFilter) so a merchant's
 * own backend automation can call these too -- intentional, not a gap.
 */
@RestController
@RequestMapping("/api/v1/merchant/team")
@RequiredArgsConstructor
public class MerchantTeamController {

    private final MerchantTeamService teamService;

    public record InviteBody(String fullName, String email, String role) {}
    public record ChangePasswordBody(String currentPassword, String newPassword) {}

    @PostMapping
    @PreAuthorize("hasRole('MERCHANT_OWNER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> invite(@RequestBody InviteBody body, HttpServletRequest req) {
        try {
            Merchant merchant = MerchantAuthResolver.currentMerchant();
            MerchantTeamService.InviteResult result = teamService.invite(merchant.getId(), body.fullName(), body.email(), body.role());

            Map<String, Object> map = toMap(result.user());
            map.put("temporaryPassword", result.temporaryPassword());
            map.put("warning", "This temporary password is shown once. Share it with them directly -- " +
                    "there's no email invite yet, so this is the only way they'll get it. " +
                    "They'll be required to change it on first login.");
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(map, requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVITE_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @GetMapping
    @PreAuthorize("hasRole('MERCHANT_OWNER')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest req) {
        Merchant merchant = MerchantAuthResolver.currentMerchant();
        List<MerchantUser> users = teamService.listForMerchant(merchant.getId());
        return ResponseEntity.ok(ApiResponse.success(users.stream().map(this::toMap).toList(), requestId(req)));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('MERCHANT_OWNER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deactivate(@PathVariable Long id, HttpServletRequest req) {
        return setStatus(id, "DEACTIVATED", req);
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasRole('MERCHANT_OWNER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reactivate(@PathVariable Long id, HttpServletRequest req) {
        return setStatus(id, "ACTIVE", req);
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasRole('MERCHANT_OWNER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resetPassword(@PathVariable Long id, HttpServletRequest req) {
        try {
            Merchant merchant = MerchantAuthResolver.currentMerchant();
            MerchantUser requester = MerchantAuthResolver.currentMerchantUserOrNull();
            Long requestingUserId = requester != null ? requester.getId() : null;

            MerchantTeamService.InviteResult result = teamService.resetPassword(merchant.getId(), id, requestingUserId);

            Map<String, Object> map = toMap(result.user());
            map.put("temporaryPassword", result.temporaryPassword());
            map.put("warning", "This temporary password is shown once. Share it with them directly. " +
                    "They'll be required to change it on next login.");
            return ResponseEntity.ok(ApiResponse.success(map, requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("RESET_FAILED", e.getMessage(), requestId(req)));
        }
    }

    /** Any authenticated merchant user can change their own password -- deliberately not OWNER-gated. */
    @PostMapping("/change-password")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> changePassword(@RequestBody ChangePasswordBody body, HttpServletRequest req) {
        try {
            MerchantUser self = MerchantAuthResolver.currentMerchantUserOrNull();
            if (self == null) {
                throw new RuntimeException("Password change requires a dashboard login, not an API key");
            }
            teamService.changeOwnPassword(self.getId(), body.currentPassword(), body.newPassword());
            return ResponseEntity.ok(ApiResponse.success(Map.of("message", "Password changed"), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("PASSWORD_CHANGE_FAILED", e.getMessage(), requestId(req)));
        }
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> setStatus(Long id, String status, HttpServletRequest req) {
        try {
            Merchant merchant = MerchantAuthResolver.currentMerchant();
            MerchantUser self = MerchantAuthResolver.currentMerchantUserOrNull();
            Long requestingUserId = self != null ? self.getId() : null;
            MerchantUser user = teamService.setStatus(merchant.getId(), id, status, requestingUserId);
            return ResponseEntity.ok(ApiResponse.success(toMap(user), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("STATUS_CHANGE_FAILED", e.getMessage(), requestId(req)));
        }
    }

    private Map<String, Object> toMap(MerchantUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("fullName", u.getFullName());
        m.put("email", u.getEmail());
        m.put("role", u.getRole());
        m.put("status", u.getStatus());
        m.put("mustChangePassword", u.getMustChangePassword());
        m.put("lastLoginAt", u.getLastLoginAt());
        m.put("createdAt", u.getCreatedAt());
        return m;
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
