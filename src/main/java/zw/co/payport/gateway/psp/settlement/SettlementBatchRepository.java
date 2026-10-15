package zw.co.payport.gateway.psp.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SettlementBatchRepository extends JpaRepository<SettlementBatch, Long> {
    Optional<SettlementBatch> findByBatchRef(String batchRef);
    List<SettlementBatch> findTop100ByOrderByCreatedAtDesc();
    List<SettlementBatch> findByCurrencyAndStatusIn(String currency, Collection<String> statuses);
}
