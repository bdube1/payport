package zw.co.payport.gateway.service;

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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private zw.co.payport.gateway.risk.DisputeSlaService slaService;

    public record OpenDisputeRequest(Long checkoutSessionId, Long amountCents, String reason) {}

    public Dispute open(OpenDisputeRequest req) {
        Dispute dispute = Dispute.builder()
                .checkoutSessionId(req.checkoutSessionId())
                .amountCents(req.amountCents())
                .reason(req.reason())
                .status("OPEN")
                .build();
        disputeRepository.save(dispute);
        if (slaService != null) slaService.setDeadlines(dispute.getId());   // Phase 6 timelines
        log.info("[DISPUTE] Opened against session {}: {} cents, reason: {}", req.checkoutSessionId(), req.amountCents(), req.reason());
        return dispute;
    }

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
