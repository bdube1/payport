package zw.co.payport.gateway.psp.merchant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MerchantLimitRepository extends JpaRepository<MerchantLimit, Long> {
    List<MerchantLimit> findByMerchantIdAndCurrency(Long merchantId, String currency);
    List<MerchantLimit> findByMerchantId(Long merchantId);
    Optional<MerchantLimit> findByMerchantIdAndCurrencyAndLimitType(Long merchantId, String currency, String limitType);

    /** Volume already attempted or collected through gateway checkout since a point in time: [sum, count]. */
    @Query(value = "SELECT COALESCE(SUM(amount_cents),0), COUNT(*) FROM checkout_sessions " +
            "WHERE merchant_id = :merchantId AND currency = :currency " +
            "AND status IN ('PENDING','PAID','PARTIALLY_REFUNDED','REFUNDED') AND created_at >= :since", nativeQuery = true)
    List<Object[]> checkoutVolumeSince(@Param("merchantId") Long merchantId, @Param("currency") String currency,
                                       @Param("since") LocalDateTime since);
}
