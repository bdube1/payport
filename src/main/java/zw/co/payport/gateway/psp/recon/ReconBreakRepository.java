package zw.co.payport.gateway.psp.recon;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReconBreakRepository extends JpaRepository<ReconBreak, Long> {
    Optional<ReconBreak> findBySourceAndBreakTypeAndReference(String source, String breakType, String reference);
    Optional<ReconBreak> findByBreakRef(String breakRef);
    List<ReconBreak> findByStatusInOrderByLastSeenAtDesc(Collection<String> statuses);
    List<ReconBreak> findByReferenceOrderByFirstSeenAtAsc(String reference);
    long countByStatusIn(Collection<String> statuses);

    @Query("SELECT b.breakType, COUNT(b) FROM ReconBreak b WHERE b.status IN ('OPEN','INVESTIGATING') GROUP BY b.breakType")
    List<Object[]> openCountsByType();

    /** Unresolved mismatches from the gateway's statement reconciliation runs, after a watermark. */
    @Query(value = "SELECT m.id, r.provider, m.match_type, m.transaction_reference, m.provider_reference, " +
            "m.provider_amount_cents, m.payport_amount_cents, m.provider_timestamp FROM reconciliation_matches m " +
            "JOIN reconciliation_runs r ON r.id = m.run_id WHERE m.match_type <> 'MATCHED' AND m.resolved = false " +
            "AND m.id > :afterId ORDER BY m.id LIMIT 1000", nativeQuery = true)
    List<Object[]> statementMismatchesAfter(@org.springframework.data.repository.query.Param("afterId") long afterId);
}
