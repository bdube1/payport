package zw.co.payport.gateway.psp.fees;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FeeAgreementRepository extends JpaRepository<FeeAgreement, Long> {
    Optional<FeeAgreement> findByAgreementRef(String agreementRef);
    List<FeeAgreement> findByMerchantIdAndCurrencyAndStatus(Long merchantId, String currency, String status);
    List<FeeAgreement> findByMerchantIdIsNullAndCurrencyAndStatus(String currency, String status);
    List<FeeAgreement> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);
    List<FeeAgreement> findByMerchantIdIsNullOrderByCreatedAtDesc();
}
