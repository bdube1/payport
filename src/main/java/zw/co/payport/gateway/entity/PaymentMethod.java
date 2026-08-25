package zw.co.payport.gateway.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Data @Entity @Builder @NoArgsConstructor @AllArgsConstructor
@Table(name = "payment_methods")
public class PaymentMethod {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 50) private String code;
    @Column(nullable = false, length = 100) private String name;
    @Column(name = "is_active", nullable = false) private Boolean isActive;
    @Column(name = "gateway_config", columnDefinition = "TEXT") private String gatewayConfig;
    @Column(name = "display_order", nullable = false) private Integer displayOrder;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @PrePersist protected void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); if (isActive == null) isActive = false; if (displayOrder == null) displayOrder = 0; }
}
