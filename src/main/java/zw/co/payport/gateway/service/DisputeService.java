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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    public record OpenDisputeRequest(Long checkoutSessionId, Long amountCents, String reason, String type) {
        public OpenDisputeRequest(Long checkoutSessionId, Long amountCents, String reason) {
            this(checkoutSessionId, amountCents, reason, null);
        }
    }

    public Dispute open(OpenDisputeRequest req) {
        if (req.type() != null && !zw.co.payport.gateway.risk.DisputeSlaService.TYPES.contains(req.type().trim().toUpperCase())) {
            throw new RuntimeException("type must be COMPLAINT, ERROR or CHARGEBACK");
        }
        Dispute dispute = Dispute.builder()
                .checkoutSessionId(req.checkoutSessionId())
                .amountCents(req.amountCents())
                .reason(req.reason())
                .status("OPEN")
                .build();
        disputeRepository.save(dispute);
        if (req.type() != null) {
            String t = req.type().trim().toUpperCase();
            if (!zw.co.payport.gateway.risk.DisputeSlaService.TYPES.contains(t)) throw new RuntimeException("type must be COMPLAINT, ERROR or CHARGEBACK");
            if (jdbc != null) jdbc.update("UPDATE disputes SET dispute_type = ? WHERE id = ?", t, dispute.getId());
        }
        if (slaService != null) slaService.setDeadlines(dispute.getId());   // timelines from compliance parameters
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
