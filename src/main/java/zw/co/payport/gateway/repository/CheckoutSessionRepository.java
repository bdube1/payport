package zw.co.payport.gateway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import zw.co.payport.gateway.entity.CheckoutSession;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CheckoutSessionRepository extends JpaRepository<CheckoutSession, Long> {
    Optional<CheckoutSession> findByMerchantIdAndReference(Long merchantId, String reference);
    Optional<CheckoutSession> findByTransactionReference(String transactionReference);
    List<CheckoutSession> findByStatusAndExpiresAtBefore(String status, LocalDateTime cutoff);
    List<CheckoutSession> findByMerchantId(Long merchantId);
    List<CheckoutSession> findByMerchantIdAndSettlementIdIsNull(Long merchantId);
}
