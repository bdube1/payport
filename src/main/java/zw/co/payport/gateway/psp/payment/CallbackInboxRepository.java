package zw.co.payport.gateway.psp.payment;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CallbackInboxRepository extends JpaRepository<CallbackInboxEntry, Long> {
    Optional<CallbackInboxEntry> findByProviderAndBodyHash(String provider, String bodyHash);
    List<CallbackInboxEntry> findByStatusInAndNextAttemptAtBeforeOrderByIdAsc(List<String> statuses, LocalDateTime now, Pageable page);
    List<CallbackInboxEntry> findByPayportReferenceOrderByIdAsc(String payportReference);
    List<CallbackInboxEntry> findTop200ByStatusOrderByIdDesc(String status);
}
