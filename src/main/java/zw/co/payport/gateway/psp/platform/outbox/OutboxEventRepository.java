package zw.co.payport.gateway.psp.platform.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Claims a batch for this node. Safe across several app instances: a row
     * is only claimable when unlocked or its lease has expired.
     */
    @Modifying
    @Query(value = "UPDATE psp_outbox_events SET locked_by = :node, locked_until = :leaseUntil " +
            "WHERE status IN ('NEW','RETRY') AND next_attempt_at <= :now " +
            "AND (locked_until IS NULL OR locked_until < :now) ORDER BY id LIMIT :batch", nativeQuery = true)
    int claimBatch(@Param("node") String node, @Param("now") LocalDateTime now,
                   @Param("leaseUntil") LocalDateTime leaseUntil, @Param("batch") int batch);

    List<OutboxEvent> findByLockedByAndStatusInOrderByIdAsc(String lockedBy, List<String> statuses);

    long countByStatus(String status);

    List<OutboxEvent> findTop100ByStatusOrderByIdDesc(String status);

    List<OutboxEvent> findByAggregateTypeAndAggregateIdOrderByIdAsc(String aggregateType, String aggregateId);
}
