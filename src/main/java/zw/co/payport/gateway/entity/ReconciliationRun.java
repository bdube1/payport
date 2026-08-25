package zw.co.payport.gateway.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Data @Entity @Builder @NoArgsConstructor @AllArgsConstructor
@Table(name = "reconciliation_runs")
public class ReconciliationRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 30) private String provider;
    @Column(name = "uploaded_file_name", nullable = false) private String uploadedFileName;
    @Column(name = "uploaded_by", nullable = false, length = 100) private String uploadedBy;
    @Column(name = "uploaded_at", nullable = false) private LocalDateTime uploadedAt;
    @Column(name = "period_start") private LocalDateTime periodStart;
    @Column(name = "period_end") private LocalDateTime periodEnd;
    @Column(name = "total_provider_lines", nullable = false) @Builder.Default private Integer totalProviderLines = 0;
    @Column(name = "matched_count", nullable = false) @Builder.Default private Integer matchedCount = 0;
    @Column(name = "amount_mismatch_count", nullable = false) @Builder.Default private Integer amountMismatchCount = 0;
    @Column(name = "provider_only_count", nullable = false) @Builder.Default private Integer providerOnlyCount = 0;
    @Column(name = "payport_only_count", nullable = false) @Builder.Default private Integer payportOnlyCount = 0;
    @Column(nullable = false, length = 20) @Builder.Default private String status = "COMPLETED";
    @Column(name = "error_message", length = 1000) private String errorMessage;
    @PrePersist protected void onCreate() { if (uploadedAt == null) uploadedAt = LocalDateTime.now(); }
}
