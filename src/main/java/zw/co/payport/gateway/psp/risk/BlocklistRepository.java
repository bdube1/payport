package zw.co.payport.gateway.psp.risk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BlocklistRepository extends JpaRepository<BlocklistEntry, Long> {
    List<BlocklistEntry> findByEntryValueIn(Collection<String> values);
    Optional<BlocklistEntry> findByEntryTypeAndEntryValue(String type, String value);
}
