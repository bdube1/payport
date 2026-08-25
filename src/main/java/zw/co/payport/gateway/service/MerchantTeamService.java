package zw.co.payport.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.entity.MerchantUser;
import zw.co.payport.gateway.repository.MerchantUserRepository;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Team management -- an OWNER inviting other people into their merchant
 * account with a specific role. No email-invite-link flow yet (that's a
 * real gap, noted where this is wired up) -- the OWNER sets a temporary
 * password directly, and the new user is required to change it on first
 * login (mustChangePassword), same pattern already used for staff.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantTeamService {

    private static final Set<String> VALID_ROLES = Set.of("OWNER", "DEVELOPER", "FINANCE");

    private final MerchantUserRepository merchantUserRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    public record InviteResult(MerchantUser user, String temporaryPassword) {}

    public InviteResult invite(Long merchantId, String fullName, String email, String role) {
        if (!VALID_ROLES.contains(role)) {
            throw new RuntimeException("Role must be one of: OWNER, DEVELOPER, FINANCE");
        }
        String normalizedEmail = email.trim().toLowerCase();
        if (merchantUserRepository.existsByEmail(normalizedEmail)) {
            throw new RuntimeException("An account with this email already exists");
        }

        String temporaryPassword = generateTemporaryPassword();
        MerchantUser user = MerchantUser.builder()
                .merchantId(merchantId)
                .fullName(fullName.trim())
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(temporaryPassword))
                .role(role)
                .status("ACTIVE")
                .mustChangePassword(true)
                .passwordSetAt(LocalDateTime.now())
                .build();
        merchantUserRepository.save(user);

        log.info("[MERCHANT_TEAM] Invited {} ({}) to merchant {}", normalizedEmail, role, merchantId);
        return new InviteResult(user, temporaryPassword);
    }

    public List<MerchantUser> listForMerchant(Long merchantId) {
        return merchantUserRepository.findByMerchantId(merchantId);
    }

    public MerchantUser setStatus(Long merchantId, Long userId, String status, Long requestingUserId) {
        MerchantUser user = getOrThrow(userId);
        if (!user.getMerchantId().equals(merchantId)) {
            throw new RuntimeException("Team member not found: " + userId);
        }
        if (user.getId().equals(requestingUserId) && "DEACTIVATED".equals(status)) {
            throw new RuntimeException("You cannot deactivate your own account");
        }
        if ("OWNER".equals(user.getRole()) && "DEACTIVATED".equals(status)) {
            long remainingActiveOwners = merchantUserRepository.findByMerchantId(merchantId).stream()
                    .filter(u -> "OWNER".equals(u.getRole()) && "ACTIVE".equals(u.getStatus()) && !u.getId().equals(userId))
                    .count();
            if (remainingActiveOwners == 0) {
                throw new RuntimeException("Cannot deactivate the last active OWNER on this merchant account");
            }
        }
        user.setStatus(status);
        merchantUserRepository.save(user);
        log.info("[MERCHANT_TEAM] User {} status set to {} by user {}", userId, status, requestingUserId);
        return user;
    }

    public void changeOwnPassword(Long merchantUserId, String currentPassword, String newPassword) {
        MerchantUser user = getOrThrow(merchantUserId);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new RuntimeException("Current password is incorrect");
        }
        validatePasswordPolicy(newPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        user.setPasswordSetAt(LocalDateTime.now());
        merchantUserRepository.save(user);
        log.info("[MERCHANT_TEAM] User {} changed their own password", merchantUserId);
    }

    /**
     * OWNER resets someone else's password (they've forgotten it, lost
     * access, etc.) -- distinct from changeOwnPassword, which requires
     * knowing the current password. Generates a fresh temporary password,
     * same as invite(), and forces a change on next login.
     */
    public InviteResult resetPassword(Long merchantId, Long userId, Long requestingUserId) {
        MerchantUser user = getOrThrow(userId);
        if (!user.getMerchantId().equals(merchantId)) {
            throw new RuntimeException("Team member not found: " + userId);
        }
        String temporaryPassword = generateTemporaryPassword();
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.setMustChangePassword(true);
        user.setPasswordSetAt(LocalDateTime.now());
        user.setFailedLoginAttempts(0);
        user.setAccountLocked(false);
        merchantUserRepository.save(user);
        log.info("[MERCHANT_TEAM] Password reset for user {} by user {}", userId, requestingUserId);
        return new InviteResult(user, temporaryPassword);
    }

    private MerchantUser getOrThrow(Long id) {
        return merchantUserRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Team member not found: " + id));
    }

    private void validatePasswordPolicy(String password) {
        if (password == null || password.length() < 8) throw new RuntimeException("Password must be at least 8 characters long");
        if (!password.matches(".*[A-Z].*")) throw new RuntimeException("Password must contain at least one uppercase letter");
        if (!password.matches(".*[a-z].*")) throw new RuntimeException("Password must contain at least one lowercase letter");
        if (!password.matches(".*\\d.*")) throw new RuntimeException("Password must contain at least one digit");
        if (!password.matches(".*[^A-Za-z0-9].*")) throw new RuntimeException("Password must contain at least one special character");
    }

    private String generateTemporaryPassword() {
        // Guaranteed to satisfy the policy above -- fixed structure, random content.
        byte[] bytes = new byte[6];
        secureRandom.nextBytes(bytes);
        String random = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).replace("_", "9").replace("-", "8");
        return "Pp!" + random + "1";
    }
}
