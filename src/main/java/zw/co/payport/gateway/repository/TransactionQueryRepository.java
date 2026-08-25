package zw.co.payport.gateway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import zw.co.payport.gateway.entity.TransactionQuery;

import java.util.List;

@Repository
public interface TransactionQueryRepository extends JpaRepository<TransactionQuery, Long> {
    List<TransactionQuery> findByCheckoutSessionIdOrderByCreatedAtDesc(Long checkoutSessionId);
    List<TransactionQuery> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);
    List<TransactionQuery> findAllByOrderByCreatedAtDesc();
    java.util.Optional<TransactionQuery> findByReferenceNumber(String referenceNumber);
}
