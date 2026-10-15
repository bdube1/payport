package zw.co.payport.gateway.psp.disputes;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomerRefundRepository extends JpaRepository<CustomerRefund, Long> {
    Optional<CustomerRefund> findByRefundRef(String refundRef);
    Optional<CustomerRefund> findBySourceTypeAndSourceRef(String sourceType, String sourceRef);
    List<CustomerRefund> findByStatusOrderByCreatedAtAsc(String status);
    List<CustomerRefund> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);
}
