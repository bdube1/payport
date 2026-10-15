package zw.co.payport.gateway.psp.merchant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MerchantPayoutAccountRepository extends JpaRepository<MerchantPayoutAccount, Long> {
    Optional<MerchantPayoutAccount> findByPayoutRef(String payoutRef);
    List<MerchantPayoutAccount> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);
    List<MerchantPayoutAccount> findByMerchantIdAndCurrencyAndStatus(Long merchantId, String currency, String status);
}
