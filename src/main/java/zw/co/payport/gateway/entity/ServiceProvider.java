package zw.co.payport.gateway.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Data @Entity @Builder @NoArgsConstructor @AllArgsConstructor
@Table(name = "service_providers")
public class ServiceProvider {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 50) private String code;
    @Column(nullable = false, length = 100) private String name;
    @Column(name = "provider_type", nullable = false, length = 30) private String providerType;
    @Column(name = "is_active", nullable = false) private Boolean isActive;
    @Column(name = "api_config", columnDefinition = "TEXT") private String apiConfig;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @PrePersist protected void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); if (isActive == null) isActive = false; }
}
