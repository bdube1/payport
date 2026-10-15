package zw.co.payport.gateway.psp.wallet;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.psp.channel.EmvQr;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.Api;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.fx.FxConversion;
import zw.co.payport.gateway.psp.fx.FxConversionService;
import zw.co.payport.gateway.psp.fx.FxRateService;
import zw.co.payport.gateway.psp.kyc.CustomerProfile;
import zw.co.payport.gateway.psp.kyc.KycService;
import zw.co.payport.gateway.psp.platform.idempotency.IdempotentHttp;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Wallet API for the PayPort mobile app. The caller is the logged-in app user;
 * their phone number (the JWT subject) is the wallet. Money-moving calls take
 * an Idempotency-Key so a retried request never pays twice.
 */
@RestController
@RequestMapping("/api/v1/psp/wallet")
@PreAuthorize("hasRole('CUSTOMER')")
public class WalletController {

    public record RegisterBody(String firstName, String lastName, LocalDate dateOfBirth, String pin) {}
    public record SendBody(String toMsisdn, String currency, long amountMinor, String pin) {}
    public record MerchantBody(long merchantId, String currency, long amountMinor, String pin) {}
    public record QrBody(String qr, Long amountMinor, String pin) {}
    public record ConvertBody(String from, String to, long amountMinor, String pin) {}
    public record PinChangeBody(String oldPin, String newPin) {}
    public record IdBody(String nationalId) {}

    private final WalletService wallets;
    private final KycService kyc;
    private final FxRateService fx;
    private final FxConversionService fxConvert;
    private final IdempotentHttp idem;
    private final com.fasterxml.jackson.databind.ObjectMapper om;

    public WalletController(WalletService wallets, KycService kyc, FxRateService fx, FxConversionService fxConvert,
                            IdempotentHttp idem, com.fasterxml.jackson.databind.ObjectMapper om) {
        this.wallets = wallets;
        this.kyc = kyc;
        this.fx = fx;
        this.fxConvert = fxConvert;
        this.idem = idem;
        this.om = om;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Map<String, Object>>> register(@RequestBody RegisterBody b, HttpServletRequest req) {
        CustomerProfile c = wallets.registerCustomer(phone(), b.firstName(), b.lastName(), b.dateOfBirth(), b.pin(), Actor.current());
        return Api.created(Map.of("customerRef", c.getCustomerRef(), "tier", c.getKycTier()), req);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(HttpServletRequest req) {
        CustomerProfile c = kyc.requireActive(phone());
        KycTierLimitView usd = limits(c, "USD"), zwg = limits(c, "ZWG");
        return Api.ok(Map.of("customerRef", c.getCustomerRef(), "name", c.getFirstName() + " " + c.getLastName(), "tier", c.getKycTier(),
                "balances", Map.of("USD", wallets.balance(c.getCustomerRef(), "USD"), "ZWG", wallets.balance(c.getCustomerRef(), "ZWG")),
                "limits", Map.of("USD", usd, "ZWG", zwg)), req);
    }

    @GetMapping("/transactions")
    public ResponseEntity<ApiResponse<List<WalletTransfer>>> history(@RequestParam(defaultValue = "50") int limit, HttpServletRequest req) {
        return Api.ok(wallets.history(kyc.requireActive(phone()).getCustomerRef(), limit), req);
    }

    @PostMapping("/send")
    public ResponseEntity<String> send(@RequestBody SendBody b, @RequestHeader(value = IdempotentHttp.HEADER, required = false) String key,
                                       HttpServletRequest req) throws Exception {
        String p = phone();
        return idem.run("wallet:" + p, key, req, om.writeValueAsString(b), 201,
                () -> wallets.sendMoney(p, b.toMsisdn(), b.currency(), b.amountMinor(), b.pin(), "APP", key),
                WalletService.TransferResult::transferRef);
    }

    @PostMapping("/pay-merchant")
    public ResponseEntity<String> payMerchant(@RequestBody MerchantBody b, @RequestHeader(value = IdempotentHttp.HEADER, required = false) String key,
                                              HttpServletRequest req) throws Exception {
        String p = phone();
        return idem.run("wallet:" + p, key, req, om.writeValueAsString(b), 201,
                () -> wallets.payMerchant(p, b.merchantId(), b.currency(), b.amountMinor(), b.pin(), "APP", key),
                WalletService.TransferResult::transferRef);
    }

    /** Scan-to-pay: parses a PayPort EMVCo QR. Dynamic QRs carry the amount; static ones need it in the body. */
    @PostMapping("/pay-qr")
    public ResponseEntity<String> payQr(@RequestBody QrBody b, @RequestHeader(value = IdempotentHttp.HEADER, required = false) String key,
                                        HttpServletRequest req) throws Exception {
        String p = phone();
        EmvQr.Payload q;
        try {
            q = EmvQr.parse(b.qr());
        } catch (IllegalArgumentException e) {
            throw PspException.badRequest("INVALID_QR", e.getMessage());
        }
        long amount = q.amountMinor() != null ? q.amountMinor() : (b.amountMinor() == null ? 0 : b.amountMinor());
        long merchantId = Long.parseLong(q.merchantRef().replaceAll("\\D", ""));
        return idem.run("wallet:" + p, key, req, om.writeValueAsString(b), 201,
                () -> wallets.payMerchant(p, merchantId, q.currency(), amount, b.pin(), "QR", key),
                WalletService.TransferResult::transferRef);
    }

    @GetMapping("/fx/quote")
    public ResponseEntity<ApiResponse<FxRateService.Quote>> quote(@RequestParam String from, @RequestParam String to,
                                                                 @RequestParam long amountMinor, HttpServletRequest req) {
        return Api.ok(fx.quote(from, to, amountMinor), req);
    }

    @PostMapping("/fx/convert")
    public ResponseEntity<String> convert(@RequestBody ConvertBody b, @RequestHeader(value = IdempotentHttp.HEADER, required = false) String key,
                                          HttpServletRequest req) throws Exception {
        String p = phone();
        return idem.run("wallet:" + p, key, req, om.writeValueAsString(b), 201,
                () -> fxConvert.convert(p, b.from(), b.to(), b.amountMinor(), b.pin(), "APP"), FxConversion::getConversionRef);
    }

    @PostMapping("/pin")
    public ResponseEntity<ApiResponse<String>> changePin(@RequestBody PinChangeBody b, HttpServletRequest req) {
        wallets.changePin(kyc.requireActive(phone()).getCustomerRef(), b.oldPin(), b.newPin());
        return Api.ok("PIN changed", req);
    }

    /** Self-service KYC upgrade: electronic national ID check (TIER1 -> TIER2 on a match). */
    @PostMapping("/kyc/national-id")
    public ResponseEntity<ApiResponse<Map<String, Object>>> nationalId(@RequestBody IdBody b, HttpServletRequest req) {
        CustomerProfile c = kyc.verifyNationalId(kyc.requireActive(phone()).getCustomerRef(), b.nationalId(), Actor.current());
        return Api.ok(Map.of("tier", c.getKycTier(), "result", String.valueOf(c.getIdVerificationResult())), req);
    }

    public record KycTierLimitView(long maxBalanceMinor, long maxTxnMinor, long dailyDebitMinor, long monthlyDebitMinor) {}

    private KycTierLimitView limits(CustomerProfile c, String ccy) {
        try {
            var l = kyc.tierLimit(c.getKycTier(), ccy);
            return new KycTierLimitView(l.getMaxBalanceMinor(), l.getMaxTxnMinor(), l.getDailyDebitMinor(), l.getMonthlyDebitMinor());
        } catch (PspException e) {
            return null;
        }
    }

    private static String phone() {
        return KycService.normaliseMsisdn(Actor.current().id());
    }
}
