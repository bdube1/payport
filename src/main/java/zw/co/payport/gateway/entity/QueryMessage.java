package zw.co.payport.gateway.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "query_messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QueryMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long queryId;

    @Column(nullable = false)
    private String senderType;

    @Column(nullable = false)
    private Long senderId;

    private String senderName;

    @Column(nullable = false)
    @Builder.Default
    private String eventType = "MESSAGE";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
