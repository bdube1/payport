package zw.co.payport.gateway.psp.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SettlementInstructionRepository extends JpaRepository<SettlementInstruction, Long> {
    Optional<SettlementInstruction> findByInstructionRef(String instructionRef);
    List<SettlementInstruction> findByBatchIdOrderByIdAsc(Long batchId);
    List<SettlementInstruction> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);
    List<SettlementInstruction> findByMerchantIdAndStatusIn(Long merchantId, Collection<String> statuses);
    List<SettlementInstruction> findByStatusIn(Collection<String> statuses);
    Optional<SettlementInstruction> findByRailReference(String railReference);
}
