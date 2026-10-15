package zw.co.payport.gateway.psp.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.Api;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.ledger.domain.AccountCategory;
import zw.co.payport.gateway.psp.platform.approval.ApprovalRequest;
import zw.co.payport.gateway.psp.platform.approval.ApprovalService;
import zw.co.payport.gateway.psp.recon.ReconBreakService;

import java.time.LocalDateTime;
import java.util.*;

/** Read access to the ledger for finance and ops, plus four-eyes reversal requests. */
@RestController
@RequestMapping("/api/v1/psp/admin/ledger")
@PreAuthorize("hasAnyRole('FINANCE','ADMIN','OPS','COMPLIANCE')")
public class LedgerAdminController {

    public record ReversalRequest(String reason) {}

    private final LedgerService ledger;
    private final MerchantFundsService funds;
    private final ApprovalService approvals;
    private final ObjectMapper om;

    public LedgerAdminController(LedgerService ledger, MerchantFundsService funds, ApprovalService approvals, ObjectMapper om) {
        this.ledger = ledger;
        this.funds = funds;
        this.approvals = approvals;
        this.om = om;
    }

    /** All balances of one owner (merchant id, customer ref, agent id, provider code...). */
    @GetMapping("/owners/{ownerRef}/balances")
    public ResponseEntity<ApiResponse<List<LedgerService.BalanceView>>> ownerBalances(@PathVariable String ownerRef, HttpServletRequest req) {
        return Api.ok(ledger.ownerBalances(ownerRef.toUpperCase(), EnumSet.allOf(AccountCategory.class)), req);
    }

    @GetMapping("/categories/{category}/balances")
    public ResponseEntity<ApiResponse<List<LedgerService.BalanceView>>> categoryBalances(@PathVariable String category, HttpServletRequest req) {
        return Api.ok(ledger.categoryBalances(AccountCategory.valueOf(category.toUpperCase())), req);
    }

    /** Statement for an account code such as MERCHANT_AVAILABLE:42:USD. */
    @GetMapping("/accounts/{code}/statement")
    public ResponseEntity<ApiResponse<Map<String, Object>>> statement(@PathVariable String code,
                                                                      @RequestParam(required = false) String from,
                                                                      @RequestParam(required = false) String to,
                                                                      @RequestParam(defaultValue = "200") int limit,
                                                                      HttpServletRequest req) {
        var key = ReconBreakService.parseAccount(code);
        LocalDateTime f = from == null ? LocalDateTime.now().minusDays(30) : LocalDateTime.parse(from);
        LocalDateTime t = to == null ? LocalDateTime.now().plusMinutes(1) : LocalDateTime.parse(to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("account", ledger.view(key));
        out.put("lines", ledger.statement(key, f, t, limit));
        return Api.ok(out, req);
    }

    @GetMapping("/entries/by-source/{sourceType}/{sourceRef}")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> bySource(@PathVariable String sourceType, @PathVariable String sourceRef, HttpServletRequest req) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JournalEntry e : ledger.entriesForSource(sourceType, sourceRef)) out.add(entryView(e));
        return Api.ok(out, req);
    }

    @GetMapping("/entries/by-key")
    public ResponseEntity<ApiResponse<Map<String, Object>>> byKey(@RequestParam String key, HttpServletRequest req) {
        JournalEntry e = ledger.entryByKey(key).orElseThrow(() -> PspException.notFound("Journal entry", key));
        return Api.ok(entryView(e), req);
    }

    @PostMapping("/entries/{entryRef}/reversal-requests")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN')")
    public ResponseEntity<ApiResponse<ApprovalRequest>> requestReversal(@PathVariable String entryRef, @RequestBody ReversalRequest r,
                                                                        HttpServletRequest req) throws Exception {
        if (r.reason() == null || r.reason().length() < 10) throw PspException.badRequest("REASON_REQUIRED", "Explain the reversal");
        return Api.created(approvals.request(LedgerReversalHandler.ACTION, entryRef, om.writeValueAsString(Map.of("reason", r.reason())),
                "Reverse journal entry " + entryRef, r.reason(), Actor.current()), req);
    }

    @GetMapping("/merchants/{merchantId}/funds")
    public ResponseEntity<ApiResponse<Map<String, Object>>> merchantFunds(@PathVariable long merchantId, HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("USD", funds.balances(merchantId, "USD"));
        out.put("ZWG", funds.balances(merchantId, "ZWG"));
        out.put("openHolds", funds.openHolds(merchantId));
        return Api.ok(out, req);
    }

    @PostMapping("/holds/{holdRef}/freeze")
    @PreAuthorize("hasAnyRole('COMPLIANCE','ADMIN','OPS')")
    public ResponseEntity<ApiResponse<FundHold>> freeze(@PathVariable String holdRef, @RequestParam boolean frozen, HttpServletRequest req) {
        return Api.ok(funds.freeze(holdRef, frozen), req);
    }

    private Map<String, Object> entryView(JournalEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entryRef", e.getEntryRef());
        m.put("idempotencyKey", e.getIdempotencyKey());
        m.put("type", e.getEntryType());
        m.put("currency", e.getCurrency());
        m.put("description", e.getDescription());
        m.put("source", e.getSourceType() + ":" + e.getSourceRef());
        m.put("reverses", e.getReversesEntryRef());
        m.put("effectiveAt", e.getEffectiveAt());
        m.put("postedBy", e.getPostedBy());
        m.put("hash", e.getEntryHash());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (JournalLine l : ledger.linesOf(e.getId())) {
            lines.add(Map.of("account", l.getAccountCode(), "direction", l.getDirection(), "amountMinor", l.getAmountMinor(),
                    "memo", l.getMemo() == null ? "" : l.getMemo()));
        }
        m.put("lines", lines);
        return m;
    }
}
