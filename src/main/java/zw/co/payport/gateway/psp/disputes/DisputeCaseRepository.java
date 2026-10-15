package zw.co.payport.gateway.psp.disputes;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DisputeCaseRepository extends JpaRepository<DisputeCase, Long> {
    Optional<DisputeCase> findByDisputeId(Long disputeId);
    List<DisputeCase> findByStatusAndRespondByBefore(String status, LocalDateTime when);
    List<DisputeCase> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);
    List<DisputeCase> findByCreatedAtBetween(LocalDateTime from, LocalDateTime to);
}
