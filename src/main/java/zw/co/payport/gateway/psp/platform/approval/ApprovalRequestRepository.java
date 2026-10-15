package zw.co.payport.gateway.psp.platform.approval;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, Long> {
    Optional<ApprovalRequest> findByApprovalRef(String approvalRef);
    List<ApprovalRequest> findByStatusOrderByRequestedAtAsc(String status);
    List<ApprovalRequest> findByActionTypeAndTargetRefAndStatus(String actionType, String targetRef, String status);
    List<ApprovalRequest> findByStatusAndExpiresAtBefore(String status, LocalDateTime now);
    List<ApprovalRequest> findTop200ByOrderByRequestedAtDesc();
}
