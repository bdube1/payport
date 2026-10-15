package zw.co.payport.gateway.psp.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.psp.platform.approval.ApprovalHandler;

/** Reversing a journal entry moves money, so it is a four-eyes action like any adjustment. */
@Component
public class LedgerReversalHandler implements ApprovalHandler {

    public static final String ACTION = "LEDGER_REVERSAL";

    private final LedgerService ledger;
    private final ObjectMapper om;

    public LedgerReversalHandler(LedgerService ledger, ObjectMapper om) {
        this.ledger = ledger;
        this.om = om;
    }

    @Override public String actionType() { return ACTION; }
    @Override public String requiredRole() { return "FINANCE"; }

    @Override
    public String execute(String entryRef, String payloadJson, String requestedBy, String approvedBy) {
        String reason;
        try { reason = om.readTree(payloadJson).path("reason").asText(); } catch (Exception e) { throw new IllegalStateException(e); }
        LedgerService.PostedEntry r = ledger.reverse(entryRef, reason + " (requested by " + requestedBy + ")", approvedBy);
        return "Reversed by " + r.entryRef();
    }
}
