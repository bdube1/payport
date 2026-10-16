package zw.co.payport.gateway.recon;

import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.model.response.TransactionResponse;
import zw.co.payport.gateway.platform.ApprovalStore;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

/**
 * Maker-checker for a late payment (provider confirmed after the customer was told the
 * payment timed out): one person requests "honour" (deliver), a different FINANCE person
 * approves, and the approval and the confirmation commit together. Refusing it leaves the
 * exception open for a refund (recorded with ReconExceptionStore.resolveById).
 */
@Service
public class LatePaymentService {

    public static final String ACTION = "HONOUR_LATE_PAYMENT";

    private final DataSource dataSource;
    private final TransactionOrchestratorService orchestrator;
    private final ReconExceptionStore exceptions;

    public LatePaymentService(DataSource dataSource, TransactionOrchestratorService orchestrator, ReconExceptionStore exceptions) {
        this.dataSource = dataSource;
        this.orchestrator = orchestrator;
        this.exceptions = exceptions;
    }

    @Transactional
    public ApprovalStore.Approval requestHonour(String reference, String reason, String requester) throws SQLException {
        boolean open = exceptions.forTransaction(reference).stream()
                .anyMatch(e -> "PAID_AFTER_TIMEOUT".equals(e.get("category")) && "OPEN".equals(e.get("status")));
        if (!open) throw new IllegalStateException("No open PAID_AFTER_TIMEOUT exception for " + reference);
        Connection c = DataSourceUtils.getConnection(dataSource);
        return ApprovalStore.request(c, ACTION, reference, "{}", reason, "FINANCE", requester, 48);
    }

    @Transactional
    public TransactionResponse approveHonour(long approvalId, String decider, Set<String> roles, String note) throws SQLException {
        Connection c = DataSourceUtils.getConnection(dataSource);
        ApprovalStore.Approval a = ApprovalStore.load(c, approvalId, false);
        if (!ACTION.equals(a.actionType())) throw new IllegalArgumentException("Approval " + approvalId + " is not a late-payment honour request");
        ApprovalStore.Approval decided = ApprovalStore.decide(c, approvalId, true, decider, roles, note);
        return orchestrator.honourLatePayment(decided.subjectRef(), decided.ref(), decider);
    }
}
