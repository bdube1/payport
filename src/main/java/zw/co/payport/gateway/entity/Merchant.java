package zw.co.payport.gateway.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "merchants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Merchant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Public, non-guessable, given to the applicant at signup so they can track their own application without logging in anywhere. Never the internal id. */
    private String applicationReference;

    @Column(nullable = false)
    private String businessName;

    @Column(nullable = false, unique = true)
    private String registrationNumber;

    private String businessCategory;

    @Column(nullable = false)
    private String contactEmail;

    @Column(nullable = false)
    private String contactPhone;

    /** PENDING | ACTIVE | SUSPENDED | CLOSED | REJECTED -- see MerchantStatus. */
    @Column(nullable = false)
    @Builder.Default
    private String status = "PENDING";

    private String rejectionReason;

    /** BANK | ECOCASH | ONEMONEY -- required before LIVE keys unlock, not at signup. */
    private String payoutAccountType;

    /** Encrypted at rest once the payout-details feature lands; null through Phase 0. */
    private String payoutAccountDetails;

    /** Basis points; null means "use the platform default rate". */
    private Integer commissionRateBps;

    @Column(nullable = false)
    @Builder.Default
    private String defaultCurrency = "USD";

    /** 0-100, rule-based, computed once at signup. Helps ops triage a review queue -- never auto-approves. */
    @Column(nullable = false)
    @Builder.Default
    private Integer riskScore = 0;

    /** Comma-separated human-readable reasons behind the score, e.g. "free_email_domain, generic_business_name". */
    private String riskFlags;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime approvedAt;

    private Long approvedBy;
}
