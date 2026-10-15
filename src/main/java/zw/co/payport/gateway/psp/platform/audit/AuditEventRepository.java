package zw.co.payport.gateway.psp.platform.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findByObjectTypeAndObjectRefOrderByIdAsc(String objectType, String objectRef);

    List<AuditEvent> findByActorOrderByIdDesc(String actor, Pageable page);

    @Query("SELECT e FROM AuditEvent e WHERE e.id > :afterId AND e.createdAt < :cutoff ORDER BY e.id ASC")
    List<AuditEvent> findSealable(@Param("afterId") long afterId, @Param("cutoff") LocalDateTime cutoff, Pageable page);

    @Query("SELECT e FROM AuditEvent e WHERE e.id BETWEEN :fromId AND :toId ORDER BY e.id ASC")
    List<AuditEvent> findRange(@Param("fromId") long fromId, @Param("toId") long toId);
}
