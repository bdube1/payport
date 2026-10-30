package zw.co.payport.gateway.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.entity.User;
import zw.co.payport.gateway.model.request.AuthRequests;
import zw.co.payport.gateway.repository.UserRepository;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final EmailService emailService;
    private final zw.co.payport.gateway.roles.StaffRoles staffRoles;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${payport.security.jwt-secret}")
    private String jwtSecret;

    @Value("${payport.security.jwt-expiration-ms:3600000}")
    private long jwtExpirationMs;

    /**
     * Normalize phone to E.164 format (+263XXXXXXXXX)
     */
    public String normalizePhone(String raw) {
        if (raw == null) return null;
        String p = raw.replaceAll("[\\s\\-]", "");
        if (p.startsWith("+263")) return p;
        if (p.startsWith("263"))  return "+" + p;
        if (p.startsWith("0"))    return "+263" + p.substring(1);
        if (p.matches("[7-9]\\d{8}")) return "+263" + p;
        return p;
    }

    /**
     * Register a new customer
     */
    public Map<String, Object> register(AuthRequests.RegisterRequest req) {
        return registerWithPinHash(req.getFullName(), req.getPhone(), req.getIdNumber(), passwordEncoder.encode(req.getPin()));
    }

    /**
     * Registration where the PIN was set on PayPort's own PIN page (WhatsApp): only its hash reached here.
     * Same checks and effects as register.
     */
    public Map<String, Object> registerWithPinHash(String fullName, String rawPhone, String idNumber, String pinHash) {
        String phone = normalizePhone(rawPhone);

        if (userRepository.existsByPhone(phone)) {
            throw new RuntimeException("An account with this phone number already exists");
        }

        User user = User.builder()
                .fullName(fullName.trim())
                .phone(phone)
                .pinHash(pinHash)
                .role("CUSTOMER")
                .idNumber(cleanIdNumber(idNumber))
                .isActive(true)
                .isVerified(true) // auto-verify for now; add OTP later
                .failedPinAttempts(0)
                .build();

        user = userRepository.save(user);
        log.info("[AUTH] Registered new customer: {} phone: {}", user.getFullName(), maskPhone(phone));

        String token = generateToken(user);
        return buildAuthResponse(user, token);
    }

    /** ID numbers are stored upper-case without spaces (e.g. 63-123456 A 42 -> 63-123456A42). */
    public static String cleanIdNumber(String raw) {
        if (raw == null) return null;
        String s = raw.replace(" ", "").trim().toUpperCase();
        return s.isEmpty() ? null : s;
    }

    /**
     * Login (consumer)
     */
    public Map<String, Object> login(AuthRequests.LoginRequest req) {
        String phone = normalizePhone(req.getPhone());
        User user = userRepository.findByPhone(phone)
                .orElseThrow(() -> { zw.co.payport.gateway.observability.SecurityEvents.count(zw.co.payport.gateway.observability.SecurityEvents.LOGIN_FAILED, "customer"); return new RuntimeException("Invalid phone number or PIN"); });

        if (!user.getIsActive()) {
            throw new RuntimeException("Account is deactivated. Please contact support.");
        }

        if (user.getLockedUntil() != null && LocalDateTime.now().isBefore(user.getLockedUntil())) {
            throw new RuntimeException("Account temporarily locked. Please try again later.");
        }

        if (!passwordEncoder.matches(req.getPin(), user.getPinHash())) {
            user.setFailedPinAttempts(user.getFailedPinAttempts() + 1);
            if (user.getFailedPinAttempts() >= 5) {
                user.setLockedUntil(LocalDateTime.now().plusMinutes(15));
                userRepository.save(user);
                throw new RuntimeException("Too many failed attempts. Account locked for 15 minutes.");
            }
            userRepository.save(user);
            zw.co.payport.gateway.observability.SecurityEvents.count(zw.co.payport.gateway.observability.SecurityEvents.LOGIN_FAILED, "customer");
            throw new RuntimeException("Invalid phone number or PIN");
        }

        // Successful login
        user.setFailedPinAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(LocalDateTime.now());

        // Single-device login — only for customers, and only for clients that
        // actually send a deviceId. Logging in here becomes the one active
        // session; any token already issued to a different device stops
        // being accepted the next time it's used (checked in
        // JwtAuthenticationFilter). Old app builds that don't send deviceId
        // are completely unaffected.
        String deviceId = req.getDeviceId();
        if (deviceId != null && !deviceId.isBlank() && "CUSTOMER".equals(user.getRole())) {
            if (user.getActiveDeviceId() != null && !user.getActiveDeviceId().equals(deviceId)) {
                log.info("[AUTH] {} logging in from a new device — previous device session will be invalidated",
                        maskPhone(phone));
            }
            user.setActiveDeviceId(deviceId);
        }

        userRepository.save(user);

        log.info("[AUTH] Login successful: {} phone: {}", user.getFullName(), maskPhone(phone));

        String token = generateToken(user, deviceId);
        return buildAuthResponse(user, token);
    }

    /**
     * Ops/Admin login — requires role OPS, ADMIN, or FINANCE
     */
    public Map<String, Object> opsLogin(AuthRequests.OpsLoginRequest req) {
        String email = req.getEmail() == null ? null : req.getEmail().trim().toLowerCase();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Invalid credentials"));

        if (!user.getIsActive()) {
            throw new RuntimeException("Account is deactivated");
        }

        String role = user.getRole();
        if ("CUSTOMER".equals(role)) {
            throw new RuntimeException("Access denied. Ops console requires elevated privileges.");
        }

        // Permanent lock (3 failed attempts, or 30+ days unused) -- only an
        // ADMIN can clear this via the unlock endpoint. Deliberately not
        // time-based like the customer lockout: staff accounts guard
        // sensitive operations, so a wrong password shouldn't self-heal in
        // 15 minutes the way a customer's does.
        if (Boolean.TRUE.equals(user.getAccountLocked())) {
            throw new RuntimeException("Account locked after too many failed attempts. Contact an administrator to unlock it.");
        }

        if (!passwordEncoder.matches(req.getPassword(), user.getPinHash())) {
            user.setFailedPinAttempts(user.getFailedPinAttempts() + 1);
            if (user.getFailedPinAttempts() >= 3) {
                user.setAccountLocked(true);
                userRepository.save(user);
                log.warn("[AUTH] Ops account locked after 3 failed attempts: {}", email);
                zw.co.payport.gateway.observability.SecurityEvents.count(zw.co.payport.gateway.observability.SecurityEvents.ACCOUNT_LOCKED, "staff");
                throw new RuntimeException("Account locked after 3 failed attempts. Contact an administrator to unlock it.");
            }
            userRepository.save(user);
            zw.co.payport.gateway.observability.SecurityEvents.count(zw.co.payport.gateway.observability.SecurityEvents.LOGIN_FAILED, "staff");
            throw new RuntimeException("Invalid credentials");
        }

        // Password expiry (90 days) -- checked after the password itself is
        // confirmed correct, so a wrong-password attempt never leaks
        // whether the account's password happens to be expired.
        if (user.getPasswordSetAt() != null &&
                user.getPasswordSetAt().plusDays(90).isBefore(LocalDateTime.now())) {
            user.setMustChangePassword(true);
        }

        user.setFailedPinAttempts(0);
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        log.info("[AUTH] Ops login: {} ({}) email: {}", user.getFullName(), role, email);

        String token = generateToken(user);
        Map<String, Object> response = buildAuthResponse(user, token);
        response.put("mustChangePassword", Boolean.TRUE.equals(user.getMustChangePassword()));
        return response;
    }

    /**
     * Forgot password -- generates a time-limited reset token and "sends" it
     * by email to the address on file. Always returns success regardless of
     * whether the email exists, so this can't be used to enumerate valid
     * staff accounts.
     */
    public void forgotPassword(AuthRequests.ForgotPasswordRequest req) {
        String email = req.getEmail() == null ? null : req.getEmail().trim().toLowerCase();
        userRepository.findByEmail(email).ifPresent(user -> {
            String token = java.util.UUID.randomUUID().toString();
            user.setResetToken(token);
            user.setResetTokenExpiresAt(LocalDateTime.now().plusHours(1));
            userRepository.save(user);
            emailService.sendPasswordReset(user.getEmail(), user.getFullName(), token);
            log.info("[AUTH] Password reset requested for: {}", email);
        });
    }

    /**
     * Reset password using a valid token. Per policy: using this flow
     * always requires setting a genuinely new password, and clears
     * mustChangePassword and any permanent lock -- a successful reset is
     * itself proof of identity, equivalent to an admin unlock.
     */
    public void resetPassword(AuthRequests.ResetPasswordRequest req) {
        User user = userRepository.findByResetToken(req.getToken())
                .orElseThrow(() -> new RuntimeException("Invalid or expired reset link"));

        if (user.getResetTokenExpiresAt() == null || user.getResetTokenExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Invalid or expired reset link");
        }

        validatePasswordPolicy(req.getNewPassword());

        user.setPinHash(passwordEncoder.encode(req.getNewPassword()));
        user.setPasswordSetAt(LocalDateTime.now());
        user.setMustChangePassword(false);
        user.setAccountLocked(false);
        user.setFailedPinAttempts(0);
        user.setResetToken(null);
        user.setResetTokenExpiresAt(null);
        userRepository.save(user);

        log.info("[AUTH] Password reset completed for: {}", user.getEmail());
    }

    /**
     * Admin-only account unlock. Enforced at the controller via
     * @PreAuthorize, not here -- this method just does the unlock once
     * that's already confirmed.
     */
    public void unlockAccount(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        user.setAccountLocked(false);
        user.setFailedPinAttempts(0);
        userRepository.save(user);
        log.info("[AUTH] Account unlocked by admin: {}", user.getEmail());
    }

    /**
     * Change the caller's own PIN/password while logged in -- self-service
     * credential change for customers (4-digit PIN) and staff (full
     * password policy). They share the same pinHash field; which policy
     * applies depends on the caller's own role, never on what the client
     * sends.
     *
     * Deliberately exposed under a path outside /api/v1/auth/** -- that
     * whole prefix is permitAll for the login/reset flows. This falls
     * under the default anyRequest().authenticated() rule instead, so
     * Spring Security itself blocks an unauthenticated request before it
     * ever reaches this code -- not just a manual check inside the method.
     */
    public void changeCredential(String phone, String currentValue, String newValue) {
        User user = userRepository.findByPhone(phone)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!passwordEncoder.matches(currentValue, user.getPinHash())) {
            throw new RuntimeException("Current " + (isStaff(user) ? "password" : "PIN") + " is incorrect");
        }

        if (isStaff(user)) {
            validatePasswordPolicy(newValue);
            user.setPasswordSetAt(LocalDateTime.now());
        } else {
            if (newValue == null || !newValue.matches("\\d{4}")) {
                throw new RuntimeException("PIN must be exactly 4 digits");
            }
        }

        // Was only cleared in the staff branch above -- meant a customer
        // forced to change their PIN after a reset had no way to ever
        // clear that flag, a permanent lockout. Applies to both staff and
        // customers now. credentialsChangedAt is also updated here (not
        // just on an ops-initiated reset) so a voluntary PIN/password
        // change invalidates any already-issued token too, consistent
        // with the same "changing your credentials ends other sessions"
        // expectation an ops-initiated reset already has.
        user.setMustChangePassword(false);
        user.setCredentialsChangedAt(LocalDateTime.now());

        user.setPinHash(passwordEncoder.encode(newValue));
        userRepository.save(user);
        log.info("[AUTH] Credential changed by user: {}", isStaff(user) ? user.getEmail() : maskPhone(phone));
    }

    /**
     * Sets a customer's new PIN after the channel has already checked the current one (USSD and WhatsApp check it
     * on an earlier step, so the raw current PIN is not available here). Same effects as changeCredential.
     */
    public void setCustomerPin(String phone, String newPin) {
        User user = userRepository.findByPhone(phone).orElseThrow(() -> new RuntimeException("User not found"));
        if (isStaff(user)) throw new IllegalStateException("Staff change their password in the ops portal");
        if (newPin == null || !newPin.matches("\\d{4}")) throw new IllegalArgumentException("PIN must be exactly 4 digits");
        user.setMustChangePassword(false);
        user.setCredentialsChangedAt(LocalDateTime.now());
        user.setPinHash(passwordEncoder.encode(newPin));
        userRepository.save(user);
        log.info("[AUTH] PIN changed over a session channel: {}", maskPhone(phone));
    }

    private boolean isStaff(User user) {
        return !"CUSTOMER".equals(user.getRole());
    }

    /**
     * Password policy (item 7): at least 8 characters, one uppercase, one
     * lowercase, one digit, one special character. Applied at reset time
     * and whenever a staff password is set going forward. Public -- also
     * used at account creation time by OpsProductConfigController.
     */
    public void validatePasswordPolicy(String password) {
        if (password == null || password.length() < 8) {
            throw new RuntimeException("Password must be at least 8 characters long");
        }
        if (!password.matches(".*[A-Z].*")) {
            throw new RuntimeException("Password must contain at least one uppercase letter");
        }
        if (!password.matches(".*[a-z].*")) {
            throw new RuntimeException("Password must contain at least one lowercase letter");
        }
        if (!password.matches(".*\\d.*")) {
            throw new RuntimeException("Password must contain at least one digit");
        }
        if (!password.matches(".*[^A-Za-z0-9].*")) {
            throw new RuntimeException("Password must contain at least one special character");
        }
    }

    /**
     * Generate JWT token for authenticated user
     */
    private String generateToken(User user) {
        return generateToken(user, null);
    }

    private String generateToken(User user, String deviceId) {
        try {
            byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
            var key = Keys.hmacShaKeyFor(keyBytes);

            var builder = Jwts.builder()
                    .setSubject(user.getPhone())  // Fixed: was .subject(), now .setSubject()
                    .claim("userId", user.getId())
                    .claim("fullName", user.getFullName())
                    .claim("role", user.getRole());

            if (deviceId != null && !deviceId.isBlank()) {
                builder.claim("deviceId", deviceId);
            }

            return builder
                    .setIssuedAt(new Date())      // Fixed: was .issuedAt(), now .setIssuedAt()
                    .setExpiration(new Date(System.currentTimeMillis() + jwtExpirationMs)) // Fixed: was .expiration(), now .setExpiration()
                    .signWith(key)
                    .compact();
        } catch (Exception e) {
            log.error("[AUTH] Token generation failed: {}", e.getMessage());
            throw new RuntimeException("Authentication error");
        }
    }

    /**
     * Build authentication response with token and user data
     */
    private Map<String, Object> buildAuthResponse(User user, String token) {
        Map<String, Object> userMap = new HashMap<>();
        userMap.put("id", user.getId());
        userMap.put("fullName", user.getFullName());
        userMap.put("phone", user.getPhone());
        userMap.put("role", user.getRole());
        userMap.put("registeredAt", user.getRegisteredAt());
        userMap.put("mustChangePassword", user.getMustChangePassword());
        userMap.put("idNumber", user.getIdNumber());
        zw.co.payport.gateway.roles.StaffRoles.Role staff = staffRoles.of(user);
        if (staff != null) {
            userMap.put("customRole", user.getCustomRole());
            userMap.put("roleName", staff.name());
            userMap.put("permissions", staff.permissions());
            userMap.put("routes", zw.co.payport.gateway.roles.StaffRoles.routes(staff));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        response.put("user", userMap);
        return response;
    }

    /**
     * Mask phone number for logging
     */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) return "****";
        return phone.substring(0, 5) + "****" + phone.substring(phone.length() - 3);
    }
}