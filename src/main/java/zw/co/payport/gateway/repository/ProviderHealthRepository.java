package zw.co.payport.gateway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import zw.co.payport.gateway.entity.ProviderHealth;
import java.util.List;

@Repository
public interface ProviderHealthRepository extends JpaRepository<ProviderHealth, String> {
    List<ProviderHealth> findByProviderType(String providerType);
    List<ProviderHealth> findByStatusNot(String status);
}
