package zw.co.payport.gateway.psp.platform.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChainSealRepository extends JpaRepository<ChainSeal, Long> {
    Optional<ChainSeal> findTopByChainNameOrderByToIdDesc(String chainName);
    List<ChainSeal> findByChainNameOrderByToIdAsc(String chainName);
}
