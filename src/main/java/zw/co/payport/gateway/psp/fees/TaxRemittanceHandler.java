package zw.co.payport.gateway.psp.fees;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.psp.ledger.LedgerService;
import zw.co.payport.gateway.psp.ledger.domain.PostingRules;
import zw.co.payport.gateway.psp.platform.approval.ApprovalHandler;

/** Records a ZIMRA payment (IMTT or VAT) against the tax-payable account once a second person approves it. */
@Component
public class TaxRemittanceHandler implements ApprovalHandler {

    public static final String ACTION = "TAX_REMITTANCE";

    private final LedgerService ledger;
    private final ObjectMapper om;

    public TaxRemittanceHandler(LedgerService ledger, ObjectMapper om) {
        this.ledger = ledger;
        this.om = om;
    }

    @Override public String actionType() { return ACTION; }
    @Override public String requiredRole() { return "FINANCE"; }

    @Override
    public String execute(String target, String payload, String requestedBy, String approvedBy) {
        JsonNode n;
        try { n = om.readTree(payload); } catch (Exception e) { throw new IllegalStateException(e); }
        LedgerService.PostedEntry e = ledger.post(PostingRules.taxRemittance(n.path("zimraRef").asText(), n.path("taxCode").asText(),
                n.path("currency").asText(), n.path("amountMinor").asLong(), n.path("cashAccount").asText()), approvedBy);
        return "Remittance posted " + e.entryRef();
    }
}
