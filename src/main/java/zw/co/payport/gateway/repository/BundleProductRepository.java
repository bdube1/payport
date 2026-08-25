package zw.co.payport.gateway.repository;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import zw.co.payport.gateway.entity.BundleProduct;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BundleProductRepository extends JpaRepository<BundleProduct, Long> {

    // Find all active bundles for a provider
    List<BundleProduct> findByProviderCodeAndIsActiveTrueOrderByDisplayOrder(
            @Param("providerCode") String providerCode
    );

    // Find bundles by category
    List<BundleProduct> findByProviderCodeAndCategoryAndIsActiveTrueOrderByDisplayOrder(
            @Param("providerCode") String providerCode,
            @Param("category") String category
    );

    // Find specific bundle by code
    Optional<BundleProduct> findByProviderCodeAndBundleCode(
            @Param("providerCode") String providerCode,
            @Param("bundleCode") String bundleCode
    );

    // Find bundles with price in range
    @Query("SELECT b FROM BundleProduct b WHERE b.providerCode = :providerCode " +
            "AND b.isActive = true " +
            "AND b.priceUsdCents BETWEEN :minPrice AND :maxPrice " +
            "ORDER BY b.priceUsdCents")
    List<BundleProduct> findByPriceRange(
            @Param("providerCode") String providerCode,
            @Param("minPrice") Integer minPrice,
            @Param("maxPrice") Integer maxPrice
    );

    // Find bundles by validity
    List<BundleProduct> findByProviderCodeAndValidityDaysAndIsActiveTrue(
            @Param("providerCode") String providerCode,
            @Param("validityDays") Integer validityDays
    );

    // Search bundles by name
    @Query("SELECT b FROM BundleProduct b WHERE b.providerCode = :providerCode " +
            "AND b.isActive = true " +
            "AND LOWER(b.bundleName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
            "ORDER BY b.displayOrder")
    List<BundleProduct> searchBundles(
            @Param("providerCode") String providerCode,
            @Param("searchTerm") String searchTerm
    );

    // Get all distinct categories for a provider
    @Query("SELECT DISTINCT b.category FROM BundleProduct b WHERE b.providerCode = :providerCode AND b.category IS NOT NULL")
    List<String> findDistinctCategoriesByProviderCode(@Param("providerCode") String providerCode);

    // Deactivate old bundles (for Excel uploads)
    @Modifying
    @Query("UPDATE BundleProduct b SET b.isActive = false, b.updatedAt = :now " +
            "WHERE b.providerCode = :providerCode AND b.bundleCode NOT IN :activeCodes")
    int deactivateOldBundles(
            @Param("providerCode") String providerCode,
            @Param("activeCodes") List<String> activeCodes,
            @Param("now") LocalDateTime now
    );

    // Find bundles that need price updates
    @Query("SELECT b FROM BundleProduct b WHERE b.providerCode = :providerCode " +
            "AND b.effectiveFrom <= :now AND (b.effectiveTo IS NULL OR b.effectiveTo > :now)")
    List<BundleProduct> findEffectiveBundles(
            @Param("providerCode") String providerCode,
            @Param("now") LocalDateTime now
    );

    // Count active bundles by provider
    long countByProviderCodeAndIsActiveTrue(String providerCode);
}