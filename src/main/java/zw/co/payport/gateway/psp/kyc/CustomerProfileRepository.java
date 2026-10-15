package zw.co.payport.gateway.psp.kyc;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomerProfileRepository extends JpaRepository<CustomerProfile, Long> {
    Optional<CustomerProfile> findByCustomerRef(String customerRef);
    Optional<CustomerProfile> findByMsisdn(String msisdn);
    Optional<CustomerProfile> findByNationalIdIndex(String nationalIdIndex);
    List<CustomerProfile> findBySanctionsStatus(String sanctionsStatus);
    long countByKycTier(String kycTier);
}
