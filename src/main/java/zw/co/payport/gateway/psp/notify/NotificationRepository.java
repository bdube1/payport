package zw.co.payport.gateway.psp.notify;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    Optional<Notification> findByDedupeKey(String dedupeKey);
    List<Notification> findByStatusInAndNextAttemptAtBeforeOrderByIdAsc(List<String> statuses, LocalDateTime now, Pageable page);
    List<Notification> findTop100ByRecipientOrderByIdDesc(String recipient);
}
