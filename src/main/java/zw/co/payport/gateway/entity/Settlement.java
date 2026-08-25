package zw.co.payport.gateway.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "settlements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Settlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long merchantId;

    @Column(nullable = false)
    private LocalDateTime periodStart;

    @Column(nullable = false)
    private LocalDateTime periodEnd;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    @Builder.Default
    private Integer sessionCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Long grossAmountCents = 0L;

    @Column(nullable = false)
    @Builder.Default
    private Long commissionAmountCents = 0L;

    @Column(nullable = false)
    @Builder.Default
    private Long refundAmountCents = 0L;

    @Column(nullable = false)
    @Builder.Default
    private Long netAmountCents = 0L;

    /** PENDING | PROCESSING | PAID */
    @Column(nullable = false)
    @Builder.Default
    private String status = "PENDING";

    private String payoutReference;
    private LocalDateTime paidAt;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
