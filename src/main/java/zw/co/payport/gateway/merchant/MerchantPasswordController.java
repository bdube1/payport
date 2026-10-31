package zw.co.payport.gateway.merchant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.entity.MerchantUser;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.PortalCredentials;
import zw.co.payport.gateway.platform.Web;
import zw.co.payport.gateway.repository.MerchantRepository;
import zw.co.payport.gateway.repository.MerchantUserRepository;
import zw.co.payport.gateway.risk.ComplianceAudit;
import zw.co.payport.gateway.service.EmailService;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Merchant portal passwords: "forgot password" by emailed link (only the link's hash is stored, it
 * works for an hour and once), and the ops reset of a merchant user's password to a temporary one
 * that must be changed at the next sign-in (which also unlocks the login).
 */
@RestController
public class MerchantPasswordController {

    private final MerchantUserRepository users;
    private final MerchantRepository merchants;
    private final BCryptPasswordEncoder encoder;
    private final EmailService email;
    private final ComplianceAudit audit;
    private final String portalUrl;

    public MerchantPasswordController(MerchantUserRepository users, MerchantRepository merchants, BCryptPasswordEncoder encoder, EmailService email,
                                      ComplianceAudit audit, @Value("${payport.portal.merchant-url:https://merchant.payport.africa}") String portalUrl) {
        this.users = users;
        this.merchants = merchants;
        this.encoder = encoder;
        this.email = email;
        this.audit = audit;
        this.portalUrl = portalUrl.endsWith("/") ? portalUrl.substring(0, portalUrl.length() - 1) : portalUrl;
    }

    public record EmailBody(String email) {}

    /** Always the same answer, so it cannot be used to find out which emails have logins. */
    @PostMapping("/api/v1/merchant-portal/forgot-password")
    public ResponseEntity<ApiResponse<Map<String, Object>>> forgot(@RequestBody EmailBody b, HttpServletRequest req) {
        return Web.run(() -> {
            String e = b.email() == null ? "" : b.email().trim().toLowerCase(Locale.ROOT);
            users.findByEmail(e).filter(u -> "ACTIVE".equals(u.getStatus())).ifPresent(u -> {
                String token = PortalCredentials.resetToken();
                u.setResetToken(PortalCredentials.sha256(token));
                u.setResetTokenExpiresAt(LocalDateTime.now().plusHours(1));
                users.save(u);
                email.sendPortalMail(u.getEmail(), "Reset your Payport merchant password",
                        "Hi " + u.getFullName() + ",\n\nA password reset was requested for your Payport merchant portal login.\n\n"
                                + "Choose a new password here (the link works for 1 hour):\n" + portalUrl + "/reset-password?token=" + token
                                + "\n\nIf you did not ask for this, ignore this email.\n\n-- Payport");
            });
            return Map.<String, Object>of("sent", true);
        }, req);
    }

    public record ResetBody(String token, String newPassword) {}

    @PostMapping("/api/v1/merchant-portal/reset-password")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reset(@RequestBody ResetBody b, HttpServletRequest req) {
        return Web.run(() -> {
            if (b.token() == null || b.token().isBlank()) throw new IllegalArgumentException("Invalid or expired reset link");
            MerchantUser u = users.findByResetToken(PortalCredentials.sha256(b.token().trim()))
                    .orElseThrow(() -> new IllegalArgumentException("Invalid or expired reset link"));
            if (u.getResetTokenExpiresAt() == null || u.getResetTokenExpiresAt().isBefore(LocalDateTime.now())) {
                throw new IllegalArgumentException("Invalid or expired reset link");
            }
            PortalCredentials.requireStrong(b.newPassword());
            u.setPasswordHash(encoder.encode(b.newPassword()));
            u.setPasswordSetAt(LocalDateTime.now());
            u.setMustChangePassword(false);
            u.setAccountLocked(false);
            u.setFailedLoginAttempts(0);
            u.setResetToken(null);
            u.setResetTokenExpiresAt(null);
            users.save(u);
            return Map.<String, Object>of("reset", true);
        }, req);
    }

    // ─── ops ─────────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/admin/merchants/{id}/users")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','COMPLIANCE')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(@PathVariable Long id, HttpServletRequest req) {
        return Web.run(() -> users.findByMerchantId(id).stream().map(MerchantPasswordController::view).toList(), req);
    }

    @PostMapping("/api/v1/admin/merchants/{id}/users/{userId}/reset-password")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adminReset(@PathVariable Long id, @PathVariable Long userId, HttpServletRequest req) {
        return Web.run(() -> {
            merchants.findById(id).orElseThrow(() -> new IllegalArgumentException("No such merchant"));
            MerchantUser u = users.findById(userId).filter(x -> id.equals(x.getMerchantId()))
                    .orElseThrow(() -> new IllegalArgumentException("No such login for this merchant"));
            String temp = PortalCredentials.temporaryPassword();
            u.setPasswordHash(encoder.encode(temp));
            u.setPasswordSetAt(LocalDateTime.now());
            u.setMustChangePassword(true);
            u.setAccountLocked(false);
            u.setFailedLoginAttempts(0);
            u.setResetToken(null);
            u.setResetTokenExpiresAt(null);
            users.save(u);
            audit.record(Web.staff(), "MERCHANT_PASSWORD_RESET", "merchant_user:" + u.getId(), u.getEmail());
            boolean sent = email.sendPortalMail(u.getEmail(), "Your Payport merchant password was reset",
                    "Hi " + u.getFullName() + ",\n\nYour Payport merchant portal password was reset by Payport.\n\nTemporary password: " + temp
                            + "\nSign in at " + portalUrl + "/login and choose a new password.\n\n-- Payport");
            Map<String, Object> out = new LinkedHashMap<>(view(u));
            out.put("temporaryPassword", temp);
            out.put("emailed", sent);
            return out;
        }, req);
    }

    static Map<String, Object> view(MerchantUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("fullName", u.getFullName());
        m.put("email", u.getEmail());
        m.put("role", u.getRole());
        m.put("status", u.getStatus());
        m.put("accountLocked", Boolean.TRUE.equals(u.getAccountLocked()));
        m.put("mustChangePassword", Boolean.TRUE.equals(u.getMustChangePassword()));
        m.put("lastLoginAt", u.getLastLoginAt());
        return m;
    }
}
