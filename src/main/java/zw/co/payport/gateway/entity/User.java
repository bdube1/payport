package zw.co.payport.gateway.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Registered user - both consumers and ops staff.
 * Role determines access: CUSTOMER | OPS | ADMIN | FINANCE
 * Consumers can always transact as guests without registering.
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "users", indexes = {
    @Index(name = "idx_users_phone", columnList = "phone", unique = true),
    @Index(name = "idx_users_role",  columnList = "role")
})
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String fullName;

    @Column(nullable = false, unique = true, length = 20)
    private String phone;              // +263XXXXXXXXX format - always normalized

    @Column(nullable = false, length = 100)
    private String pinHash;            // BCrypt hash: 4-digit PIN for customers, real password for staff

    /** Set whenever the PIN/password is reset -- any JWT issued before this moment is rejected on the next request, closing the "old token still works after a reset" gap. */
    private java.time.LocalDateTime credentialsChangedAt;

    @Column(unique = true, length = 255)
    private String email;              // required for OPS/ADMIN/FINANCE, unused for customers -- login identifier for staff and target for password reset

    private LocalDateTime passwordSetAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean mustChangePassword = false;

    /** Permanent lock, staff only -- distinct from lockedUntil below, which
     *  is the customer's short timed lockout. Only an ADMIN can clear this. */
    @Column(nullable = false)
    @Builder.Default
    private Boolean accountLocked = false;

    @Column(length = 255)
    private String resetToken;

    private LocalDateTime resetTokenExpiresAt;

    @Column(nullable = false, length = 20)
    private String role;               // CUSTOMER | OPS | ADMIN | FINANCE

    /** A configurable staff role (Roles & permissions); null = the built-in role above. */
    @Column(name = "custom_role", length = 40)
    private String customRole;

    /** National ID (or passport) number given at wallet registration. */
    @Column(name = "id_number", length = 40)
    private String idNumber;

    @Column(nullable = false)
    private Boolean isActive;

    @Column(nullable = false)
    private Boolean isVerified;

    @Column(length = 10)
    private String verificationCode;

    private LocalDateTime lastLoginAt;

    @Column(nullable = false)
    private Integer failedPinAttempts;

    private LocalDateTime lockedUntil;

    /** Single-device login for customers — set at login, checked on every
     *  authenticated request. Null = not enforced (either never logged in
     *  from a device-aware client, or an OPS/ADMIN/FINANCE account, which
     *  this is deliberately not applied to). */
    @Column(name = "active_device_id", length = 255)
    private String activeDeviceId;

    @Column(nullable = false)
    private LocalDateTime registeredAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        registeredAt = now;
        updatedAt = now;
        if (isActive == null)         isActive = true;
        if (isVerified == null)       isVerified = false;
        if (failedPinAttempts == null) failedPinAttempts = 0;
        if (role == null)             role = "CUSTOMER";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
