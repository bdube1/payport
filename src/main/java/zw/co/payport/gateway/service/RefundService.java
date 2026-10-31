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
 * A merchant refunds its own customer from its own balance (owner, 2026-10-31).
 *
 * The refund comes out of what PayPort still holds for the merchant: it is allowed only while the payment has not been
 * paid out to the merchant, and the merchant's balance drops by its share at once (the refund row is COMPLETED, which
 * the balance and settlement already subtract). The money back to the customer is listed in the central reversal
 * register as MERCHANT_REFUND, waiting for the payout (no provider in this codebase can reverse a payment
 * automatically); Finance records the payout reference there and the refund.completed event follows. Every refund is
 * therefore in one place, and refund.requested / refund.completed are on the platform event stream.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final RefundRepository refundRepository;
    private final CheckoutSessionRepository checkoutSessionRepository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final zw.co.payport.gateway.events.PlatformEvents events;
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

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
        // From the merchant's own balance: only while PayPort still holds this payment for the merchant.
        Integer paidOut = jdbc.queryForObject("SELECT COUNT(*) FROM checkout_sessions cs WHERE cs.id = ? AND (cs.settlement_id IS NOT NULL OR EXISTS "
                + "(SELECT 1 FROM stl_session_claims c JOIN stl_items i ON i.id = c.item_id JOIN stl_batches b ON b.id = i.batch_id "
                + "WHERE c.session_id = cs.id AND b.mode = 'LIVE'))", Integer.class, session.getId());
        if (paidOut != null && paidOut > 0) {
            throw new RuntimeException("This payment has already been paid out to you, so it cannot be refunded from your balance. Contact PayPort support.");
        }

        Refund refund = Refund.builder()
                .checkoutSessionId(session.getId())
                .amountCents(amount)
                .reason(req.reason())
                .status("COMPLETED")
                .initiatedByType(initiatedByType)
                .initiatedById(initiatedById)
                .build();
        refundRepository.save(refund);

        long newTotalRefunded = alreadyRefunded + amount;
        session.setStatus(newTotalRefunded >= session.getAmountCents() ? "REFUNDED" : "PARTIALLY_REFUNDED");
        session.setUpdatedAt(LocalDateTime.now());
        checkoutSessionRepository.save(session);

        // A test-mode (sandbox) payment moved no money, so its refund has nothing to pay out: it is not put in the register.
        String register = Boolean.TRUE.equals(session.getLivemode()) ? registerPayout(session, refund, initiatedByType, initiatedById) : null;
        java.util.Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("refundId", refund.getId());
        p.put("checkoutSessionId", session.getId());
        p.put("reference", session.getReference());
        p.put("amountMinor", amount);
        p.put("currency", session.getCurrency());
        p.put("sessionStatus", session.getStatus());
        p.put("reversalRef", register);
        events.record("refund.requested", session.getMerchantId(), "refund_" + refund.getId(), "refund:" + refund.getId() + ":requested",
                Boolean.TRUE.equals(session.getLivemode()), p);

        log.info("[REFUND] {} cents refunded on session {} by {} {} (reason: {}), payout {}",
                amount, session.getId(), initiatedByType, initiatedById, req.reason(), register);
        return refund;
    }

    /** The payout back to the customer, in the central reversal register (Customer care > Reversals, awaiting payout). */
    private String registerPayout(CheckoutSession session, Refund refund, String byType, Long byId) {
        String merchant = jdbc.queryForList("SELECT business_name FROM merchants WHERE id = ?", String.class, session.getMerchantId())
                .stream().findFirst().orElse("Merchant " + session.getMerchantId());
        String summary = "Merchant refund: " + merchant + " refunds " + session.getCurrency() + " "
                + java.math.BigDecimal.valueOf(refund.getAmountCents(), 2).toPlainString() + " on " + session.getReference()
                + (session.getTransactionReference() == null ? "" : " (" + session.getTransactionReference() + ")");
        String reason = refund.getReason() == null || refund.getReason().isBlank() ? "Refund by the merchant" : refund.getReason().trim();
        String by = "merchant:" + session.getMerchantId() + ":" + (byType == null ? "USER" : byType) + ":" + (byId == null ? 0 : byId);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        for (int i = 0; i < 10; i++) {
            String ref = "RVS-" + now.format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd")) + "-" + String.format("%05d", RANDOM.nextInt(100_000));
            try {
                jdbc.update("INSERT INTO reversal_requests (reversal_ref, kind, target_ref, amount_minor, currency, summary, reason, status, requested_by, requested_at) "
                                + "VALUES (?, 'MERCHANT_REFUND', ?, ?, ?, ?, ?, 'AWAITING_PAYOUT', ?, ?)",
                        ref, "refund:" + refund.getId(), refund.getAmountCents(), session.getCurrency(), clip(summary, 300), clip(reason, 1000), clip(by, 160),
                        java.sql.Timestamp.valueOf(now));
                jdbc.update("UPDATE refunds SET reversal_ref = ? WHERE id = ?", ref, refund.getId());
                return ref;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // reference taken: try another
            }
        }
        throw new IllegalStateException("Please try again");
    }

    private static String clip(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
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
