package zw.co.payport.gateway.psp.risk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RiskCaseRepository extends JpaRepository<RiskCase, Long> {
    Optional<RiskCase> findByCaseRef(String caseRef);
    List<RiskCase> findByStatusInOrderByCreatedAtDesc(Collection<String> statuses);
    List<RiskCase> findBySubjectRefOrderByCreatedAtDesc(String subjectRef);
    long countByCaseTypeAndCreatedAtBetween(String caseType, LocalDateTime from, LocalDateTime to);
    List<RiskCase> findByCreatedAtBetween(LocalDateTime from, LocalDateTime to);
}
