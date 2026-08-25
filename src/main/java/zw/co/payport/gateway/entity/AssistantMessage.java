package zw.co.payport.gateway.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Data @Entity @Builder @NoArgsConstructor @AllArgsConstructor
@Table(name = "assistant_messages")
public class AssistantMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "conversation_id", nullable = false, length = 64) private String conversationId;
    @Column(nullable = false, length = 10) private String role; // USER or ASSISTANT
    @Column(nullable = false, columnDefinition = "TEXT") private String content;
    @Column(name = "was_fallback", nullable = false) @Builder.Default private Boolean wasFallback = false;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @PrePersist protected void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
