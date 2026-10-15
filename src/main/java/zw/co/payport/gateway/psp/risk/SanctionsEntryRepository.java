package zw.co.payport.gateway.psp.risk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SanctionsEntryRepository extends JpaRepository<SanctionsEntry, Long> {
    List<SanctionsEntry> findByActiveTrue();
    List<SanctionsEntry> findByListCode(String listCode);
    Optional<SanctionsEntry> findByListCodeAndExternalId(String listCode, String externalId);
}
