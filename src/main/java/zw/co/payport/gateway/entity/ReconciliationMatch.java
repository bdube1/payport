package zw.co.payport.gateway.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Data @Entity @Builder @NoArgsConstructor @AllArgsConstructor
@Table(name = "reconciliation_matches")
public class ReconciliationMatch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "run_id", nullable = false) private Long runId;
    @Column(name = "match_type", nullable = false, length = 20) private String matchType; // MATCHED, AMOUNT_MISMATCH, PROVIDER_ONLY, PAYPORT_ONLY
    @Column(name = "match_confidence", length = 20) private String matchConfidence;        // EXACT_REFERENCE, FUZZY, NONE
    @Column(name = "transaction_reference", length = 24) private String transactionReference;
    @Column(name = "provider_reference", length = 255) private String providerReference;
    @Column(name = "provider_amount_cents") private Long providerAmountCents;
    @Column(name = "payport_amount_cents") private Long payportAmountCents;
    @Column(name = "provider_phone", length = 20) private String providerPhone;
    @Column(name = "provider_timestamp") private LocalDateTime providerTimestamp;
    @Column(name = "raw_provider_row", columnDefinition = "TEXT") private String rawProviderRow;
    @Column(columnDefinition = "TEXT") private String notes;
    @Column(nullable = false) @Builder.Default private Boolean resolved = false;
    @Column(name = "resolved_by", length = 100) private String resolvedBy;
    @Column(name = "resolved_at") private LocalDateTime resolvedAt;
}
