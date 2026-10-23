package zw.co.payport.gateway.ussd;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.agents.AgentService;
import zw.co.payport.gateway.billers.BillerAdaptor;
import zw.co.payport.gateway.billers.BillerRegistry;
import zw.co.payport.gateway.channels.ChannelService;
import zw.co.payport.gateway.entity.CheckoutSession;
import zw.co.payport.gateway.entity.User;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.markets.MarketRegistry;
import zw.co.payport.gateway.model.request.InitiateTransactionRequest;
import zw.co.payport.gateway.model.request.PayTransactionRequest;
import zw.co.payport.gateway.model.response.BundleResponse;
import zw.co.payport.gateway.model.response.TransactionResponse;
import zw.co.payport.gateway.payments.TransactionOutcomeService;
import zw.co.payport.gateway.platform.Web;
import zw.co.payport.gateway.repository.NetworkPrefixRepository;
import zw.co.payport.gateway.repository.UserRepository;
import zw.co.payport.gateway.service.BundleService;
import zw.co.payport.gateway.service.CheckoutSessionService;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;
import zw.co.payport.gateway.wallets.PinVerifier;
import zw.co.payport.gateway.wallets.WalletPaymentService;
import zw.co.payport.gateway.wallets.WalletService;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.function.Supplier;

/**
 * The conversational menus for USSD (aggregator and the Econet, NetOne and Telecel gateways) and
 * WhatsApp. The whole input path is the state ("1*2*0771234567*1*5*1*1"): every hop re-reads it, so
 * a gateway retry gets the same answer. Replies start with "CON " (continue) or "END " (finish).
 *
 * Anyone can transact as a guest and pay with EcoCash, OneMoney, O'mari or InnBucks. On USSD, a
 * number with a PayPort account is greeted by name and may log in with the wallet PIN (or 0 for
 * guest): logged in, the wallet is also a way to pay, and the wallet services are offered.
 *
 * Every payment goes through the same orchestrator, products, billers, risk rules and providers
 * as the consumer site and the app: the backend is the source of truth. A confirmed step runs once
 * per session (ussd_sessions), so a gateway retry never pays twice.
 */
@Slf4j
@Service
public class UssdService {

    static final String GUEST = "0";
    static final String MASK = "#";

    private final JdbcTemplate jdbc;
    private final ChannelService channels;
    private final MarketRegistry markets;
    private final BillerRegistry billers;
    private final NetworkPrefixRepository prefixes;
    private final TransactionOrchestratorService orchestrator;
    private final TransactionOutcomeService outcomes;
    private final UserRepository users;
    private final PinVerifier pins;
    private final WalletService wallets;
    private final WalletPaymentService walletPayments;
    private final AgentService agents;
    private final CheckoutSessionService checkout;
    private final BundleService bundles;
    private final List<String> walletMethods;

    public UssdService(JdbcTemplate jdbc, ChannelService channels, MarketRegistry markets, BillerRegistry billers, NetworkPrefixRepository prefixes,
                       TransactionOrchestratorService orchestrator, TransactionOutcomeService outcomes, UserRepository users, PinVerifier pins,
                       WalletService wallets, WalletPaymentService walletPayments, AgentService agents, CheckoutSessionService checkout, BundleService bundles,
                       @Value("${payport.ussd.payment-methods:ECOCASH,ONEMONEY,OMARI,INNBUCKS}") String walletMethods) {
        this.jdbc = jdbc;
        this.channels = channels;
        this.markets = markets;
        this.billers = billers;
        this.prefixes = prefixes;
        this.orchestrator = orchestrator;
        this.outcomes = outcomes;
        this.users = users;
        this.pins = pins;
        this.wallets = wallets;
        this.walletPayments = walletPayments;
        this.agents = agents;
        this.checkout = checkout;
        this.bundles = bundles;
        this.walletMethods = Arrays.stream(walletMethods.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** The reply to one hop, for gateways that send only the latest input. */
    public record Hop(String text, boolean end) {}

    /**
     * For the network gateways (Econet, NetOne, Telecel), which send only the latest input: the path
     * is kept here (ussd_state) and the wallet PIN is never stored (it is replaced by "#" once checked).
     */
    public Hop converse(String network, String sessionId, String phoneNumber, String input, boolean first) {
        String key = network + ":" + sessionId;
        String msisdn = phoneNumber.replaceAll("[^0-9]", "");
        String clean = input == null ? "" : input.replace("*", "").replace("#", "").trim();
        String path;
        if (first) {
            jdbc.update("DELETE FROM ussd_state WHERE session_id = ?", key);
            path = "";
        } else {
            List<String> rows = jdbc.queryForList("SELECT path FROM ussd_state WHERE session_id = ? AND msisdn = ?", String.class, key, msisdn);
            String before = rows.isEmpty() || rows.get(0) == null ? "" : rows.get(0);
            path = before.isEmpty() ? clean : before + "*" + clean;
        }
        if (path.length() > 480) return new Hop("Session too long. Please dial again.", true);
        String r = handle("USSD", key, "+" + msisdn, path);
        if (Boolean.TRUE.equals(MASKED.get())) {
            int star = path.indexOf('*');
            path = MASK + (star < 0 ? "" : path.substring(star));
        }
        MASKED.remove();
        boolean end = r.startsWith("END");
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        if (end) {
            jdbc.update("DELETE FROM ussd_state WHERE session_id = ?", key);
        } else {
            jdbc.update("INSERT INTO ussd_state (session_id, channel, msisdn, path, created_at, updated_at) VALUES (?, 'USSD', ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE path = VALUES(path), updated_at = VALUES(updated_at)", key, msisdn, path, now, now);
        }
        return new Hop(r.length() >= 4 ? r.substring(4) : r, end);
    }

    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 600_000L)
    public void forgetOldSessions() {
        jdbc.update("DELETE FROM ussd_state WHERE updated_at < ?", Timestamp.valueOf(LocalDateTime.now().minusHours(6)));
    }

    public String handle(String sessionId, String phoneNumber, String text) {
        return handle("USSD", sessionId, phoneNumber, text);
    }

    /**
     * The same menus for every conversational channel (USSD, WHATSAPP): the channel decides which
     * switch applies, the channel recorded on the payment and the reference prefix. Wallet login is
     * offered on USSD only (a PIN is never typed into a chat).
     */
    public String handle(String channel, String sessionId, String phoneNumber, String text) {
        CHANNEL.set(channel);
        MASKED.remove();
        try {
            return menu(channel, sessionId, phoneNumber, text);
        } finally {
            CHANNEL.remove();
        }
    }

    private static final ThreadLocal<String> CHANNEL = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> MASKED = new ThreadLocal<>();

    /** One conversation: who, where, and (once the PIN is checked) whose wallet. */
    private record Ctx(String channel, String session, String msisdn, MarketRegistry.Market market, String network, User holder) {
        String phone() { return "+" + msisdn; }
        boolean wallet() { return holder != null; }
    }

    /** The input path, read one answer at a time. */
    private static final class In {
        private final String[] t;
        private int i;
        In(String[] t, int from) { this.t = t; this.i = from; }
        boolean has() { return i < t.length; }
        String next() { return t[i++].trim(); }
        String last() {
            String v = t[t.length - 1].trim();
            i = t.length;
            return v;
        }
    }

    private String menu(String channel, String sessionId, String phoneNumber, String text) {
        if (!channels.enabled(channel)) return "END PayPort " + ("USSD".equals(channel) ? "USSD" : "on " + capital(channel)) + " is not available at the moment. Please try again later.";
        Optional<MarketRegistry.Market> market = markets.forMsisdn(phoneNumber);
        if (market.isEmpty()) return "END This number is not supported.";
        String msisdn = phoneNumber.replaceAll("[^0-9]", "");
        String[] tokens = text == null || text.isBlank() ? new String[0] : text.split("\\*", -1);
        String network = networkOf(local(msisdn, market.get()));
        try {
            Optional<User> holder = "USSD".equals(channel) ? holder(msisdn) : Optional.empty();
            if (holder.isEmpty()) {
                Ctx c = new Ctx(channel, sessionId, msisdn, market.get(), network, null);
                In in = new In(tokens, 0);
                if (!in.has()) return "CON PayPort\n" + numbered(GUEST_MENU);
                return guest(c, in.next(), in);
            }
            User u = holder.get();
            if (tokens.length == 0) {
                return "CON Welcome to PayPort, " + firstName(u) + "\nEnter your wallet PIN\n0. Continue as guest";
            }
            In in = new In(tokens, 1);
            if (GUEST.equals(tokens[0].trim())) {
                Ctx c = new Ctx(channel, sessionId, msisdn, market.get(), network, null);
                if (!in.has()) return "CON PayPort\n" + numbered(GUEST_MENU);
                return guest(c, in.next(), in);
            }
            User verified = login(sessionId, u, tokens[0].trim());
            Ctx c = new Ctx(channel, sessionId, msisdn, market.get(), network, verified);
            if (!in.has()) return "CON " + firstName(u) + ", what would you like to do?\n" + numbered(WALLET_MENU);
            return wallet(c, in.next(), in);
        } catch (Web.PinRejectedException e) {
            return "END " + clip(e.getMessage(), 150);
        } catch (Exception e) {
            log.warn("[USSD] session {} failed: {}", sessionId, e.getMessage());
            return "END " + clip(e.getMessage() == null ? "Something went wrong. No money was taken." : e.getMessage(), 150);
        }
    }

    static final List<String> GUEST_MENU = List.of("Buy airtime", "Buy data", "Pay a bill", "Pay a merchant", "Check a payment");
    static final List<String> WALLET_MENU = List.of("Buy airtime", "Buy data", "Pay a bill", "Pay a merchant", "Send money", "Top up my wallet",
            "Cash out at an agent", "Balance and statement", "Check a payment");

    private String guest(Ctx c, String choice, In in) {
        return switch (choice) {
            case "1" -> airtime(c, in);
            case "2" -> data(c, in);
            case "3" -> bill(c, in);
            case "4" -> merchant(c, in);
            case "5" -> status(c, in);
            default -> "END Invalid choice.";
        };
    }

    private String wallet(Ctx c, String choice, In in) {
        return switch (choice) {
            case "1" -> airtime(c, in);
            case "2" -> data(c, in);
            case "3" -> bill(c, in);
            case "4" -> merchant(c, in);
            case "5" -> send(c, in);
            case "6" -> topUp(c, in);
            case "7" -> cashOut(c, in);
            case "8" -> balance(c, in);
            case "9" -> status(c, in);
            default -> "END Invalid choice.";
        };
    }

    // ─── wallet login ────────────────────────────────────────────────────────

    private Optional<User> holder(String msisdn) {
        return users.findByPhone("+" + msisdn)
                .filter(u -> "CUSTOMER".equalsIgnoreCase(u.getRole()) && u.getPinHash() != null && Boolean.TRUE.equals(u.getIsActive()));
    }

    /** The PIN is checked once per session; later hops carry "#" (gateways) or the same PIN (aggregator). */
    private User login(String session, User u, String pin) {
        List<Long> done = jdbc.queryForList("SELECT wallet_user_id FROM ussd_state WHERE session_id = ? AND wallet_user_id IS NOT NULL", Long.class, session);
        if (!done.isEmpty() && done.get(0) == u.getId().longValue()) {
            MASKED.set(true);
            return u;
        }
        if (MASK.equals(pin)) throw new Web.PinRejectedException("Your session has expired. Please dial again.");
        User v = pins.verify(u.getPhone(), pin);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO ussd_state (session_id, channel, msisdn, path, wallet_user_id, created_at, updated_at) VALUES (?, ?, ?, '', ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE wallet_user_id = VALUES(wallet_user_id), updated_at = VALUES(updated_at)",
                session, CHANNEL.get() == null ? "USSD" : CHANNEL.get(), u.getPhone().replaceAll("[^0-9]", ""), v.getId(), now, now);
        MASKED.set(true);
        return v;
    }

    // ─── airtime ─────────────────────────────────────────────────────────────

    private String airtime(Ctx c, In in) {
        if (!in.has()) return "CON Airtime for:\n1. My number\n2. Another number";
        String recipient = recipient(c, in);
        if (recipient == null) return "CON Enter the phone number:";
        String network = networkOf(local(recipient, c.market()));
        if (network == null) return "END That network is not supported for airtime.";
        List<String> ccys = liveCurrencies(c.market());
        if (!in.has()) return "CON Currency:\n" + numbered(ccys);
        String ccy = pick(ccys, in.next());
        if (!in.has()) return "CON Amount (" + ccy + "):";
        long amount = minor(in.next());
        String local = local(recipient, c.market());
        return pay(c, in, "AIRTIME_" + network, "+" + recipient, amount, ccy, "Buy " + ccy + " " + major(amount) + " airtime for " + local, "airtime", true);
    }

    /** "1. My number / 2. Another number": the recipient's digits, or null while the number is still to be typed. */
    private String recipient(Ctx c, In in) {
        String who = in.next();
        if ("1".equals(who)) return c.msisdn();
        if (!"2".equals(who)) throw new IllegalArgumentException("Invalid choice.");
        if (!in.has()) return null;
        String n = in.next().replaceAll("[^0-9]", "");
        String cc = c.market().phoneCountryCode();
        if (n.startsWith("0")) n = cc + n.substring(1);
        else if (!n.startsWith(cc)) n = cc + n;
        if (markets.forMsisdn("+" + n).isEmpty() || n.length() < cc.length() + 8) throw new IllegalArgumentException("Invalid phone number.");
        return n;
    }

    // ─── data ────────────────────────────────────────────────────────────────

    private String data(Ctx c, In in) {
        if (!in.has()) return "CON Data for:\n1. My number\n2. Another number";
        String recipient = recipient(c, in);
        if (recipient == null) return "CON Enter the phone number:";
        String network = networkOf(local(recipient, c.market()));
        if (network == null) return "END That network is not supported for data.";
        List<String> ccys = liveCurrencies(c.market());
        if (!in.has()) return "CON Currency:\n" + numbered(ccys);
        String ccy = pick(ccys, in.next());
        List<BundleResponse> list = new ArrayList<>();
        for (BundleResponse b : bundles.getActiveBundles(network, ccy, null)) {
            if (b.getPriceValue() != null && b.getPriceValue() > 0) list.add(b);
            if (list.size() == 8) break;
        }
        if (list.isEmpty()) return "END No " + capital(network) + " data bundles in " + ccy + " at the moment.";
        if (!in.has()) {
            List<String> names = list.stream().map(b -> clip(b.getBundleName(), 22) + " " + major(cents(b.getPriceValue()))).toList();
            return "CON Choose a bundle (" + ccy + "):\n" + numbered(names);
        }
        BundleResponse b = pick(list, in.next());
        long amount = cents(b.getPriceValue());
        return pay(c, in, "DATA_" + network, "+" + recipient, amount, ccy,
                "Buy " + clip(b.getBundleName(), 30) + " (" + ccy + " " + major(amount) + ") for " + local(recipient, c.market()), clip(b.getBundleName(), 30), true);
    }

    // ─── bills ───────────────────────────────────────────────────────────────

    private String bill(Ctx c, In in) {
        List<BillerAdaptor.Profile> list = new ArrayList<>();
        for (BillerAdaptor.Profile p : billers.catalogue()) if (productFor(p.code()).isPresent()) list.add(p);
        if (list.isEmpty()) return "END No bills can be paid here at the moment.";
        if (!in.has()) return "CON Pay which bill?\n" + numbered(list.stream().map(BillerAdaptor.Profile::name).toList());
        BillerAdaptor.Profile p = pick(list, in.next());
        if (!in.has()) return "CON " + p.accountLabel() + ":";
        String account = in.next();
        BillerAdaptor biller = billers.find(p.code()).orElseThrow();
        String invalid = biller.validateAccount(account);
        if (invalid != null) return "END " + clip(invalid, 150);
        List<String> ccys = new ArrayList<>(liveCurrencies(c.market()));
        ccys.retainAll(p.currencies());
        if (ccys.isEmpty()) return "END " + p.name() + " cannot be paid in this country's currencies.";
        if (!in.has()) {
            BillerAdaptor.Account a;
            try {
                a = biller.lookup(account, ccys.get(0), c.phone());
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
        String ccy = pick(ccys, in.next());
        if (!in.has()) return "CON Amount (" + ccy + "):";
        long amount = minor(in.next());
        String limit = biller.validateAmount(amount, ccy);
        if (limit != null) return "END " + clip(limit, 150);
        return pay(c, in, productFor(p.code()).orElseThrow(), account, amount, ccy, "Pay " + ccy + " " + major(amount) + " to " + p.name() + " " + account, p.name(), true);
    }

    private Optional<String> productFor(String billerCode) {
        List<String> codes = jdbc.queryForList("SELECT code FROM product_types WHERE provider_code = ? AND is_active = TRUE AND category = 'BILLS' ORDER BY id LIMIT 1",
                String.class, billerCode);
        return codes.stream().findFirst();
    }

    // ─── merchant (hosted checkout code) ─────────────────────────────────────

    private String merchant(Ctx c, In in) {
        if (!in.has()) return "CON Enter the merchant payment code:";
        long id;
        try {
            id = Long.parseLong(in.next().replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return "END Invalid payment code.";
        }
        CheckoutSession s;
        try {
            s = checkout.getPublic(id);
        } catch (RuntimeException e) {
            return "END No payment with that code.";
        }
        if (!"CREATED".equals(s.getStatus())) return "END This payment is " + s.getStatus().toLowerCase(Locale.ROOT) + ".";
        List<String> names = jdbc.queryForList("SELECT business_name FROM merchants WHERE id = ?", String.class, s.getMerchantId());
        String merchant = names.isEmpty() ? "the merchant" : clip(names.get(0), 30);
        long amount = s.getAmountCents();
        String ccy = s.getCurrency();
        return payMerchant(c, in, id, merchant, amount, ccy);
    }

    private String payMerchant(Ctx c, In in, long sessionId, String merchant, long amount, String ccy) {
        List<String> methods = methods(c.wallet());
        if (!in.has()) return "CON Pay " + merchant + " " + ccy + " " + major(amount) + " with:\n" + numbered(methods.stream().map(this::methodName).toList());
        String method = pick(methods, in.next());
        String payer = c.phone();
        if (needsOwnNumber(method, c.network())) {
            if (!in.has()) return "CON Enter your " + methodName(method) + " number:";
            payer = "+" + phone(in.next(), c.market());
        }
        if (!in.has()) return "CON Pay " + merchant + " " + ccy + " " + major(amount) + " with " + methodName(method) + "?\n1. Confirm\n2. Cancel";
        if (!"1".equals(in.next())) return "END Cancelled. No money was taken.";
        if (in.has()) return omari(c, in.last());
        final String from = payer;
        return once(c, () -> {
            try {
                if (WalletService.RAIL.equals(method)) {
                    Map<String, Object> r = walletPayments.payCheckoutAs(c.holder(), sessionId, "ussd");
                    record(c, (String) r.get("reference"));
                    return walletResult(r, merchant);
                }
                CheckoutSessionService.PaymentSubmissionResult r = checkout.submitPayment(sessionId, from, null, method, "ussd");
                String ref = r.session().getTransactionReference();
                record(c, ref);
                return afterSubmit(ref, method, from, ccy, amount, merchant, null);
            } catch (Exception e) {
                return "END " + clip(e.getMessage() == null ? "Payment could not be started. No money was taken." : e.getMessage(), 150);
            }
        });
    }

    // ─── wallet services ─────────────────────────────────────────────────────

    private String send(Ctx c, In in) {
        if (!in.has()) return "CON Send to (phone or wallet number):";
        String to = in.next();
        if (!to.toUpperCase(Locale.ROOT).startsWith("W")) to = "+" + phone(to, c.market());
        List<WalletService.Wallet> mine = mine(c);
        if (!in.has()) return "CON From which wallet?\n" + numbered(mine.stream().map(w -> w.currency() + " " + major(wallets.balance(w))).toList());
        WalletService.Wallet w = pick(mine, in.next());
        if (!in.has()) return "CON Amount (" + w.currency() + "):";
        long amount = minor(in.next());
        if (!in.has()) return "CON Send " + w.currency() + " " + major(amount) + " to " + to + "?\n1. Confirm\n2. Cancel";
        if (!"1".equals(in.next())) return "END Cancelled. No money was sent.";
        final String recipient = to;
        return once(c, () -> {
            try {
                Map<String, Object> r = wallets.transfer(c.holder().getId(), recipient, w.currency(), amount, "ussd:" + c.session(), "customer:" + c.holder().getPhone());
                return "END Sent " + w.currency() + " " + major(amount) + " to " + r.get("to") + ". Balance " + w.currency() + " " + major(((Number) r.get("balanceMinor")).longValue()) + ".";
            } catch (RuntimeException e) {
                return "END " + clip(e.getMessage() == null ? "The transfer could not be made." : e.getMessage(), 150);
            }
        });
    }

    private String topUp(Ctx c, In in) {
        List<WalletService.Wallet> mine = mine(c);
        if (!in.has()) return "CON Top up which wallet?\n" + numbered(mine.stream().map(w -> w.currency() + " " + w.ref()).toList());
        WalletService.Wallet w = pick(mine, in.next());
        if (!in.has()) return "CON Amount (" + w.currency() + "):";
        long amount = minor(in.next());
        return pay(c, in, "WALLET_TOPUP", w.ref(), amount, w.currency(), "Top up " + w.currency() + " " + major(amount) + " to your wallet", "wallet top-up", false);
    }

    private String cashOut(Ctx c, In in) {
        List<WalletService.Wallet> mine = mine(c);
        if (!in.has()) return "CON Cash out from which wallet?\n" + numbered(mine.stream().map(w -> w.currency() + " " + major(wallets.balance(w))).toList());
        WalletService.Wallet w = pick(mine, in.next());
        if (!in.has()) return "CON Amount (" + w.currency() + "):";
        long amount = minor(in.next());
        if (!in.has()) return "CON Get a code to cash out " + w.currency() + " " + major(amount) + " at a PayPort agent?\n1. Confirm\n2. Cancel";
        if (!"1".equals(in.next())) return "END Cancelled.";
        return once(c, () -> {
            try {
                Map<String, Object> r = agents.issueCashOutCodeFor(c.holder(), w.currency(), amount);
                return "END Cash-out code " + r.get("code") + " for " + w.currency() + " " + major(amount)
                        + ". Show it with your phone number to a PayPort agent. It works once and expires at "
                        + String.valueOf(r.get("expiresAt")).replace('T', ' ').substring(0, 16) + ".";
            } catch (RuntimeException e) {
                return "END " + clip(e.getMessage() == null ? "A code could not be issued." : e.getMessage(), 150);
            }
        });
    }

    private String balance(Ctx c, In in) {
        List<WalletService.Wallet> mine = mine(c);
        StringBuilder sb = new StringBuilder("END ");
        for (WalletService.Wallet w : mine) sb.append(w.currency()).append(" ").append(major(wallets.balance(w))).append(" (").append(w.ref()).append(")\n");
        WalletService.Wallet first = mine.get(0);
        List<Map<String, Object>> lines = wallets.statement(first, 4);
        if (!lines.isEmpty()) {
            sb.append("Last ").append(first.currency()).append(":\n");
            for (Map<String, Object> l : lines) {
                long a = ((Number) l.get("amount_minor")).longValue();
                sb.append("DR".equals(l.get("direction")) ? "-" : "+").append(major(a)).append(" ").append(clip(String.valueOf(l.get("kind")).replace('_', ' ').toLowerCase(Locale.ROOT), 14)).append("\n");
            }
        }
        return clip(sb.toString().trim(), 180);
    }

    private List<WalletService.Wallet> mine(Ctx c) {
        List<WalletService.Wallet> mine = wallets.forUser(c.holder().getId()).stream().filter(w -> !"CLOSED".equals(w.status())).toList();
        if (mine.isEmpty()) throw new IllegalArgumentException("You have no PayPort wallet yet. Open one in the PayPort app.");
        return mine;
    }

    // ─── status ──────────────────────────────────────────────────────────────

    private String status(Ctx c, In in) {
        if (!in.has()) return "CON Payment reference:";
        String ref = in.next().toUpperCase(Locale.ROOT);
        // Only the paying phone (or the number that started it here) may check a payment.
        Integer mine = jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE reference = ? AND (REPLACE(customer_phone, '+', '') = ? "
                + "OR reference IN (SELECT reference FROM ussd_sessions WHERE msisdn = ?))", Integer.class, ref, c.msisdn(), c.msisdn());
        if (mine == null || mine == 0) return "END No payment with that reference for this number.";
        return outcomes.current(ref).map(r -> "END " + r.outcome().name().replace('_', ' ') + (r.message() == null ? "" : ". " + clip(r.message(), 120)) + "\nRef " + ref)
                .orElse("END No payment with that reference for this number.");
    }

    // ─── paying ──────────────────────────────────────────────────────────────

    /** Method, payer number (when the method needs one on another network), confirmation, then payment. */
    private String pay(Ctx c, In in, String product, String recipient, long amount, String ccy, String summary, String what, boolean walletAllowed) {
        List<String> methods = methods(c.wallet() && walletAllowed);
        if (!in.has()) return "CON Pay with:\n" + numbered(methods.stream().map(this::methodName).toList());
        String method = pick(methods, in.next());
        String payer = c.phone();
        if (needsOwnNumber(method, c.network())) {
            if (!in.has()) return "CON Enter your " + methodName(method) + " number:";
            payer = "+" + phone(in.next(), c.market());
        }
        if (!in.has()) return "CON " + summary + " with " + methodName(method) + "?\n1. Confirm\n2. Cancel";
        if (!"1".equals(in.next())) return "END Cancelled. No money was taken.";
        if (in.has()) return omari(c, in.last());
        final String from = payer;
        return once(c, () -> start(c, product, recipient, amount, ccy, method, from, what));
    }

    private String start(Ctx c, String product, String recipient, long amount, String ccy, String method, String payer, String what) {
        String channel = c.channel();
        String ref = reference(channel, c.session());
        record(c, ref);
        try {
            InitiateTransactionRequest init = new InitiateTransactionRequest();
            init.setReference(ref);
            init.setProductTypeCode(product);
            init.setRecipientIdentifier(recipient);
            init.setAmountCents(amount);
            init.setCurrency(ccy);
            init.setCustomerPhone(WalletService.RAIL.equals(method) ? c.holder().getPhone() : payer);
            init.setChannel(channel);
            orchestrator.initiateTransaction(init, "ussd");
            if (WalletService.RAIL.equals(method)) return walletResult(walletPayments.payTransactionAs(c.holder(), ref), what);
            PayTransactionRequest pay = new PayTransactionRequest();
            pay.setReference(ref);
            pay.setPaymentMethodCode(method);
            pay.setCustomerPhone(payer);
            TransactionResponse r = orchestrator.submitPayment(pay);
            if (r.getStatus() == TransactionStatus.PAYMENT_FAILED) {
                return "END " + clip(r.getFailureReason() == null ? "The payment was declined. No money was taken." : r.getFailureReason(), 130) + " Ref " + ref;
            }
            return afterSubmit(ref, method, payer, ccy, amount, what, r.getPaymentCode());
        } catch (Exception e) {
            return "END " + clip(e.getMessage() == null ? "Payment could not be started. No money was taken." : e.getMessage(), 140) + " Ref " + ref;
        }
    }

    /** What the customer does next, per method. */
    private String afterSubmit(String ref, String method, String payer, String ccy, long amount, String what, String code) {
        String m = methodName(method);
        if ("OMARI".equals(method)) return "CON Enter the code " + m + " sent to " + payer + " to pay " + ccy + " " + major(amount) + ":";
        if ("INNBUCKS".equals(method)) {
            if (code == null && ref != null) {
                List<String> c = jdbc.queryForList("SELECT provider_reference FROM transactions WHERE reference = ?", String.class, ref);
                code = c.isEmpty() ? null : c.get(0);
            }
            return "END Your InnBucks payment code is " + (code == null ? "on its way by SMS" : code) + ". Approve it in the InnBucks app to pay "
                    + ccy + " " + major(amount) + " for " + clip(what, 30) + ". Ref " + ref;
        }
        return "END Approve the " + m + " prompt to pay " + ccy + " " + major(amount) + " for " + clip(what, 30)
                + ". You will get an SMS when it is done. Ref " + ref;
    }

    private String walletResult(Map<String, Object> r, String what) {
        String status = String.valueOf(r.get("status"));
        String ccy = String.valueOf(r.get("currency"));
        String bal = r.get("balanceMinor") == null ? "" : " Balance " + ccy + " " + major(((Number) r.get("balanceMinor")).longValue()) + ".";
        if ("PAYMENT_FAILED".equals(status) || "FAILED".equals(status)) {
            return "END " + clip(r.get("message") == null ? "The wallet payment failed." : String.valueOf(r.get("message")), 120) + bal;
        }
        return "END Paid from your wallet for " + clip(what, 30) + "." + bal + " Ref " + r.get("reference");
    }

    /** O'mari confirms with the code it texts the payer. A wrong code with attempts left asks again. */
    private String omari(Ctx c, String otp) {
        List<String> refs = jdbc.queryForList("SELECT reference FROM ussd_sessions WHERE session_id = ?", String.class, c.session());
        if (refs.isEmpty() || refs.get(0) == null) return "END Nothing to confirm. Please dial again.";
        String ref = refs.get(0);
        try {
            TransactionResponse r = orchestrator.confirmOmariOtp(ref, otp);
            if (r.getStatus() == TransactionStatus.PAYMENT_FAILED) return "END " + clip(r.getFailureReason() == null ? "O'mari declined the payment." : r.getFailureReason(), 130) + " Ref " + ref;
            return "END Payment confirmed. You will get an SMS when it is done. Ref " + ref;
        } catch (IllegalStateException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("attempt")) return "CON " + clip(msg, 120) + "\nEnter the code:";
            return outcomes.current(ref).map(o -> "END " + o.outcome().name().replace('_', ' ') + ". Ref " + ref).orElse("END " + clip(msg, 140));
        } catch (Exception e) {
            return "END " + clip(e.getMessage() == null ? "The code could not be checked." : e.getMessage(), 140) + " Ref " + ref;
        }
    }

    /** A confirmed step runs once per session: a gateway retry gets the first answer. */
    private String once(Ctx c, Supplier<String> action) {
        List<Map<String, Object>> done = jdbc.queryForList("SELECT outcome FROM ussd_sessions WHERE session_id = ?", c.session());
        if (!done.isEmpty() && done.get(0).get("outcome") != null) return (String) done.get(0).get("outcome");
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO ussd_sessions (session_id, msisdn, created_at, updated_at) VALUES (?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at)", c.session(), c.msisdn(), now, now);
        String reply = action.get();
        jdbc.update("UPDATE ussd_sessions SET outcome = ?, updated_at = ? WHERE session_id = ?", clip(reply, 500), Timestamp.valueOf(LocalDateTime.now()), c.session());
        return reply;
    }

    private void record(Ctx c, String reference) {
        if (reference == null) return;
        jdbc.update("UPDATE ussd_sessions SET reference = ?, updated_at = ? WHERE session_id = ?", reference, Timestamp.valueOf(LocalDateTime.now()), c.session());
    }

    /** EcoCash works on Econet numbers and OneMoney on NetOne numbers: from another network the payer's number is asked. */
    private static boolean needsOwnNumber(String method, String network) {
        return ("ECOCASH".equals(method) && !"ECONET".equals(network)) || ("ONEMONEY".equals(method) && !"NETONE".equals(network));
    }

    private static String capital(String c) {
        return c.isEmpty() ? c : c.charAt(0) + c.substring(1).toLowerCase(Locale.ROOT);
    }

    public static String reference(String session) {
        return reference("USSD", session);
    }

    public static String reference(String channel, String session) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((channel + "|" + session).getBytes(StandardCharsets.UTF_8));
            String d = LocalDate.now().toString().replace("-", "");
            String prefix = "USSD".equals(channel) ? "USSD" : "WHATSAPP".equals(channel) ? "WA" : channel;
            if ("USSD".equals(channel)) h = MessageDigest.getInstance("SHA-256").digest(session.getBytes(StandardCharsets.UTF_8));
            return "PP-" + prefix + "-" + d + "-" + HexFormat.of().formatHex(h).substring(0, 10).toUpperCase(Locale.ROOT);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private String networkOf(String local) {
        if (local == null || local.length() < 4) return null;
        return prefixes.findByPrefix(local.substring(0, 4)).map(p -> p.getNetworkCode()).orElse(null);
    }

    private static String local(String msisdn, MarketRegistry.Market m) {
        return msisdn.startsWith(m.phoneCountryCode()) ? "0" + msisdn.substring(m.phoneCountryCode().length()) : msisdn;
    }

    private static String phone(String typed, MarketRegistry.Market m) {
        String n = typed.replaceAll("[^0-9]", "");
        String cc = m.phoneCountryCode();
        if (n.startsWith("0")) n = cc + n.substring(1);
        else if (!n.startsWith(cc)) n = cc + n;
        if (n.length() < cc.length() + 8) throw new IllegalArgumentException("Invalid phone number.");
        return n;
    }

    private static String firstName(User u) {
        String n = u.getFullName() == null ? "" : u.getFullName().trim();
        return n.isEmpty() ? "customer" : clip(n.split("\\s+")[0], 20);
    }

    private List<String> liveCurrencies(MarketRegistry.Market m) {
        List<String> out = new ArrayList<>();
        if (!m.live()) return out;
        for (MarketRegistry.Currency c : m.currencies()) if (c.enabled() && c.live()) out.add(c.code());
        if (out.isEmpty()) throw new IllegalArgumentException("Payments are not available in this country at the moment.");
        return out;
    }

    private List<String> methods(boolean withWallet) {
        List<String> active = jdbc.queryForList("SELECT code FROM payment_methods WHERE is_active = TRUE ORDER BY display_order", String.class);
        List<String> out = new ArrayList<>();
        if (withWallet) out.add(WalletService.RAIL);
        for (String m : walletMethods) if (active.contains(m)) out.add(m);
        if (out.isEmpty()) throw new IllegalArgumentException("No payment methods are available at the moment.");
        return out;
    }

    private String methodName(String code) {
        if (WalletService.RAIL.equals(code)) return "PayPort wallet";
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

    private static long cents(Double major) {
        return BigDecimal.valueOf(major).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    private static String major(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }

    private static String clip(String s, int n) {
        return s == null ? "" : s.length() <= n ? s : s.substring(0, n);
    }
}
