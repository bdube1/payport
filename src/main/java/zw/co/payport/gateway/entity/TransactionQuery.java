package zw.co.payport.gateway.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "transaction_queries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionQuery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-facing, e.g. "QRY-20260822-4471" -- for searching and referencing the query outside the system. Null on legacy rows created before this existed. */
    private String referenceNumber;

    @Column(nullable = false)
    private Long checkoutSessionId;

    @Column(nullable = false)
    private Long merchantId;

    @Column(nullable = false)
    private Long raisedByMerchantUserId;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    /** OPEN | IN_PROGRESS | RESOLVED */
    @Column(nullable = false)
    @Builder.Default
    private String status = "OPEN";

    @Column(columnDefinition = "TEXT")
    private String opsResponse;

    private Long respondedByUserId;
    private LocalDateTime respondedAt;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime resolvedAt;
}
