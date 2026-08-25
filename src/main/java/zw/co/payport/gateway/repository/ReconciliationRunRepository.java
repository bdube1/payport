package zw.co.payport.gateway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import zw.co.payport.gateway.entity.ReconciliationRun;
import java.util.List;

@Repository
public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, Long> {
    List<ReconciliationRun> findByProviderOrderByUploadedAtDesc(String provider);
    List<ReconciliationRun> findAllByOrderByUploadedAtDesc();
}
