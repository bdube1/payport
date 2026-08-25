package zw.co.payport.gateway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import zw.co.payport.gateway.entity.Beneficiary;
import java.util.List;
import java.util.Optional;

@Repository
public interface BeneficiaryRepository extends JpaRepository<Beneficiary, Long> {
    List<Beneficiary> findByUserPhoneOrderByCreatedAtDesc(String userPhone);
    Optional<Beneficiary> findByIdAndUserPhone(Long id, String userPhone);
    void deleteByIdAndUserPhone(Long id, String userPhone);
}
