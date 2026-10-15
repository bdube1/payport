package zw.co.payport.gateway.psp.recon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.ledger.LedgerService;
import zw.co.payport.gateway.psp.ledger.domain.PostingRules;
import zw.co.payport.gateway.psp.platform.approval.ApprovalHandler;

import java.time.LocalDateTime;

/** Posts an approved ledger adjustment and closes the break it clears. */
@Component
public class LedgerAdjustmentHandler implements ApprovalHandler {

    private final LedgerService ledger;
    private final ReconBreakRepository breaks;
    private final ObjectMapper om;

    public LedgerAdjustmentHandler(LedgerService ledger, ReconBreakRepository breaks, ObjectMapper om) {
        this.ledger = ledger;
        this.breaks = breaks;
        this.om = om;
    }

    @Override public String actionType() { return ReconBreakService.APPROVE_ADJUSTMENT; }
    @Override public String requiredRole() { return "FINANCE"; }

    @Override
    public String execute(String breakRef, String payloadJson, String requestedBy, String approvedBy) {
        JsonNode n;
        try { n = om.readTree(payloadJson); } catch (Exception e) { throw new IllegalStateException(e); }
        ReconBreak b = breaks.findByBreakRef(breakRef).orElseThrow(() -> PspException.notFound("Break", breakRef));
        LedgerService.PostedEntry posted = ledger.post(PostingRules.adjustment(breakRef, n.path("currency").asText(),
                ReconBreakService.parseAccount(n.path("debit").asText()), ReconBreakService.parseAccount(n.path("credit").asText()),
                n.path("amountMinor").asLong(), n.path("reason").asText()), approvedBy);
        b.setStatus("RESOLVED");
        b.setResolution("ADJUSTED");
        b.setResolutionEntryRef(posted.entryRef());
        b.setResolutionNote("Adjustment " + posted.entryRef() + " requested by " + requestedBy + ": " + n.path("reason").asText());
        b.setResolvedBy(approvedBy);
        b.setResolvedAt(LocalDateTime.now());
        breaks.save(b);
        return "Posted " + posted.entryRef();
    }
}
