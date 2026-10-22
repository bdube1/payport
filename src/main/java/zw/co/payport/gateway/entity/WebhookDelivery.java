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

    /** Null for events that are not about a checkout session (e.g. payouts). */
    private Long checkoutSessionId;

    /** Stable id of the event; a replay re-sends the same event id so receivers can de-duplicate. */
    private String eventId;

    /** The delivery this one replays (merchant-requested resend), if any. */
    private Long replayOf;

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
