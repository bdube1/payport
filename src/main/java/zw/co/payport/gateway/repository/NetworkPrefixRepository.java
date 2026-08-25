package zw.co.payport.gateway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import zw.co.payport.gateway.entity.NetworkPrefix;
import java.util.Optional;

@Repository
public interface NetworkPrefixRepository extends JpaRepository<NetworkPrefix, Long> {
    Optional<NetworkPrefix> findByPrefix(String prefix);
}
