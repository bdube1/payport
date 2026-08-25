package zw.co.payport.gateway.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A person who can log into the merchant dashboard on behalf of a Merchant.
 * Security fields deliberately mirror the staff User entity (V14 migration)
 * -- same lockout policy, same password-expiry pattern -- rather than
 * inventing a second auth scheme to maintain.
 */
@Entity
@Table(name = "merchant_users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MerchantUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long merchantId;

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    /** OWNER | DEVELOPER | FINANCE -- see MerchantRole. */
    @Column(nullable = false)
    @Builder.Default
    private String role = "OWNER";

    /** ACTIVE | DEACTIVATED */
    @Column(nullable = false)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(nullable = false)
    @Builder.Default
    private Integer failedLoginAttempts = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean accountLocked = false;

    private LocalDateTime passwordSetAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean mustChangePassword = false;

    private String resetToken;
    private LocalDateTime resetTokenExpiresAt;

    private LocalDateTime lastLoginAt;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
