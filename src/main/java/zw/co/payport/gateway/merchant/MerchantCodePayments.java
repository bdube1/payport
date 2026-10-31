package zw.co.payport.gateway.merchant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.entity.CheckoutSession;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.PartyCodes;
import zw.co.payport.gateway.platform.Web;
import zw.co.payport.gateway.repository.CheckoutSessionRepository;
import zw.co.payport.gateway.repository.MerchantRepository;
import zw.co.payport.gateway.rules.PartyRules;
import zw.co.payport.gateway.service.CheckoutSessionService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Paying a merchant by its six-digit merchant code (app, web, USSD). The customer types the code, sees the
 * merchant's name and enters the amount; Payport opens a live checkout session for that merchant, which is then
 * paid like any other: with the wallet (registered customers) or EcoCash, OneMoney, O'mari or InnBucks (anyone).
 */
@RestController
public class MerchantCodePayments {

    private final PartyCodes codes;
    private final MerchantRepository merchants;
    private final CheckoutSessionService checkout;
    private final CheckoutSessionRepository sessions;
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private zw.co.payport.gateway.agents.AgentService agents;

    public MerchantCodePayments(PartyCodes codes, MerchantRepository merchants, CheckoutSessionService checkout, CheckoutSessionRepository sessions) {
        this.codes = codes;
        this.merchants = merchants;
        this.checkout = checkout;
        this.sessions = sessions;
    }

    public Merchant merchant(String code) {
        long id = codes.resolve(PartyCodes.MERCHANT, code).orElseThrow(() -> new IllegalArgumentException("No merchant with code " + code));
        Merchant m = merchants.findById(id).orElseThrow(() -> new IllegalArgumentException("No merchant with code " + code));
        if (!"ACTIVE".equals(m.getStatus())) throw new IllegalStateException("This merchant cannot take payments at the moment");
        return m;
    }

    public Map<String, Object> lookup(String code) {
        Merchant m = merchant(code);
        String digits = code.replaceAll("[^0-9]", "");
        Map<String, Object> c = codes.row(digits).orElse(Map.of());
        boolean branch = c.get("label") != null && !Boolean.TRUE.equals(c.get("is_primary"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", digits);
        out.put("name", branch ? m.getBusinessName() + " – " + c.get("label") : m.getBusinessName());
        out.put("address", c.get("address") != null ? c.get("address") : m.getAddress());
        out.put("currencies", checkoutCurrencies(m));
        return out;
    }

    private java.util.List<String> checkoutCurrencies(Merchant m) {
        return checkout.liveCurrenciesFor(m.getId());
    }

    /** A live checkout session for the merchant behind the code, for this amount. */
    public CheckoutSession start(String code, long amountMinor, String currency, String channel, String customerPhone, String description) {
        Merchant m = merchant(code);
        if (amountMinor <= 0) throw new IllegalArgumentException("Enter an amount above zero");
        String ccy = currency == null || currency.isBlank() ? m.getDefaultCurrency() : currency.trim().toUpperCase();
        CheckoutSession s;
        try {
            s = checkout.createSession(m, null, new CheckoutSessionService.CreateSessionRequest(null, amountMinor, ccy,
                    description == null || description.isBlank() ? "Payment to " + m.getBusinessName() : description.trim(),
                    customerPhone, null, null, null, null));
        } catch (CheckoutSessionService.CurrencyNotEnabledException e) {
            throw new IllegalArgumentException(m.getBusinessName() + " does not take payments in " + ccy + " yet");
        }
        s.setChannel(PartyRules.channel(channel));
        s.setPartyCode(code.replaceAll("[^0-9]", ""));
        return sessions.save(s);
    }

    /** What a scanned Payport QR (or a typed code) points to: merchant or agent, its code and name. */
    public Map<String, Object> scanned(String payload) {
        Map<String, Object> c = codes.fromQr(payload).orElseThrow(() -> new IllegalArgumentException("This QR code is not valid. Ask for the current Payport QR or enter the code."));
        String code = (String) c.get("code");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", c.get("party_type"));
        out.put("code", code);
        if (PartyCodes.MERCHANT.equals(c.get("party_type"))) out.putAll(lookup(code));
        else out.putAll(agents.lookup(code));
        out.put("type", c.get("party_type"));
        return out;
    }

    // ─── public routes ───────────────────────────────────────────────────────

    @GetMapping("/api/v1/codes/scan")
    public ResponseEntity<ApiResponse<Map<String, Object>>> scanRoute(@RequestParam String payload, HttpServletRequest req) {
        return Web.run(() -> scanned(payload), req);
    }

    @GetMapping("/api/v1/merchants/by-code/{code}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> lookupRoute(@PathVariable String code, HttpServletRequest req) {
        return Web.run(() -> lookup(code), req);
    }

    public record StartBody(long amountMinor, String currency, String channel, String customerPhone, String description) {}

    @PostMapping("/api/v1/merchants/by-code/{code}/payments")
    public ResponseEntity<ApiResponse<Map<String, Object>>> startRoute(@PathVariable String code, @RequestBody StartBody b, HttpServletRequest req) {
        return Web.run(() -> {
            CheckoutSession s = start(code, b.amountMinor(), b.currency(), b.channel(), b.customerPhone(), b.description());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("sessionId", s.getId());
            out.put("merchantName", merchant(code).getBusinessName());
            out.put("amountMinor", s.getAmountCents());
            out.put("currency", s.getCurrency());
            return out;
        }, req);
    }
}
