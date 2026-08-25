package zw.co.payport.gateway.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "webhook_deliveries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long webhookId;

    @Column(nullable = false)
    private Long checkoutSessionId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    @Builder.Default
    private Integer attemptCount = 0;

    private LocalDateTime nextAttemptAt;
    private LocalDateTime lastAttemptAt;
    private Integer responseStatus;
    private String responseBody;
    private LocalDateTime deliveredAt;

    /** PENDING | DELIVERED | FAILED */
    @Column(nullable = false)
    @Builder.Default
    private String status = "PENDING";

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
