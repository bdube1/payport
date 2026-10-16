package zw.co.payport.gateway.ussd;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.billers.BillerAdaptor;
import zw.co.payport.gateway.billers.BillerRegistry;
import zw.co.payport.gateway.channels.ChannelService;
import zw.co.payport.gateway.markets.MarketRegistry;
import zw.co.payport.gateway.model.request.InitiateTransactionRequest;
import zw.co.payport.gateway.model.request.PayTransactionRequest;
import zw.co.payport.gateway.payments.TransactionOutcomeService;
import zw.co.payport.gateway.repository.NetworkPrefixRepository;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 8 USSD channel (menus harvested in spirit from psp-foundation, rebuilt on the live payment
 * path). Africa's Talking style: the whole input path arrives on every hop ("2*1*0123456*1*20*1*1"),
 * so the path is the state. Replies start with "CON " (continue) or "END " (finish).
 *
 *   1  Buy airtime for this number      → currency → amount → wallet → confirm
 *   2  Pay a bill                        → biller → account (looked up: holder, payable) → currency → amount → wallet → confirm
 *   3  Check a payment                   → reference → the same outcome the app shows
 *
 * Payments run through the same orchestrator as every other channel (channel USSD): the same
 * product, currency, risk, biller and provider rules apply. A confirmed step is idempotent per USSD
 * session (the reference is derived from the session id), so a gateway retry never pays twice.
 * Currencies offered are the market's live currencies; the market comes from the phone number.
 */
@Slf4j
@Service
public class UssdService {

    private final JdbcTemplate jdbc;
    private final ChannelService channels;
    private final MarketRegistry markets;
    private final BillerRegistry billers;
    private final NetworkPrefixRepository prefixes;
    private final TransactionOrchestratorService orchestrator;
    private final TransactionOutcomeService outcomes;
    private final List<String> walletMethods;

    public UssdService(JdbcTemplate jdbc, ChannelService channels, MarketRegistry markets, BillerRegistry billers, NetworkPrefixRepository prefixes,
                       TransactionOrchestratorService orchestrator, TransactionOutcomeService outcomes,
                       @Value("${payport.ussd.payment-methods:ECOCASH,ONEMONEY,TELECASH}") String walletMethods) {
        this.jdbc = jdbc;
        this.channels = channels;
        this.markets = markets;
        this.billers = billers;
        this.prefixes = prefixes;
        this.orchestrator = orchestrator;
        this.outcomes = outcomes;
        this.walletMethods = Arrays.stream(walletMethods.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public String handle(String sessionId, String phoneNumber, String text) {
        if (!channels.enabled("USSD")) return "END PayPort USSD is not available at the moment. Please try again later.";
        Optional<MarketRegistry.Market> market = markets.forMsisdn(phoneNumber);
        if (market.isEmpty()) return "END This number is not supported.";
        String msisdn = phoneNumber.replaceAll("[^0-9]", "");
        String[] in = text == null || text.isBlank() ? new String[0] : text.split("\\*", -1);
        try {
            if (in.length == 0) return "CON PayPort\n1. Buy airtime\n2. Pay a bill\n3. Check a payment";
            return switch (in[0]) {
                case "1" -> airtime(sessionId, msisdn, market.get(), Arrays.copyOfRange(in, 1, in.length));
                case "2" -> bill(sessionId, msisdn, market.get(), Arrays.copyOfRange(in, 1, in.length));
                case "3" -> status(msisdn, Arrays.copyOfRange(in, 1, in.length));
                default -> "END Invalid choice.";
            };
        } catch (Exception e) {
            log.warn("[USSD] session {} failed: {}", sessionId, e.getMessage());
            return "END " + clip(e.getMessage() == null ? "Something went wrong. No money was taken." : e.getMessage(), 150);
        }
    }

    // ─── airtime ─────────────────────────────────────────────────────────────

    private String airtime(String session, String msisdn, MarketRegistry.Market m, String[] s) {
        String local = "0" + msisdn.substring(m.phoneCountryCode().length());
        String network = local.length() >= 4 ? prefixes.findByPrefix(local.substring(0, 4)).map(p -> p.getNetworkCode()).orElse(null) : null;
        if (network == null) return "END Your network is not supported for airtime.";
        String product = "AIRTIME_" + network;
        List<String> ccys = liveCurrencies(m);
        if (s.length == 0) return "CON Currency:\n" + numbered(ccys);
        String ccy = pick(ccys, s[0]);
        if (s.length == 1) return "CON Amount (" + ccy + "):";
        long amount = minor(s[1]);
        List<String> methods = methods();
        if (s.length == 2) return "CON Pay with:\n" + numbered(methods.stream().map(this::methodName).toList());
        String method = pick(methods, s[2]);
        if (s.length == 3) return "CON Buy " + ccy + " " + major(amount) + " airtime for " + local + " with " + methodName(method) + "?\n1. Confirm\n2. Cancel";
        if (!"1".equals(s[3])) return "END Cancelled. No money was taken.";
        return pay(session, msisdn, product, "+" + msisdn, amount, ccy, method, "airtime");
    }

    // ─── bills ───────────────────────────────────────────────────────────────

    private String bill(String session, String msisdn, MarketRegistry.Market m, String[] s) {
        List<BillerAdaptor.Profile> list = new ArrayList<>();
        for (BillerAdaptor.Profile p : billers.catalogue()) if (productFor(p.code()).isPresent()) list.add(p);
        if (list.isEmpty()) return "END No bills can be paid by USSD at the moment.";
        if (s.length == 0) return "CON Pay which bill?\n" + numbered(list.stream().map(BillerAdaptor.Profile::name).toList());
        BillerAdaptor.Profile p = pick(list, s[0]);
        if (s.length == 1) return "CON " + p.accountLabel() + ":";
        String account = s[1].trim();
        BillerAdaptor biller = billers.find(p.code()).orElseThrow();
        String invalid = biller.validateAccount(account);
        if (invalid != null) return "END " + clip(invalid, 150);
        List<String> ccys = new ArrayList<>(liveCurrencies(m));
        ccys.retainAll(p.currencies());
        if (ccys.isEmpty()) return "END " + p.name() + " cannot be paid in this country's currencies.";
        if (s.length == 2) {
            BillerAdaptor.Account a;
            try {
                a = biller.lookup(account, ccys.get(0), "+" + msisdn);
            } catch (IllegalArgumentException e) {
                return "END " + clip(e.getMessage(), 150);
            } catch (Exception e) {
                return "END " + clip(p.name() + " is not responding. Please try again later. No money was taken.", 150);
            }
            if (!a.payable()) return "END " + clip(a.notPayableReason() == null ? "This account cannot be paid." : a.notPayableReason(), 150);
            List<String> accepted = new ArrayList<>(ccys);
            if (a.acceptedCurrencies() != null && !a.acceptedCurrencies().isEmpty()) accepted.retainAll(a.acceptedCurrencies());
            if (accepted.isEmpty()) return "END This account cannot be paid in the currencies offered.";
            return "CON " + clip(a.holderName() == null ? account : a.holderName(), 40) + "\nCurrency:\n" + numbered(accepted);
        }
        String ccy = pick(ccys, s[2]);
        if (s.length == 3) return "CON Amount (" + ccy + "):";
        long amount = minor(s[3]);
        String limit = biller.validateAmount(amount, ccy);
        if (limit != null) return "END " + clip(limit, 150);
        List<String> methods = methods();
        if (s.length == 4) return "CON Pay with:\n" + numbered(methods.stream().map(this::methodName).toList());
        String method = pick(methods, s[4]);
        if (s.length == 5) return "CON Pay " + ccy + " " + major(amount) + " to " + p.name() + " " + account + " with " + methodName(method) + "?\n1. Confirm\n2. Cancel";
        if (!"1".equals(s[5])) return "END Cancelled. No money was taken.";
        return pay(session, msisdn, productFor(p.code()).orElseThrow(), account, amount, ccy, method, p.name());
    }

    private Optional<String> productFor(String billerCode) {
        List<String> codes = jdbc.queryForList("SELECT code FROM product_types WHERE provider_code = ? AND is_active = TRUE AND category = 'BILLS' ORDER BY id LIMIT 1",
                String.class, billerCode);
        return codes.stream().findFirst();
    }

    // ─── status ──────────────────────────────────────────────────────────────

    private String status(String msisdn, String[] s) {
        if (s.length == 0) return "CON Payment reference:";
        String ref = s[0].trim().toUpperCase(Locale.ROOT);
        // Only the paying phone may check a payment by USSD.
        Integer mine = jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE reference = ? AND REPLACE(customer_phone, '+', '') = ?", Integer.class, ref, msisdn);
        if (mine == null || mine == 0) return "END No payment with that reference for this number.";
        return outcomes.current(ref).map(r -> "END " + r.outcome().name().replace('_', ' ') + (r.message() == null ? "" : ". " + clip(r.message(), 120)) + "\nRef " + ref)
                .orElse("END No payment with that reference for this number.");
    }

    // ─── payment ─────────────────────────────────────────────────────────────

    private String pay(String session, String msisdn, String product, String recipient, long amount, String ccy, String method, String what) {
        String ref = reference(session);
        List<Map<String, Object>> done = jdbc.queryForList("SELECT reference, outcome FROM ussd_sessions WHERE session_id = ?", session);
        if (!done.isEmpty() && done.get(0).get("outcome") != null) return (String) done.get(0).get("outcome");   // gateway retry: same answer, no second payment
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO ussd_sessions (session_id, msisdn, reference, created_at, updated_at) VALUES (?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at)", session, msisdn, ref, Timestamp.valueOf(now), Timestamp.valueOf(now));
        String reply;
        try {
            InitiateTransactionRequest init = new InitiateTransactionRequest();
            init.setReference(ref);
            init.setProductTypeCode(product);
            init.setRecipientIdentifier(recipient);
            init.setAmountCents(amount);
            init.setCurrency(ccy);
            init.setCustomerPhone("+" + msisdn);
            init.setChannel("USSD");
            orchestrator.initiateTransaction(init, "ussd");
            PayTransactionRequest pay = new PayTransactionRequest();
            pay.setReference(ref);
            pay.setPaymentMethodCode(method);
            pay.setCustomerPhone("+" + msisdn);
            orchestrator.submitPayment(pay);
            reply = "END Approve the " + methodName(method) + " prompt to pay " + ccy + " " + major(amount) + " for " + clip(what, 30)
                    + ". You will get an SMS when it is done. Ref " + ref;
        } catch (Exception e) {
            reply = "END " + clip(e.getMessage() == null ? "Payment could not be started. No money was taken." : e.getMessage(), 140) + " Ref " + ref;
        }
        jdbc.update("UPDATE ussd_sessions SET outcome = ?, updated_at = ? WHERE session_id = ?", reply, Timestamp.valueOf(LocalDateTime.now()), session);
        return reply;
    }

    public static String reference(String session) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(session.getBytes(StandardCharsets.UTF_8));
            String d = LocalDate.now().toString().replace("-", "");
            return "PP-USSD-" + d + "-" + HexFormat.of().formatHex(h).substring(0, 10).toUpperCase(Locale.ROOT);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private List<String> liveCurrencies(MarketRegistry.Market m) {
        List<String> out = new ArrayList<>();
        if (!m.live()) return out;
        for (MarketRegistry.Currency c : m.currencies()) if (c.enabled() && c.live()) out.add(c.code());
        if (out.isEmpty()) throw new IllegalArgumentException("Payments are not available in this country at the moment.");
        return out;
    }

    private List<String> methods() {
        List<String> active = jdbc.queryForList("SELECT code FROM payment_methods WHERE is_active = TRUE ORDER BY display_order", String.class);
        List<String> out = active.stream().filter(walletMethods::contains).toList();
        if (out.isEmpty()) throw new IllegalArgumentException("No wallet payments are available at the moment.");
        return out;
    }

    private String methodName(String code) {
        List<String> n = jdbc.queryForList("SELECT name FROM payment_methods WHERE code = ?", String.class, code);
        return n.isEmpty() ? code : n.get(0);
    }

    private static <T> T pick(List<T> options, String choice) {
        int i;
        try { i = Integer.parseInt(choice.trim()); } catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid choice."); }
        if (i < 1 || i > options.size()) throw new IllegalArgumentException("Invalid choice.");
        return options.get(i - 1);
    }

    private static String numbered(List<String> options) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < options.size(); i++) sb.append(i + 1).append(". ").append(options.get(i)).append(i < options.size() - 1 ? "\n" : "");
        return sb.toString();
    }

    static long minor(String amount) {
        try {
            long v = new BigDecimal(amount.trim()).movePointRight(2).longValueExact();
            if (v <= 0) throw new IllegalArgumentException("Enter an amount above zero.");
            return v;
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("Invalid amount.");
        }
    }

    private static String major(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }

    private static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
