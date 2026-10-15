package zw.co.payport.gateway.psp.risk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface RiskEventRepository extends JpaRepository<RiskEvent, Long> {

    /** [count, sumUsd, distinctCounterparties] for a subject since a time (BLOCKed attempts excluded). */
    @Query("SELECT COUNT(e), COALESCE(SUM(e.amountUsdMinor),0), COUNT(DISTINCT e.counterpartyRef) FROM RiskEvent e " +
           "WHERE e.subjectRef = :subject AND e.createdAt >= :since AND e.decision <> 'BLOCK'")
    List<Object[]> stats(@Param("subject") String subjectRef, @Param("since") LocalDateTime since);

    @Query("SELECT COUNT(e) FROM RiskEvent e WHERE e.subjectRef = :subject AND e.counterpartyRef = :cp AND e.decision <> 'BLOCK'")
    long countWithCounterparty(@Param("subject") String subjectRef, @Param("cp") String counterpartyRef);

    @Modifying
    @Query("DELETE FROM RiskEvent e WHERE e.createdAt < :before")
    int purgeBefore(@Param("before") LocalDateTime before);
}
