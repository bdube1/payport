package zw.co.payport.gateway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import zw.co.payport.gateway.entity.AssistantConversation;

@Repository
public interface AssistantConversationRepository extends JpaRepository<AssistantConversation, String> {
}
