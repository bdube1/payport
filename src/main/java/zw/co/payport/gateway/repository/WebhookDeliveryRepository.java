package zw.co.payport.gateway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import zw.co.payport.gateway.entity.WebhookDelivery;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {
    List<WebhookDelivery> findByWebhookIdOrderByCreatedAtDesc(Long webhookId);
    List<WebhookDelivery> findByStatusAndNextAttemptAtBefore(String status, LocalDateTime cutoff);
}
