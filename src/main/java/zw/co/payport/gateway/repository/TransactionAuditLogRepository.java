package zw.co.payport.gateway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import zw.co.payport.gateway.entity.TransactionAuditLog;
import java.util.List;

@Repository
public interface TransactionAuditLogRepository extends JpaRepository<TransactionAuditLog, Long> {
    List<TransactionAuditLog> findByTransactionReferenceOrderByCreatedAtAsc(String reference);
    long countByTransactionReference(String reference);
}
