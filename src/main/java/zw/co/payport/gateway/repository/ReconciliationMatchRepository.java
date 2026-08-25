package zw.co.payport.gateway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import zw.co.payport.gateway.entity.ReconciliationMatch;
import java.util.List;

@Repository
public interface ReconciliationMatchRepository extends JpaRepository<ReconciliationMatch, Long> {
    List<ReconciliationMatch> findByRunId(Long runId);
    List<ReconciliationMatch> findByRunIdAndMatchType(Long runId, String matchType);
}
