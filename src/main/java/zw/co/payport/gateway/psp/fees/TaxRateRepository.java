package zw.co.payport.gateway.psp.fees;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaxRateRepository extends JpaRepository<TaxRate, Long> {
    List<TaxRate> findByTaxCodeAndCurrencyAndStatus(String taxCode, String currency, String status);
    List<TaxRate> findByStatusOrderByTaxCodeAsc(String status);
    List<TaxRate> findAllByOrderByCreatedAtDesc();
}
