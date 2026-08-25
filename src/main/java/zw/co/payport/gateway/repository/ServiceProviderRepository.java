package zw.co.payport.gateway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import zw.co.payport.gateway.entity.ServiceProvider;
import java.util.List;
import java.util.Optional;

@Repository
public interface ServiceProviderRepository extends JpaRepository<ServiceProvider, Long> {
    Optional<ServiceProvider> findByCode(String code);
    List<ServiceProvider> findByIsActiveTrue();
    List<ServiceProvider> findByProviderType(String type);
}
