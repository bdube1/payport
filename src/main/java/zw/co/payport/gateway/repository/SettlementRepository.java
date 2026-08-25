package zw.co.payport.gateway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import zw.co.payport.gateway.entity.Settlement;

import java.util.List;

@Repository
public interface SettlementRepository extends JpaRepository<Settlement, Long> {
    List<Settlement> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);
    List<Settlement> findAllByOrderByCreatedAtDesc();
}
