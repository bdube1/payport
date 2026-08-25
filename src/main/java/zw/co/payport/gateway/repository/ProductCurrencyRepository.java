package zw.co.payport.gateway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import zw.co.payport.gateway.entity.ProductCurrency;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductCurrencyRepository extends JpaRepository<ProductCurrency, Long> {

    /** All active currencies for a given product — used by consumer portal */
    @Query("SELECT pc FROM ProductCurrency pc WHERE pc.productType.id = :productId AND pc.isActive = true ORDER BY pc.displayOrder")
    List<ProductCurrency> findActiveByProductId(@Param("productId") Long productId);

    /** All currencies (active + inactive) for a product — used by ops management */
    @Query("SELECT pc FROM ProductCurrency pc WHERE pc.productType.id = :productId ORDER BY pc.displayOrder")
    List<ProductCurrency> findAllByProductId(@Param("productId") Long productId);

    /** Single currency for a product — used for validation on transaction initiation */
    @Query("SELECT pc FROM ProductCurrency pc WHERE pc.productType.id = :productId AND pc.currency = :currency AND pc.isActive = true")
    Optional<ProductCurrency> findActiveByProductIdAndCurrency(@Param("productId") Long productId,
                                                                @Param("currency") String currency);

    /** All active currencies across all active products — for bulk catalogue response */
    @Query("SELECT pc FROM ProductCurrency pc WHERE pc.isActive = true AND pc.productType.isActive = true ORDER BY pc.productType.id, pc.displayOrder")
    List<ProductCurrency> findAllActiveForActiveProdcuts();

    /** Find by product code and currency */
    @Query("SELECT pc FROM ProductCurrency pc WHERE pc.productType.code = :code AND pc.currency = :currency")
    Optional<ProductCurrency> findByProductCodeAndCurrency(@Param("code") String code,
                                                            @Param("currency") String currency);
}
