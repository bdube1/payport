package zw.co.payport.gateway.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "refunds")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long checkoutSessionId;

    @Column(nullable = false)
    private Long amountCents;

    private String reason;

    /** PENDING | COMPLETED | FAILED */
    @Column(nullable = false)
    @Builder.Default
    private String status = "PENDING";

    /** MERCHANT_USER | STAFF */
    @Column(nullable = false)
    private String initiatedByType;

    @Column(nullable = false)
    private Long initiatedById;

    private String providerReference;

    /** PSP: the customer-refund record in the ledger (psp_customer_refunds.refund_ref) that funds and tracks payout of this refund. */
    private String pspRefundRef;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
