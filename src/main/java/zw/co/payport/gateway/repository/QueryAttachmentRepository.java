package zw.co.payport.gateway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import zw.co.payport.gateway.entity.QueryAttachment;

import java.util.List;

@Repository
public interface QueryAttachmentRepository extends JpaRepository<QueryAttachment, Long> {
    List<QueryAttachment> findByQueryIdOrderByCreatedAtDesc(Long queryId);
}
