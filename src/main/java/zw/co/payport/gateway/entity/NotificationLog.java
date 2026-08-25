package zw.co.payport.gateway.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Data @Entity @Builder @NoArgsConstructor @AllArgsConstructor
@Table(name = "notification_log")
public class NotificationLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "transaction_reference", length = 24) private String transactionReference;
    @Column(name = "recipient_phone", nullable = false, length = 20) private String recipientPhone;
    @Column(name = "message_type", nullable = false, length = 50) private String messageType;
    @Column(name = "message_body", nullable = false, columnDefinition = "TEXT") private String messageBody;
    @Column(nullable = false, length = 30) private String provider;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "provider_message_id", length = 100) private String providerMessageId;
    @Column(name = "error_message", length = 500) private String errorMessage;
    @Column(name = "sent_at") private LocalDateTime sentAt;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @PrePersist protected void onCreate() { createdAt = LocalDateTime.now(); if (status == null) status = "PENDING"; if (provider == null) provider = "AFRICAS_TALKING"; }
}
