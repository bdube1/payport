package zw.co.payport.gateway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import zw.co.payport.gateway.entity.PaymentMethod;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, Long> {
    Optional<PaymentMethod> findByCodeAndIsActiveTrue(String code);
    Optional<PaymentMethod> findByCode(String code);
    List<PaymentMethod> findByIsActiveTrueOrderByDisplayOrder();
}
