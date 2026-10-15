package zw.co.payport.gateway.psp.fx;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FxRateRepository extends JpaRepository<FxRate, Long> {
    Optional<FxRate> findByRateRef(String rateRef);
    Optional<FxRate> findTopByBaseCcyAndQuoteCcyAndStatusOrderByAsOfDesc(String base, String quote, String status);
    List<FxRate> findTop100ByOrderByAsOfDesc();
}
