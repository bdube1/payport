package zw.co.payport.gateway.psp.merchant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MerchantProfileRepository extends JpaRepository<MerchantProfile, Long> {
    Optional<MerchantProfile> findByMerchantId(Long merchantId);
    List<MerchantProfile> findByKycReviewDueAtBefore(LocalDateTime now);
    List<MerchantProfile> findByParentMerchantId(Long parentMerchantId);
    List<MerchantProfile> findBySuspensionScopeNot(String scope);
}
