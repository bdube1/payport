package zw.co.payport.gateway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import zw.co.payport.gateway.entity.NotificationLog;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {
    List<NotificationLog> findByTransactionReference(String reference);
    List<NotificationLog> findByStatusAndCreatedAtBefore(String status, LocalDateTime cutoff);
}
