package zw.co.payport.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.entity.CheckoutSession;
import zw.co.payport.gateway.model.response.TransactionResponse;
import zw.co.payport.gateway.repository.CheckoutSessionRepository;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Internal consistency reconciliation: does each CheckoutSession's
 * recorded status/amount actually agree with its own underlying
 * Transaction's status/amount? No payment provider in this codebase
 * exposes an API to fetch their own settlement statement, so comparing
 * against an actual external source of truth isn't automatable yet --
 * this instead catches Payport's own two records about the same payment
 * silently drifting apart, which is a real, worth-catching class of bug
 * on its own.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private static final Set<String> SUCCESS_TX_STATUSES = Set.of("COMPLETED");
    private static final Set<String> FAILURE_TX_STATUSES = Set.of("PAYMENT_FAILED", "FULFILMENT_FAILED", "CANCELLED");

    private final CheckoutSessionRepository checkoutSessionRepository;
    private final TransactionOrchestratorService orchestrator;

    public enum DiscrepancyType { STATUS_MISMATCH, AMOUNT_MISMATCH, TRANSACTION_NOT_FOUND }

    public record Discrepancy(
            Long checkoutSessionId, String sessionReference, DiscrepancyType type,
            String sessionStatus, String transactionStatus,
            Long sessionAmountCents, Long transactionAmountCents, String detail) {}

    public record ReconciliationReport(int sessionsChecked, int discrepanciesFound, List<Discrepancy> discrepancies) {}

    public ReconciliationReport run(int days) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        List<Discrepancy> discrepancies = new ArrayList<>();
        int checked = 0;

        for (CheckoutSession session : checkoutSessionRepository.findAll()) {
            if (session.getTransactionReference() == null) continue;
            if (session.getCreatedAt().isBefore(cutoff)) continue;
            checked++;

            TransactionResponse tx;
            try {
                tx = orchestrator.getTransaction(session.getTransactionReference());
            } catch (Exception e) {
                discrepancies.add(new Discrepancy(
                        session.getId(), session.getReference(), DiscrepancyType.TRANSACTION_NOT_FOUND,
                        session.getStatus(), null, session.getAmountCents(), null,
                        "Session references transaction " + session.getTransactionReference() + " but it could not be loaded: " + e.getMessage()));
                continue;
            }

            String txStatus = tx.getStatus() != null ? tx.getStatus().toString() : null;
            boolean sessionSaysPaid = "PAID".equals(session.getStatus()) || "PARTIALLY_REFUNDED".equals(session.getStatus()) || "REFUNDED".equals(session.getStatus());
            boolean sessionSaysFailed = "FAILED".equals(session.getStatus());
            boolean txSaysSuccess = SUCCESS_TX_STATUSES.contains(txStatus);
            boolean txSaysFailed = FAILURE_TX_STATUSES.contains(txStatus);

            if (sessionSaysPaid && txSaysFailed) {
                discrepancies.add(new Discrepancy(
                        session.getId(), session.getReference(), DiscrepancyType.STATUS_MISMATCH,
                        session.getStatus(), txStatus, session.getAmountCents(), tx.getAmountCents(),
                        "Session shows PAID but its transaction shows " + txStatus));
            } else if (sessionSaysFailed && txSaysSuccess) {
                discrepancies.add(new Discrepancy(
                        session.getId(), session.getReference(), DiscrepancyType.STATUS_MISMATCH,
                        session.getStatus(), txStatus, session.getAmountCents(), tx.getAmountCents(),
                        "Session shows FAILED but its transaction shows COMPLETED"));
            }

            if (tx.getAmountCents() != null && !tx.getAmountCents().equals(session.getAmountCents())) {
                discrepancies.add(new Discrepancy(
                        session.getId(), session.getReference(), DiscrepancyType.AMOUNT_MISMATCH,
                        session.getStatus(), txStatus, session.getAmountCents(), tx.getAmountCents(),
                        "Session amount (" + session.getAmountCents() + ") doesn't match transaction amount (" + tx.getAmountCents() + ")"));
            }
        }

        log.info("[RECONCILIATION] Checked {} sessions over {} days, found {} discrepancy(ies)", checked, days, discrepancies.size());
        return new ReconciliationReport(checked, discrepancies.size(), discrepancies);
    }
}
