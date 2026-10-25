package zw.co.payport.gateway.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "provider_health")
public class ProviderHealth {

    @Id
    @Column(name = "provider_code", length = 50)
    private String providerCode;

    @Column(name = "provider_type", nullable = false, length = 20)
    private String providerType;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "detail", length = 500)
    private String detail;

    // REMOVE this line - column doesn't exist in DB
    // @Column(name = "last_status_detail", length = 500)
    // private String lastStatusDetail;

    @Column(name = "success_rate_10m", precision = 5, scale = 2)
    private BigDecimal successRate10m;

    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;

    @Column(name = "manual_override", nullable = false)
    private Boolean manualOverride;

    @Column(name = "override_reason", length = 200)
    private String overrideReason;

    @Column(name = "override_by", length = 100)
    private String overrideBy;

    @Column(name = "override_at")
    private LocalDateTime overrideAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        if (manualOverride == null) manualOverride = false;
        if (status == null) status = "NOT_CHECKED";
    }
}