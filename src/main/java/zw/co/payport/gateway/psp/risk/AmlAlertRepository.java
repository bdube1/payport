package zw.co.payport.gateway.psp.risk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AmlAlertRepository extends JpaRepository<AmlAlert, Long> {
    Optional<AmlAlert> findByAlertRef(String alertRef);
    Optional<AmlAlert> findByAlertTypeAndSubjectRefAndDedupeKey(String alertType, String subjectRef, String dedupeKey);
    List<AmlAlert> findByStatusInOrderByCreatedAtDesc(Collection<String> statuses);
    List<AmlAlert> findByAlertTypeAndCreatedAtBetween(String alertType, LocalDateTime from, LocalDateTime to);
    List<AmlAlert> findByStatusAndDecidedAtBetween(String status, LocalDateTime from, LocalDateTime to);
}
