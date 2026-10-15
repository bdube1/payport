package zw.co.payport.gateway.psp.risk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SanctionsListVersionRepository extends JpaRepository<SanctionsListVersion, Long> {
    Optional<SanctionsListVersion> findTopByListCodeAndStatusOrderByFetchedAtDesc(String listCode, String status);
    List<SanctionsListVersion> findTop50ByOrderByFetchedAtDesc();
}
