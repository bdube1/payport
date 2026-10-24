package zw.co.payport.gateway.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A single "intent to pay" created by a merchant's server via the API.
 * The customer is redirected to a hosted checkout page keyed by this
 * session's id, where they actually complete payment.
 */
@Entity
@Table(name = "checkout_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckoutSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long merchantId;

    /** Merchant-provided, unique per merchant (not globally unique). */
    @Column(nullable = false)
    private String reference;

    @Column(nullable = false)
    private Long amountCents;

    @Column(nullable = false)
    @Builder.Default
    private String currency = "USD";

    private String description;

    /** CREATED | PENDING | PAID | FAILED | EXPIRED | CANCELLED */
    @Column(nullable = false)
    @Builder.Default
    private String status = "CREATED";

    private String customerPhone;
    private String customerEmail;

    private String successUrl;
    private String cancelUrl;

    /** Merchant-defined passthrough JSON -- stored and returned verbatim, never interpreted. */
    @Column(columnDefinition = "TEXT")
    private String metadata;

    /** The underlying Transaction's reference, once payment is actually attempted. */
    private String transactionReference;

    /** The channel a pay-by-merchant-code payment came through (MOB, WEB, USD); null for API and payment links. */
    @Column(length = 10)
    private String channel;

    private Long apiKeyId;

    /**
     * True = real money (a real provider is called and the session can be settled);
     * false = test mode (payment is simulated and the session can never be settled).
     * Decided once, when the session is created, from the creating credential
     * (see CheckoutSessionService.resolveLivemode) and never changed afterwards.
     * Deliberately a nullable wrapper with no default: a code path that forgets
     * to set it fails at insert (NOT NULL column) instead of silently picking a mode.
     */
    @Column(nullable = false, updatable = false)
    private Boolean livemode;

    private String paymentMethodCode;

    /** The rate actually applied, frozen at payment time -- a later change to the merchant's rate must never retroactively alter a past collection. */
    private Integer commissionBpsApplied;
    private Long commissionAmountCents;
    private Long netAmountCents;

    /** Set once this session is claimed by a Settlement -- see SettlementService. Null means still pending settlement. */
    private Long settlementId;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();
}
