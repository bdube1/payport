package zw.co.payport.gateway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import zw.co.payport.gateway.entity.ProductType;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductTypeRepository extends JpaRepository<ProductType, Long> {
    Optional<ProductType> findByCodeAndIsActiveTrue(String code);
    List<ProductType> findByIsActiveTrueOrderByCategory();
    List<ProductType> findByCategoryAndIsActiveTrue(String category);
}
