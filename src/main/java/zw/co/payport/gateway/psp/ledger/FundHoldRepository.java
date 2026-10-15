package zw.co.payport.gateway.psp.ledger;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FundHoldRepository extends JpaRepository<FundHold, Long> {
    Optional<FundHold> findByHoldRef(String holdRef);
    List<FundHold> findByStatusAndReleaseAtBeforeOrderByReleaseAtAsc(String status, LocalDateTime now, Pageable page);
    List<FundHold> findByReserveStatusAndReserveReleaseAtBeforeOrderByReserveReleaseAtAsc(String status, LocalDateTime now, Pageable page);
    List<FundHold> findByMerchantIdAndStatusOrderByReleaseAtAsc(Long merchantId, String status);
}
