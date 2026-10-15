package zw.co.payport.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.entity.CheckoutSession;
import zw.co.payport.gateway.entity.Refund;
import zw.co.payport.gateway.repository.CheckoutSessionRepository;
import zw.co.payport.gateway.repository.RefundRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Refund tracking and workflow -- deliberately not called "refund
 * execution". PSP: refunds are now funded from the merchant's ledger balance
 * and paid out through the PSP refunds desk; "COMPLETED" here means the refund
 * is approved and funded, the payout status lives on psp_customer_refunds. No payment provider adaptor in this codebase (EcoCash,
 * OneMoney, Omari, ZimSwitch) has a refund-execution method built, so
 * this records the refund, moves the session/balance state, and marks
 * itself COMPLETED -- but doesn't actually reverse money through a
 * mobile money network. That gap is surfaced honestly in the API
 * response (see RefundController), not hidden behind an optimistic
 * "COMPLETED" that implies more than what happened.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final RefundRepository refundRepository;
    private final CheckoutSessionRepository checkoutSessionRepository;
    /** Lazy: the PSP refunds service reads gateway data through a bridge that itself uses this service. */
    private final org.springframework.beans.factory.ObjectProvider<zw.co.payport.gateway.psp.disputes.DisputesAndRefundsService> pspRefunds;

    public record RefundRequest(Long amountCents, String reason) {}

    @org.springframework.transaction.annotation.Transactional
    public Refund createRefund(CheckoutSession session, RefundRequest req, String initiatedByType, Long initiatedById) {
        if (!"PAID".equals(session.getStatus()) && !"PARTIALLY_REFUNDED".equals(session.getStatus())) {
            throw new RuntimeException("Only a PAID session can be refunded (current status: " + session.getStatus() + ")");
        }

        long alreadyRefunded = totalRefunded(session.getId());
        long refundableRemaining = session.getAmountCents() - alreadyRefunded;

        long amount = req.amountCents() != null ? req.amountCents() : refundableRemaining;
        if (amount <= 0) {
            throw new RuntimeException("Refund amount must be greater than zero");
        }
        if (amount > refundableRemaining) {
            throw new RuntimeException("Refund amount exceeds what's left to refund (" +
                    refundableRemaining + " cents remaining)");
        }

        // PSP: debit the merchant's ledger balance (available first, then pending) and create the
        // customer-refund liability. Throws (HTTP 422 INSUFFICIENT_FUNDS) if the merchant cannot cover it,
        // so a refund can no longer be recorded without the money to back it. The refund is then paid
        // out to the customer from the PSP refunds desk (rail or manual, with a payout reference).
        String pspRefundRef = pspRefunds.getObject().fundMerchantRefund(session.getId(), amount, session.getCustomerPhone(),
                initiatedByType + ":" + initiatedById);

        Refund refund = Refund.builder()
                .checkoutSessionId(session.getId())
                .amountCents(amount)
                .reason(req.reason())
                .pspRefundRef(pspRefundRef)
                .status("COMPLETED")
                .initiatedByType(initiatedByType)
                .initiatedById(initiatedById)
                .build();
        refundRepository.save(refund);

        long newTotalRefunded = alreadyRefunded + amount;
        session.setStatus(newTotalRefunded >= session.getAmountCents() ? "REFUNDED" : "PARTIALLY_REFUNDED");
        session.setUpdatedAt(LocalDateTime.now());
        checkoutSessionRepository.save(session);

        log.info("[REFUND] {} cents refunded on session {} by {} {} (reason: {})",
                amount, session.getId(), initiatedByType, initiatedById, req.reason());
        return refund;
    }

    public List<Refund> listForSession(Long checkoutSessionId) {
        return refundRepository.findByCheckoutSessionIdOrderByCreatedAtDesc(checkoutSessionId);
    }

    public long totalRefunded(Long checkoutSessionId) {
        return refundRepository.findByCheckoutSessionIdOrderByCreatedAtDesc(checkoutSessionId).stream()
                .filter(r -> "COMPLETED".equals(r.getStatus()))
                .mapToLong(Refund::getAmountCents)
                .sum();
    }
}
