package zw.co.payport.gateway.psp.payment;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentCheckRepository extends JpaRepository<PaymentCheck, Long> {
    Optional<PaymentCheck> findByReference(String reference);
    List<PaymentCheck> findByStatusAndNextCheckAtBeforeOrderByNextCheckAtAsc(String status, LocalDateTime now, Pageable page);
    List<PaymentCheck> findByStatusOrderByFirstSeenAtAsc(String status);
}
