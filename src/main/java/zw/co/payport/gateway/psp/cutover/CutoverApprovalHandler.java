package zw.co.payport.gateway.psp.cutover;

import org.springframework.stereotype.Component;
import zw.co.payport.gateway.psp.ledger.LedgerService;
import zw.co.payport.gateway.psp.ledger.domain.PostingRules;
import zw.co.payport.gateway.psp.legacy.LegacyGateway;
import zw.co.payport.gateway.psp.platform.approval.ApprovalHandler;

import java.util.ArrayList;
import java.util.List;

/** Executes an approved opening-balance cutover; recomputed at approval time so it reflects the latest state. */
@Component
public class CutoverApprovalHandler implements ApprovalHandler {

    private final LegacyGateway legacy;
    private final LedgerService ledger;

    public CutoverApprovalHandler(LegacyGateway legacy, LedgerService ledger) {
        this.legacy = legacy;
        this.ledger = ledger;
    }

    @Override public String actionType() { return LedgerCutoverService.ACTION; }
    @Override public String requiredRole() { return "FINANCE"; }

    @Override
    public String execute(String target, String payload, String requestedBy, String approvedBy) {
        long merchantId = Long.parseLong(target);
        List<String> done = new ArrayList<>();
        for (LedgerCutoverService.CurrencyPreview p : CutoverMath.preview(legacy.unsettledSessions(merchantId),
                ref -> ref != null && ledger.entryByKey("COLLECT:" + ref).isPresent())) {
            if (p.openingBalanceMinor() > 0) {
                ledger.post(PostingRules.openingBalance(merchantId, p.currency(), p.openingBalanceMinor()), approvedBy);
            }
            legacy.markSessionsMigrated(merchantId, p.currency(), p.sessionIds(), p.openingBalanceMinor());
            done.add(p.currency() + " " + p.openingBalanceMinor() + " (" + p.sessions() + " sessions)");
        }
        return "Opening balances: " + String.join(", ", done);
    }
}
