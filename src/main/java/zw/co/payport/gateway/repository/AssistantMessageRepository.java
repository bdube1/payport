package zw.co.payport.gateway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import zw.co.payport.gateway.entity.AssistantMessage;
import java.util.List;

@Repository
public interface AssistantMessageRepository extends JpaRepository<AssistantMessage, Long> {
    // Ordered oldest-first so conversation history reconstructs correctly
    // when replayed back to the model.
    List<AssistantMessage> findByConversationIdOrderByCreatedAtAsc(String conversationId);
}
