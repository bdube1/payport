package zw.co.payport.gateway.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Data @Entity @Builder @NoArgsConstructor @AllArgsConstructor
@Table(name = "beneficiaries")
public class Beneficiary {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_phone", nullable = false, length = 20) private String userPhone;
    @Column(nullable = false, length = 10) private String type; // PHONE | BILLS
    @Column(nullable = false, length = 100) private String name;
    @Column(length = 20) private String phone;
    @Column(name = "network_name", length = 30) private String networkName;
    @Column(name = "network_color", length = 10) private String networkColor;
    @Column(name = "biller_code", length = 30) private String billerCode;
    @Column(name = "biller_name", length = 100) private String billerName;
    @Column(name = "account_ref", length = 100) private String accountRef;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

    @PrePersist protected void onCreate() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }
    @PreUpdate  protected void onUpdate() { updatedAt = LocalDateTime.now(); }
}
