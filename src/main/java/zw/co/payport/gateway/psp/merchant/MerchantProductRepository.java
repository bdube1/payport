package zw.co.payport.gateway.psp.merchant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MerchantProductRepository extends JpaRepository<MerchantProduct, Long> {
    List<MerchantProduct> findByMerchantId(Long merchantId);
    Optional<MerchantProduct> findByMerchantIdAndPaymentMethodCodeAndCurrency(Long merchantId, String paymentMethodCode, String currency);
}
