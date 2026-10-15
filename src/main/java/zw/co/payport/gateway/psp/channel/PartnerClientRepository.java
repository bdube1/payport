package zw.co.payport.gateway.psp.channel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PartnerClientRepository extends JpaRepository<PartnerClient, Long> {
    Optional<PartnerClient> findByKeyId(String keyId);
}
