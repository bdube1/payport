package zw.co.payport.gateway.psp.merchant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.config.MerchantAuthResolver;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.psp.channel.ChannelAdminService;
import zw.co.payport.gateway.psp.channel.EmvQr;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.Api;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.disputes.CustomerRefund;
import zw.co.payport.gateway.psp.disputes.DisputeCase;
import zw.co.payport.gateway.psp.disputes.DisputesAndRefundsService;
import zw.co.payport.gateway.psp.fees.FeeService;
import zw.co.payport.gateway.psp.ledger.FundHold;
import zw.co.payport.gateway.psp.ledger.LedgerService;
import zw.co.payport.gateway.psp.ledger.MerchantFundsService;
import zw.co.payport.gateway.psp.ledger.domain.AccountCategory;
import zw.co.payport.gateway.psp.ledger.domain.AccountKey;
import zw.co.payport.gateway.psp.ledger.domain.FeeSchedule;
import zw.co.payport.gateway.psp.settlement.SettlementInstruction;
import zw.co.payport.gateway.psp.settlement.SettlementEngine;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The merchant's view of their money, served from the ledger. Reached with the
 * merchant's API key or dashboard login (the gateway's existing filters on /api/v1/merchant/**).
 */
@RestController
@RequestMapping("/api/v1/merchant/psp")
public class MerchantPspController {

    public record QrBody(String currency, Long amountMinor, String reference) {}

    private final MerchantFundsService funds;
    private final LedgerService ledger;
    private final SettlementEngine settlements;
    private final MerchantControlService controls;
    private final FeeService fees;
    private final DisputesAndRefundsService disputes;
    private final ChannelAdminService channels;

    public MerchantPspController(MerchantFundsService funds, LedgerService ledger, SettlementEngine settlements,
                                 MerchantControlService controls, FeeService fees, DisputesAndRefundsService disputes,
                                 ChannelAdminService channels) {
        this.funds = funds;
        this.ledger = ledger;
        this.settlements = settlements;
        this.controls = controls;
        this.fees = fees;
        this.disputes = disputes;
        this.channels = channels;
    }

    /** Pending (T+N hold), available (next payout), reserve and in-settlement, per currency. */
    @GetMapping("/balances")
    public ResponseEntity<ApiResponse<List<MerchantFundsService.MerchantBalances>>> balances(HttpServletRequest req) {
        long id = me().getId();
        return Api.ok(List.of(funds.balances(id, "USD"), funds.balances(id, "ZWG")), req);
    }

    @GetMapping("/statement")
    public ResponseEntity<ApiResponse<List<LedgerService.StatementLine>>> statement(@RequestParam(defaultValue = "USD") String currency,
                                                                                   @RequestParam(defaultValue = "MERCHANT_AVAILABLE") String bucket,
                                                                                   @RequestParam(required = false) String from,
                                                                                   @RequestParam(required = false) String to,
                                                                                   HttpServletRequest req) {
        AccountCategory cat = AccountCategory.valueOf(bucket.toUpperCase());
        if (cat.ownerKind() != AccountCategory.OwnerKind.MERCHANT) throw PspException.badRequest("INVALID_BUCKET", "Use a MERCHANT_* or SETTLEMENT_PAYABLE bucket");
        LocalDateTime f = from == null ? LocalDateTime.now().minusDays(30) : LocalDateTime.parse(from);
        LocalDateTime t = to == null ? LocalDateTime.now().plusMinutes(1) : LocalDateTime.parse(to);
        return Api.ok(ledger.statement(AccountKey.merchant(cat, me().getId(), currency), f, t, 500), req);
    }

    @GetMapping("/holds")
    public ResponseEntity<ApiResponse<List<FundHold>>> holds(HttpServletRequest req) {
        return Api.ok(funds.openHolds(me().getId()), req);
    }

    @GetMapping("/settlements")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> settlementHistory(HttpServletRequest req) {
        return Api.ok(settlements.forMerchant(me().getId()).stream().map(MerchantPspController::view).collect(Collectors.toList()), req);
    }

    @GetMapping("/payout-accounts")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> payoutAccounts(HttpServletRequest req) {
        return Api.ok(controls.payoutAccounts(me().getId()).stream().map(MerchantControlService::view).collect(Collectors.toList()), req);
    }

    /** New payout destination: verified by PayPort finance (four-eyes) and subject to a cooling-off period. */
    @PostMapping("/payout-accounts")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addPayoutAccount(@RequestBody MerchantControlService.PayoutAccountRequest b,
                                                                            HttpServletRequest req) {
        Merchant m = me();
        if (MerchantAuthResolver.currentMerchantUserOrNull() == null) {
            throw PspException.forbidden("DASHBOARD_ONLY", "Payout accounts can only be changed from the merchant dashboard, not with an API key");
        }
        return Api.created(MerchantControlService.view(controls.addPayoutAccount(m.getId(), b, actor(m))), req);
    }

    @GetMapping("/fees")
    public ResponseEntity<ApiResponse<Map<String, FeeSchedule>>> feeSchedule(@RequestParam(defaultValue = "ECOCASH") String method, HttpServletRequest req) {
        long id = me().getId();
        return Api.ok(Map.of("USD", fees.scheduleFor(id, method, "USD"), "ZWG", fees.scheduleFor(id, method, "ZWG")), req);
    }

    @GetMapping("/disputes")
    public ResponseEntity<ApiResponse<List<DisputeCase>>> disputes(HttpServletRequest req) {
        return Api.ok(disputes.merchantDisputes(me().getId()), req);
    }

    @GetMapping("/refunds")
    public ResponseEntity<ApiResponse<List<CustomerRefund>>> refunds(HttpServletRequest req) {
        return Api.ok(disputes.merchantRefunds(me().getId()), req);
    }

    /** EMVCo QR payload for a sticker (no amount) or till/invoice (with amount). Render it as a QR image client-side. */
    @PostMapping("/qr")
    public ResponseEntity<ApiResponse<Map<String, String>>> qr(@RequestBody QrBody b, HttpServletRequest req) {
        Merchant m = me();
        String mcc = controls.profile(m.getId()).getMcc();
        String payload = EmvQr.build(new EmvQr.Payload(b.amountMinor() != null, String.valueOf(m.getId()), mcc,
                b.currency() == null ? m.getDefaultCurrency() : b.currency(), b.amountMinor(), m.getBusinessName(), "HARARE", b.reference()));
        return Api.ok(Map.of("payload", payload), req);
    }

    @GetMapping("/terminals")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> terminals(HttpServletRequest req) {
        return Api.ok(channels.terminals(me().getId()).stream().map(t -> Map.<String, Object>of("terminalId", t.getTerminalId(),
                "type", t.getTerminalType(), "status", t.getStatus(), "location", String.valueOf(t.getLocationLabel()),
                "lastSeenAt", String.valueOf(t.getLastSeenAt()))).collect(Collectors.toList()), req);
    }

    private static Merchant me() {
        try {
            return MerchantAuthResolver.currentMerchant();
        } catch (RuntimeException e) {
            throw PspException.forbidden("MERCHANT_AUTH_REQUIRED", "Authenticate as a merchant");
        }
    }

    private static Actor actor(Merchant m) {
        var user = MerchantAuthResolver.currentMerchantUserOrNull();
        return new Actor("merchant:" + m.getId() + (user != null ? ":user:" + user.getId() : ":api"), Set.of("MERCHANT"));
    }

    static Map<String, Object> view(SettlementInstruction i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instructionRef", i.getInstructionRef());
        m.put("currency", i.getCurrency());
        m.put("amountMinor", i.getAmountMinor());
        m.put("status", i.getStatus());
        m.put("destination", i.getDestinationMasked());
        m.put("bank", i.getBankName());
        m.put("payoutReference", i.getPayoutReference());
        m.put("paidAt", i.getPaidAt());
        m.put("createdAt", i.getCreatedAt());
        m.put("failureReason", i.getFailureReason());
        return m;
    }
}
