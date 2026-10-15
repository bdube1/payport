package zw.co.payport.gateway.service;

import zw.co.payport.gateway.entity.CheckoutSession;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.entity.Dispute;
import zw.co.payport.gateway.repository.DisputeRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class DisputeService {

    private static final Set<String> RESOLVED_STATUSES = Set.of("WON", "LOST");

    private final DisputeRepository disputeRepository;
    private final org.springframework.beans.factory.ObjectProvider<zw.co.payport.gateway.psp.disputes.DisputesAndRefundsService> pspDisputes;
    private final zw.co.payport.gateway.repository.CheckoutSessionRepository checkoutSessions;

    public record OpenDisputeRequest(Long checkoutSessionId, Long amountCents, String reason) {}

    /**
     * PSP: opening a dispute holds the disputed amount in the merchant's ledger
     * RESERVE (it can no longer be settled); WON releases it, LOST charges it back
     * to the customer. WON = the merchant won, LOST = the customer won.
     */
    @org.springframework.transaction.annotation.Transactional
    public Dispute open(OpenDisputeRequest req) {
        Dispute dispute = Dispute.builder()
                .checkoutSessionId(req.checkoutSessionId())
                .amountCents(req.amountCents())
                .reason(req.reason())
                .status("OPEN")
                .build();
        disputeRepository.save(dispute);
        pspDisputes.getObject().onDisputeOpened(dispute.getId(), req.checkoutSessionId(), req.amountCents(), "SYSTEM:disputes");
        log.info("[DISPUTE] Opened against session {}: {} cents, reason: {}", req.checkoutSessionId(), req.amountCents(), req.reason());
        return dispute;
    }

    @org.springframework.transaction.annotation.Transactional
    public Dispute resolve(Long disputeId, String status, String evidenceNotes) {
        if (!RESOLVED_STATUSES.contains(status)) {
            throw new RuntimeException("Resolution status must be WON or LOST");
        }
        Dispute dispute = disputeRepository.findById(disputeId)
                .orElseThrow(() -> new RuntimeException("Dispute not found: " + disputeId));
        dispute.setStatus(status);
        if (evidenceNotes != null) dispute.setEvidenceNotes(evidenceNotes);
        dispute.setResolvedAt(LocalDateTime.now());
        disputeRepository.save(dispute);
        String customerPhone = checkoutSessions.findById(dispute.getCheckoutSessionId()).map(CheckoutSession::getCustomerPhone).orElse(null);
        pspDisputes.getObject().onDisputeResolved(dispute.getId(), status, "SYSTEM:disputes", customerPhone);
        log.info("[DISPUTE] {} resolved as {}", disputeId, status);
        return dispute;
    }

    public List<Dispute> listForSession(Long checkoutSessionId) {
        return disputeRepository.findByCheckoutSessionId(checkoutSessionId);
    }

    public List<Dispute> listAll() {
        return disputeRepository.findAll();
    }
}
