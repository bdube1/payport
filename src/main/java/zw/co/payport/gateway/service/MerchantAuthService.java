package zw.co.payport.gateway.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.entity.MerchantUser;
import zw.co.payport.gateway.model.request.MerchantRequests;
import zw.co.payport.gateway.repository.MerchantRepository;
import zw.co.payport.gateway.repository.MerchantUserRepository;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Merchant User login. Deliberately a separate token from the
 * customer/staff JWTs issued by AuthService -- a merchant user is a
 * different kind of principal entirely (tied to a Merchant, not a
 * User/phone), and giving it its own "typ": "merchant_user" claim means
 * a merchant token can never be accidentally accepted by the existing
 * customer/staff JwtAuthenticationFilter, or vice versa. Signed with the
 * same platform JWT secret as everything else -- no new secret to manage.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantAuthService {

    private final MerchantUserRepository merchantUserRepository;
    private final MerchantRepository merchantRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${payport.security.jwt-secret}")
    private String jwtSecret;

    @Value("${payport.security.jwt-expiration-ms:3600000}")
    private long jwtExpirationMs;

    public Map<String, Object> login(MerchantRequests.LoginRequest req) {
        String email = req.getEmail() == null ? null : req.getEmail().trim().toLowerCase();
        MerchantUser user = merchantUserRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Invalid credentials"));

        if (!"ACTIVE".equals(user.getStatus())) {
            throw new RuntimeException("This account has been deactivated");
        }
        if (Boolean.TRUE.equals(user.getAccountLocked())) {
            throw new RuntimeException("Account locked after too many failed attempts. Contact Payport support to unlock it.");
        }

        Merchant merchant = merchantRepository.findById(user.getMerchantId())
                .orElseThrow(() -> new RuntimeException("Merchant account not found"));
        if ("SUSPENDED".equals(merchant.getStatus()) || "CLOSED".equals(merchant.getStatus())) {
            throw new RuntimeException("This merchant account is " + merchant.getStatus().toLowerCase() + ". Contact Payport support.");
        }
        if ("REJECTED".equals(merchant.getStatus())) {
            throw new RuntimeException("This application was not approved.");
        }
        // PENDING/UNDER_REVIEW/UPDATE_REQUIRED are now allowed to log in --
        // the point is to let an applicant track their own application
        // status without calling Payport. The dashboard frontend is
        // responsible for showing the tracking page instead of the full
        // dashboard when merchantStatus isn't ACTIVE (see the "user" map
        // below, which carries merchantStatus for exactly that check).

        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            user.setFailedLoginAttempts(user.getFailedLoginAttempts() + 1);
            if (user.getFailedLoginAttempts() >= 3) {
                user.setAccountLocked(true);
                merchantUserRepository.save(user);
                log.warn("[MERCHANT_AUTH] Account locked after 3 failed attempts: {}", email);
                throw new RuntimeException("Account locked after 3 failed attempts. Contact Payport support to unlock it.");
            }
            merchantUserRepository.save(user);
            throw new RuntimeException("Invalid credentials");
        }

        user.setFailedLoginAttempts(0);
        user.setLastLoginAt(LocalDateTime.now());
        merchantUserRepository.save(user);

        log.info("[MERCHANT_AUTH] Login: {} ({}) for merchant {}", user.getFullName(), user.getRole(), merchant.getBusinessName());

        String token = generateToken(user);

        Map<String, Object> userMap = new HashMap<>();
        userMap.put("id", user.getId());
        userMap.put("fullName", user.getFullName());
        userMap.put("email", user.getEmail());
        userMap.put("role", user.getRole());
        userMap.put("mustChangePassword", user.getMustChangePassword());
        userMap.put("merchantId", merchant.getId());
        userMap.put("merchantName", merchant.getBusinessName());
        userMap.put("merchantStatus", merchant.getStatus());

        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        response.put("user", userMap);
        return response;
    }

    private String generateToken(MerchantUser user) {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        var key = Keys.hmacShaKeyFor(keyBytes);
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtExpirationMs);

        return Jwts.builder()
                .setSubject(user.getEmail())
                .claim("typ", "merchant_user")
                .claim("merchantUserId", user.getId())
                .claim("merchantId", user.getMerchantId())
                .claim("role", user.getRole())
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(key)
                .compact();
    }
}
