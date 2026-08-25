package zw.co.payport.gateway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import zw.co.payport.gateway.entity.Merchant;

import java.util.List;
import java.util.Optional;

@Repository
public interface MerchantRepository extends JpaRepository<Merchant, Long> {
    boolean existsByRegistrationNumber(String registrationNumber);
    Optional<Merchant> findByRegistrationNumber(String registrationNumber);
    Optional<Merchant> findByApplicationReference(String applicationReference);
    List<Merchant> findByStatus(String status);
}
