package zw.co.payport.gateway.psp.fx;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FxConversionRepository extends JpaRepository<FxConversion, Long> {
    Optional<FxConversion> findByConversionRef(String conversionRef);
    List<FxConversion> findTop50ByCustomerRefOrderByCreatedAtDesc(String customerRef);
}
