package zw.co.payport.gateway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import zw.co.payport.gateway.entity.MerchantUser;

import java.util.List;
import java.util.Optional;

@Repository
public interface MerchantUserRepository extends JpaRepository<MerchantUser, Long> {
    Optional<MerchantUser> findByEmail(String email);
    boolean existsByEmail(String email);
    Optional<MerchantUser> findByResetToken(String resetToken);
    List<MerchantUser> findByMerchantId(Long merchantId);
}
