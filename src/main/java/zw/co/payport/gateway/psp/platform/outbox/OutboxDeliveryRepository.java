package zw.co.payport.gateway.psp.platform.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxDeliveryRepository extends JpaRepository<OutboxDelivery, Long> {
    boolean existsByEventIdAndHandler(String eventId, String handler);
}
