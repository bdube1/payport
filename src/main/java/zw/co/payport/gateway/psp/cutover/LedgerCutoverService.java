package zw.co.payport.gateway.psp.cutover;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.ledger.LedgerService;
import zw.co.payport.gateway.psp.legacy.LegacyGateway;
import zw.co.payport.gateway.psp.platform.approval.ApprovalRequest;
import zw.co.payport.gateway.psp.platform.approval.ApprovalService;

import java.util.*;

/**
 * Moves each merchant's unsettled balance from the gateway's checkout-session
 * arithmetic into the ledger, exactly once.
 *
 * Only sessions whose payment has NO ledger collection entry are included
 * (payments confirmed after the PSP release are already in the ledger), so
 * running it after go-live can never double count. The balance is posted
 * as an OPENING_BALANCE to MERCHANT_AVAILABLE (it was already settleable
 * under the old rules) and the sessions are marked so the old settlement
 * code can never pay them again. Needs four-eyes approval per merchant.
 */
@Service
public class LedgerCutoverService {

    public static final String ACTION = "CUTOVER_OPENING_BALANCE";

    public record CurrencyPreview(String currency, int sessions, long openingBalanceMinor, List<Long> sessionIds) {}

    private final LegacyGateway legacy;
    private final LedgerService ledger;
    private final ApprovalService approvals;
    private final ObjectMapper om;

    public LedgerCutoverService(LegacyGateway legacy, LedgerService ledger, ApprovalService approvals, ObjectMapper om) {
        this.legacy = legacy;
        this.ledger = ledger;
        this.approvals = approvals;
        this.om = om;
    }

    @Transactional(readOnly = true)
    public List<CurrencyPreview> preview(long merchantId) {
        return CutoverMath.preview(legacy.unsettledSessions(merchantId),
                ref -> ref != null && ledger.entryByKey("COLLECT:" + ref).isPresent());
    }

    @Transactional
    public ApprovalRequest request(long merchantId, Actor actor) {
        List<CurrencyPreview> p = preview(merchantId);
        if (p.stream().allMatch(c -> c.openingBalanceMinor() == 0 && c.sessions() == 0)) {
            throw PspException.unprocessable("NOTHING_TO_MIGRATE", "Merchant " + merchantId + " has no unsettled pre-ledger balance");
        }
        String summary = "Opening balance for merchant " + merchantId + ": " + String.join(", ",
                p.stream().map(c -> c.currency() + " " + c.openingBalanceMinor() + " from " + c.sessions() + " sessions").toList());
        try {
            return approvals.request(ACTION, String.valueOf(merchantId), om.writeValueAsString(Map.of("merchantId", merchantId)),
                    summary, "Ledger cutover", actor);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
