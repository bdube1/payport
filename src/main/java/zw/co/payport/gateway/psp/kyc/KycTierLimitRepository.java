package zw.co.payport.gateway.psp.kyc;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface KycTierLimitRepository extends JpaRepository<KycTierLimit, Long> {
    Optional<KycTierLimit> findByKycTierAndCurrency(String kycTier, String currency);
}
