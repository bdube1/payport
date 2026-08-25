package zw.co.payport.gateway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import zw.co.payport.gateway.entity.ApiKey;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {
    Optional<ApiKey> findByPublicKey(String publicKey);
    Optional<ApiKey> findBySecretKeyHash(String secretKeyHash);
    List<ApiKey> findByMerchantId(Long merchantId);
    List<ApiKey> findByMerchantIdAndEnvironment(Long merchantId, String environment);
}
