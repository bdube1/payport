package zw.co.payport.gateway.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Data @Entity @Builder @NoArgsConstructor @AllArgsConstructor
@Table(name = "assistant_conversations")
public class AssistantConversation {
    @Id @Column(length = 64) private String id;
    @Column(name = "started_at", nullable = false) private LocalDateTime startedAt;
    @Column(name = "last_activity_at", nullable = false) private LocalDateTime lastActivityAt;
    @Column(name = "message_count", nullable = false) @Builder.Default private Integer messageCount = 0;
    @Column(nullable = false, length = 30) @Builder.Default private String source = "WEBSITE";
    @PrePersist protected void onCreate() { if (startedAt == null) startedAt = LocalDateTime.now(); if (lastActivityAt == null) lastActivityAt = LocalDateTime.now(); }
}
