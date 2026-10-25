package zw.co.payport.gateway.ussd;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.agents.AgentService;
import zw.co.payport.gateway.billers.BillerAdaptor;
import zw.co.payport.gateway.billers.BillerController;
import zw.co.payport.gateway.billers.BillerRegistry;
import zw.co.payport.gateway.cards.CardService;
import zw.co.payport.gateway.channels.ChannelService;
import zw.co.payport.gateway.entity.Beneficiary;
import zw.co.payport.gateway.entity.ProductCurrency;
import zw.co.payport.gateway.entity.ProductType;
import zw.co.payport.gateway.entity.Transaction;
import zw.co.payport.gateway.entity.User;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.markets.MarketRegistry;
import zw.co.payport.gateway.merchant.MerchantCodePayments;
import zw.co.payport.gateway.model.request.AuthRequests;
import zw.co.payport.gateway.model.request.InitiateTransactionRequest;
import zw.co.payport.gateway.model.request.PayTransactionRequest;
import zw.co.payport.gateway.model.response.BundleResponse;
import zw.co.payport.gateway.model.response.TransactionResponse;
import zw.co.payport.gateway.payments.TransactionOutcomeService;
import zw.co.payport.gateway.platform.Web;
import zw.co.payport.gateway.repository.BeneficiaryRepository;
import zw.co.payport.gateway.repository.NetworkPrefixRepository;
import zw.co.payport.gateway.repository.ProductCurrencyRepository;
import zw.co.payport.gateway.repository.ProductTypeRepository;
import zw.co.payport.gateway.repository.TransactionRepository;
import zw.co.payport.gateway.repository.UserRepository;
import zw.co.payport.gateway.rules.ChargeQuoteController;
import zw.co.payport.gateway.rules.PartyRules;
import zw.co.payport.gateway.service.AuthService;
import zw.co.payport.gateway.service.BundleService;
import zw.co.payport.gateway.service.CheckoutSessionService;
import zw.co.payport.gateway.service.Counterparties;
import zw.co.payport.gateway.service.SmsNotificationService;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The conversation engine for USSD (aggregator and the Econet, NetOne and Telecel gateways) and WhatsApp: the same
 * menus, the same services and the same configuration as the website and the app (owner, 2026-10-25).
 *
 * Every step calls the service behind the matching app/web endpoint, so all four channels behave the same:
 * products, currencies and limits from the product catalogue (/api/v1/products), payment methods from the payment
 * method table (/api/v1/products/payment-methods), bundles from BundleService (/api/v1/bundles), biller lookups
 * through BillerController.lookupAccount (/api/v1/billers/{code}/lookup), payments through the orchestrator
 * (/transactions/initiate and /pay), wallet operations through WalletService, AgentService, MerchantCodePayments
 * and CardService (/api/v1/wallet/...), saved numbers through the beneficiaries table, registration and PIN changes
 * through AuthService (/auth/register, /account/change-pin), history through the same query as /my-history.
 *
 * Guests: airtime, data, bills, merchant payments, payment status, registration and help. A number with a PayPort
 * account is greeted by name and logs in with its PIN (0 continues as a guest); then the PayPort wallet is the first
 * way to pay and the wallet, saved numbers, cards, history and PIN are offered as well.
 *
 * The whole input path is the state ("1*2*0771234567*1*5*1*1"): every hop re-reads it, so a gateway retry gets the
 * same answer, and a confirmed step runs once per session (ussd_sessions). PINs are never stored: the stored path
 * carries "#" in their place and only a hash or a "checked" flag is kept (channel_session_secrets).
 * Replies start with "CON " (continue) or "END " (finish).
 */
@Slf4j
@Service
public class UssdService {

    static final String GUEST = "0";
    static final String MASK = "#";
    static final String MORE = "0";
    static final String HELP = "Help: WhatsApp +263 719 183 557, email support@payport.africa, web www.payport.africa";
    static final String TERMS = "payport.africa/terms";

    /** Ways to pay that are not a customer's choice at checkout. */
    static final Set<String> NOT_OFFERED = Set.of("WALLET", "PAYPORT_WALLET", "WALLET_TOPUP", "AGENT");
    /** Card payments finish on a secure card page (a link by SMS on USSD, in the chat on WhatsApp). */
    static final Set<String> CARD = Set.of("ZIMSWITCH", "VISA_MC", "VISA", "MASTERCARD");
    /** Mobile money that only works on its own network's numbers. */
    static final Map<String, String> HOME_NETWORK = Map.of("ECOCASH", "ECONET", "ONEMONEY", "NETONE", "TELECASH", "TELECEL");

    private final JdbcTemplate jdbc;
    private final ChannelService channels;
    private final MarketRegistry markets;
    private final BillerRegistry billers;
    private final BillerController billerLookups;
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
    private final ProductTypeRepository products;
    private final ProductCurrencyRepository productCurrencies;
    private final MerchantCodePayments merchantCodes;
    private final ChargeQuoteController chargeQuotes;
    private final AuthService auth;
    private final BeneficiaryRepository beneficiaries;
    private final TransactionRepository transactions;
    private final Counterparties counterparties;
    private final CardService cards;
    private final PasswordEncoder encoder;
    private final SmsNotificationService sms;
    private final String consumerBase;

    public UssdService(JdbcTemplate jdbc, ChannelService channels, MarketRegistry markets, BillerRegistry billers, BillerController billerLookups,
                       NetworkPrefixRepository prefixes, TransactionOrchestratorService orchestrator, TransactionOutcomeService outcomes,
                       UserRepository users, PinVerifier pins, WalletService wallets, WalletPaymentService walletPayments, AgentService agents,
                       CheckoutSessionService checkout, BundleService bundles, ProductTypeRepository products, ProductCurrencyRepository productCurrencies,
                       MerchantCodePayments merchantCodes, ChargeQuoteController chargeQuotes, AuthService auth, BeneficiaryRepository beneficiaries,
                       TransactionRepository transactions, Counterparties counterparties, CardService cards, PasswordEncoder encoder,
                       SmsNotificationService sms, @Value("${payport.consumer.base-url:https://app.payport.africa}") String consumerBase) {
        this.jdbc = jdbc;
        this.channels = channels;
        this.markets = markets;
        this.billers = billers;
        this.billerLookups = billerLookups;
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
        this.products = products;
        this.productCurrencies = productCurrencies;
        this.merchantCodes = merchantCodes;
        this.chargeQuotes = chargeQuotes;
        this.auth = auth;
        this.beneficiaries = beneficiaries;
        this.transactions = transactions;
        this.counterparties = counterparties;
        this.cards = cards;
        this.encoder = encoder;
        this.sms = sms;
        this.consumerBase = consumerBase == null ? "" : consumerBase.replaceAll("/+$", "");
    }

    // ─── entry points ────────────────────────────────────────────────────────

    /** The reply to one hop, for gateways that send only the latest input. */
    public record Hop(String text, boolean end) {}

    /** One turn: the reply, and the path to keep for the next hop (PINs replaced by "#"). */
    public record Turn(String reply, String storedPath) {
        public boolean end() { return reply.startsWith("END"); }
        public String text() { return reply.length() >= 4 ? reply.substring(4) : reply; }
    }

    /**
     * For the network gateways (Econet, NetOne, Telecel), which send only the latest input: the path is kept here
     * (ussd_state) with every PIN replaced by "#".
     */
    public Hop converse(String network, String sessionId, String phoneNumber, String input, boolean first) {
        String key = network + ":" + sessionId;
        String msisdn = phoneNumber.replaceAll("[^0-9]", "");
        String clean = input == null ? "" : input.replace("*", "").replace("#", "").trim();
        String path;
        if (first) {
            jdbc.update("DELETE FROM ussd_state WHERE session_id = ?", key);
            jdbc.update("DELETE FROM channel_session_secrets WHERE session_id = ?", key);
            path = "";
        } else {
            List<String> rows = jdbc.queryForList("SELECT path FROM ussd_state WHERE session_id = ? AND msisdn = ?", String.class, key, msisdn);
            String before = rows.isEmpty() || rows.get(0) == null ? "" : rows.get(0);
            path = before.isEmpty() ? clean : before + "*" + clean;
        }
        if (path.length() > 480) return new Hop("Session too long. Please dial again.", true);
        Turn t = turn("USSD", key, "+" + msisdn, path);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        if (t.end()) {
            forget(key);
        } else {
            jdbc.update("INSERT INTO ussd_state (session_id, channel, msisdn, path, created_at, updated_at) VALUES (?, 'USSD', ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE path = VALUES(path), updated_at = VALUES(updated_at)", key, msisdn, t.storedPath(), now, now);
        }
        return new Hop(t.text(), t.end());
    }

    /** Forgets a finished session's login and secrets. */
    public void forget(String session) {
        jdbc.update("DELETE FROM ussd_state WHERE session_id = ?", session);
        jdbc.update("DELETE FROM channel_session_secrets WHERE session_id = ?", session);
    }

    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 600_000L)
    public void forgetOldSessions() {
        Timestamp before = Timestamp.valueOf(LocalDateTime.now().minusHours(6));
        jdbc.update("DELETE FROM ussd_state WHERE updated_at < ?", before);
        jdbc.update("DELETE FROM channel_session_secrets WHERE created_at < ?", before);
    }

    public String handle(String sessionId, String phoneNumber, String text) {
        return handle("USSD", sessionId, phoneNumber, text);
    }

    /** The same menus for every conversational channel (USSD, WHATSAPP). */
    public String handle(String channel, String sessionId, String phoneNumber, String text) {
        return turn(channel, sessionId, phoneNumber, text).reply();
    }

    public Turn turn(String channel, String sessionId, String phoneNumber, String text) {
        SECRETS.set(new HashSet<>());
        try {
            String reply = menu(channel, sessionId, phoneNumber, text);
            return new Turn(reply, mask(text, SECRETS.get()));
        } finally {
            SECRETS.remove();
        }
    }

    static String mask(String path, Set<Integer> secret) {
        if (path == null || path.isEmpty() || secret.isEmpty()) return path == null ? "" : path;
        String[] t = path.split("\\*", -1);
        for (int i : secret) if (i >= 0 && i < t.length) t[i] = MASK;
        return String.join("*", t);
    }

    private static final ThreadLocal<Set<Integer>> SECRETS = new ThreadLocal<>();

    /** One conversation: who, where, and (once the PIN is checked) whose wallet. */
    private record Ctx(String channel, String session, String msisdn, MarketRegistry.Market market, String network, User holder) {
        String phone() { return "+" + msisdn; }
        boolean wallet() { return holder != null; }
        boolean ussd() { return "USSD".equals(channel); }
        /** The channel code charges, limits and payments are recorded under (USD = USSD). */
        String rules() { return PartyRules.channel(channel); }
        int room() { return ussd() ? 175 : 1500; }
    }

    /** The input path, read one answer at a time. */
    private static final class In {
        private final String[] t;
        private int i;
        In(String[] t, int from) { this.t = t; this.i = from; }
        boolean has() { return i < t.length; }
        String next() { return t[i++].trim(); }
        /** The answer just read is a PIN: it is never stored. */
        void secret() { SECRETS.get().add(i - 1); }
        String last() {
            String v = t[t.length - 1].trim();
            i = t.length;
            return v;
        }
    }

    /** Either a prompt to show, or the answer chosen. */
    private record Step<T>(String prompt, T value) {
        static <T> Step<T> ask(String p) { return new Step<>(p, null); }
        static <T> Step<T> of(T v) { return new Step<>(null, v); }
        boolean asking() { return prompt != null; }
    }

    private String menu(String channel, String sessionId, String phoneNumber, String text) {
        if (!channels.enabled(channel)) return "END PayPort " + ("USSD".equals(channel) ? "USSD" : "on " + capital(channel)) + " is not available at the moment. Please try again later.";
        Optional<MarketRegistry.Market> market = markets.forMsisdn(phoneNumber);
        if (market.isEmpty()) return "END This number is not supported.";
        String msisdn = phoneNumber.replaceAll("[^0-9]", "");
        String[] tokens = text == null || text.isBlank() ? new String[0] : text.split("\\*", -1);
        String network = networkOf(local(msisdn, market.get()));
        try {
            Optional<User> holder = holder(msisdn);
            if (holder.isEmpty()) {
                Ctx c = new Ctx(channel, sessionId, msisdn, market.get(), network, null);
                In in = new In(tokens, 0);
                if (!in.has()) return "CON PayPort\n" + numbered(GUEST_MENU);
                return guest(c, in.next(), in);
            }
            User u = holder.get();
            if (tokens.length == 0) return "CON Welcome to PayPort, " + firstName(u) + "\nEnter your wallet PIN\n0. Continue as guest";
            In in = new In(tokens, 1);
            if (GUEST.equals(tokens[0].trim())) {
                Ctx c = new Ctx(channel, sessionId, msisdn, market.get(), network, null);
                if (!in.has()) return "CON PayPort\n" + numbered(GUEST_MENU);
                return guest(c, in.next(), in);
            }
            SECRETS.get().add(0);
            User verified = login(sessionId, u, tokens[0].trim());
            Ctx c = new Ctx(channel, sessionId, msisdn, market.get(), network, verified);
            if (!in.has()) return "CON " + firstName(u) + ", what would you like to do?\n" + numbered(WALLET_MENU);
            return wallet(c, in.next(), in);
        } catch (Web.PinRejectedException e) {
            return "END " + clip(e.getMessage(), 150);
        } catch (Exception e) {
            log.warn("[{}] session {} failed: {}", channel, sessionId, e.getMessage());
            return "END " + clip(e.getMessage() == null ? "Something went wrong. No money was taken." : e.getMessage(), 150);
        }
    }

    static final List<String> GUEST_MENU = List.of("Buy airtime", "Buy data", "Pay a bill", "Pay a merchant", "Check a payment", "Register", "Help");
    static final List<String> WALLET_MENU = List.of("Buy airtime", "Buy data", "Pay a bill", "Pay a merchant", "Send money", "My wallet",
            "Cash out at an agent", "More");
    static final List<String> WALLET_SUBMENU = List.of("Balances", "Statement", "Top up", "Convert currency", "Open a wallet");
    static final List<String> MORE_MENU = List.of("History", "Check a payment", "Saved numbers", "Cards", "Change PIN", "Help");

    private String guest(Ctx c, String choice, In in) {
        return switch (choice) {
            case "1" -> airtime(c, in);
            case "2" -> data(c, in);
            case "3" -> bill(c, in);
            case "4" -> merchant(c, in);
            case "5" -> status(c, in);
            case "6" -> register(c, in);
            case "7" -> "END " + HELP;
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
            case "6" -> walletMenu(c, in);
            case "7" -> cashOut(c, in);
            case "8" -> more(c, in);
            default -> "END Invalid choice.";
        };
    }

    private String walletMenu(Ctx c, In in) {
        if (!in.has()) return "CON My wallet\n" + numbered(WALLET_SUBMENU);
        return switch (in.next()) {
            case "1" -> balances(c);
            case "2" -> statement(c, in);
            case "3" -> topUp(c, in);
            case "4" -> convert(c, in);
            case "5" -> openWallet(c, in);
            default -> "END Invalid choice.";
        };
    }

    private String more(Ctx c, In in) {
        if (!in.has()) return "CON More\n" + numbered(MORE_MENU);
        return switch (in.next()) {
            case "1" -> history(c);
            case "2" -> status(c, in);
            case "3" -> saved(c, in);
            case "4" -> cardsMenu(c, in);
            case "5" -> changePin(c, in);
            case "6" -> "END " + HELP;
            default -> "END Invalid choice.";
        };
    }

    // ─── wallet login ────────────────────────────────────────────────────────

    private Optional<User> holder(String msisdn) {
        return users.findByPhone("+" + msisdn)
                .filter(u -> "CUSTOMER".equalsIgnoreCase(u.getRole()) && u.getPinHash() != null && Boolean.TRUE.equals(u.getIsActive()));
    }

    /** The PIN is checked once per session; later hops carry "#" (stored paths) or the same PIN (aggregator). */
    private User login(String session, User u, String pin) {
        List<Long> done = jdbc.queryForList("SELECT wallet_user_id FROM ussd_state WHERE session_id = ? AND wallet_user_id IS NOT NULL", Long.class, session);
        if (!done.isEmpty() && done.get(0) == u.getId().longValue()) return u;
        if (MASK.equals(pin)) throw new Web.PinRejectedException("Your session has expired. Please start again.");
        User v = pins.verify(u.getPhone(), pin);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO ussd_state (session_id, channel, msisdn, path, wallet_user_id, created_at, updated_at) VALUES (?, ?, ?, '', ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE wallet_user_id = VALUES(wallet_user_id), updated_at = VALUES(updated_at)",
                session, "USSD", u.getPhone().replaceAll("[^0-9]", ""), v.getId(), now, now);
        return v;
    }

    // ─── session secrets (hashes and flags only) ─────────────────────────────

    private void putSecret(Ctx c, String name, String value) {
        jdbc.update("INSERT INTO channel_session_secrets (session_id, name, value, created_at) VALUES (?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE value = VALUES(value)", c.session(), name, value, Timestamp.valueOf(LocalDateTime.now()));
    }

    private String getSecret(Ctx c, String name) {
        List<String> v = jdbc.queryForList("SELECT value FROM channel_session_secrets WHERE session_id = ? AND name = ?", String.class, c.session(), name);
        return v.isEmpty() ? null : v.get(0);
    }

    private static Web.PinRejectedException expired() {
        return new Web.PinRejectedException("Your session has expired. Please start again.");
    }

    // ─── registration (as /api/v1/auth/register) ─────────────────────────────

    private String register(Ctx c, In in) {
        if (users.findByPhone(c.phone()).isPresent()) return "END This number already has a PayPort account. Start again and enter your PIN.";
        if (!in.has()) return "CON Register with PayPort\nEnter your full name:";
        String name = in.next().replaceAll("\\s+", " ").trim();
        if (name.length() < 2 || name.length() > 200 || !name.matches("[\\p{L} .'-]+")) return "END Enter your full name in letters only.";
        if (!in.has()) return "CON Enter your national ID number:";
        String id = in.next().trim();
        if (!id.matches("^[A-Za-z0-9][A-Za-z0-9 /-]{3,39}$")) return "END Enter a valid ID number.";
        if (!in.has()) return "CON By registering you accept the PayPort Terms and Privacy Policy (" + TERMS + ").\n1. Accept\n2. Cancel";
        if (!"1".equals(in.next())) return "END Cancelled. Nothing was saved.";
        if (!in.has()) return "CON Choose a 4-digit PIN:";
        String first = in.next();
        in.secret();
        String hash;
        if (MASK.equals(first)) {
            hash = getSecret(c, "register_pin");
            if (hash == null) throw expired();
        } else {
            if (!first.matches("\\d{4}")) return "END Your PIN must be exactly 4 digits.";
            hash = encoder.encode(first);
            putSecret(c, "register_pin", hash);
        }
        if (!in.has()) return "CON Enter the PIN again:";
        String again = in.next();
        in.secret();
        if (MASK.equals(again)) throw expired();
        if (!encoder.matches(again, hash)) return "END The PINs do not match. Nothing was saved.";
        final String pin = again;
        return once(c, () -> {
            try {
                AuthRequests.RegisterRequest r = new AuthRequests.RegisterRequest();
                r.setFullName(name);
                r.setPhone(c.phone());
                r.setPin(pin);
                r.setIdNumber(id);
                auth.register(r);
                return "END Welcome to PayPort, " + clip(name.split(" ")[0], 20) + ". Start again and enter your PIN to open your wallet and use it.";
            } catch (RuntimeException e) {
                return "END " + clip(e.getMessage() == null ? "Registration failed. Please try again." : e.getMessage(), 150);
            }
        });
    }

    // ─── airtime (product AIRTIME_<network>) ─────────────────────────────────

    private String airtime(Ctx c, In in) {
        if (!in.has()) return "CON Airtime for:\n" + recipientMenu(c);
        Step<String> who = recipient(c, in);
        if (who.asking()) return who.prompt();
        String recipient = who.value();
        String network = networkOf(local(recipient, c.market()));
        if (network == null) return "END That network is not supported for airtime.";
        String product = "AIRTIME_" + network;
        List<String> ccys = currencies(c, product);
        if (!in.has()) return "CON Currency:\n" + numbered(ccys);
        String ccy = pick(ccys, in.next());
        if (!in.has()) return "CON Amount (" + ccy + "):";
        long amount = minor(in.next());
        String limit = limits(product, ccy, amount);
        if (limit != null) return "END " + limit;
        return pay(c, in, product, "+" + recipient, amount, ccy, "Buy " + ccy + " " + major(amount) + " airtime for " + local(recipient, c.market()), "airtime", true);
    }

    private String recipientMenu(Ctx c) {
        return "1. My number\n2. Another number" + (savedPhones(c).isEmpty() ? "" : "\n3. Saved number");
    }

    /** "1. My number / 2. Another number / 3. Saved number": the recipient's digits, or the next prompt. */
    private Step<String> recipient(Ctx c, In in) {
        String who = in.next();
        if ("1".equals(who)) return Step.of(c.msisdn());
        if ("3".equals(who) && !savedPhones(c).isEmpty()) {
            Step<Beneficiary> b = choose(c, in, "Saved numbers:", savedPhones(c), x -> clip(x.getName(), 18) + " " + x.getPhone());
            if (b.asking()) return Step.ask(b.prompt());
            return Step.of(phone(b.value().getPhone(), c.market()));
        }
        if (!"2".equals(who)) throw new IllegalArgumentException("Invalid choice.");
        if (!in.has()) return Step.ask("CON Enter the phone number:");
        String n = phone(in.next(), c.market());
        if (markets.forMsisdn("+" + n).isEmpty()) throw new IllegalArgumentException("Invalid phone number.");
        return Step.of(n);
    }

    // ─── data (product DATA_<network>, bundles as /api/v1/bundles) ───────────

    private String data(Ctx c, In in) {
        if (!in.has()) return "CON Data for:\n" + recipientMenu(c);
        Step<String> who = recipient(c, in);
        if (who.asking()) return who.prompt();
        String recipient = who.value();
        String network = networkOf(local(recipient, c.market()));
        if (network == null) return "END That network is not supported for data.";
        String product = "DATA_" + network;
        List<String> ccys = currencies(c, product);
        if (!in.has()) return "CON Currency:\n" + numbered(ccys);
        String ccy = pick(ccys, in.next());
        List<BundleResponse> list = new ArrayList<>();
        for (BundleResponse b : bundles.getActiveBundles(network, ccy, null)) if (b.getPriceValue() != null && b.getPriceValue() > 0) list.add(b);
        if (list.isEmpty()) return "END No " + capital(network) + " data bundles in " + ccy + " at the moment.";
        Step<BundleResponse> pickB = choose(c, in, "Choose a bundle (" + ccy + "):", list, b -> clip(b.getBundleName(), 22) + " " + major(cents(b.getPriceValue())));
        if (pickB.asking()) return pickB.prompt();
        BundleResponse b = pickB.value();
        long amount = cents(b.getPriceValue());
        return pay(c, in, product, "+" + recipient, amount, ccy,
                "Buy " + clip(b.getBundleName(), 30) + " (" + ccy + " " + major(amount) + ") for " + local(recipient, c.market()), clip(b.getBundleName(), 30), true,
                b.getBundleCode() == null ? null : Map.of("bundleCode", b.getBundleCode()));
    }

    // ─── bills (active BILLS products, lookup as /api/v1/billers/{code}/lookup) ─

    private List<ProductType> billProducts() {
        return products.findByIsActiveTrueOrderByCategory().stream().filter(p -> "BILLS".equalsIgnoreCase(p.getCategory())).toList();
    }

    private String accountLabel(ProductType p) {
        return billers.find(p.getProviderCode()).map(b -> b.profile().accountLabel()).orElse("Account number");
    }

    private String bill(Ctx c, In in) {
        List<ProductType> list = billProducts();
        if (list.isEmpty()) return "END No bills can be paid here at the moment.";
        Step<ProductType> pp = choose(c, in, "Pay which bill?", list, ProductType::getName);
        if (pp.asking()) return pp.prompt();
        ProductType p = pp.value();
        String label = accountLabel(p);
        List<Beneficiary> saved = savedAccounts(c, p.getProviderCode());
        String account;
        if (!saved.isEmpty()) {
            if (!in.has()) return "CON " + label + ":\n1. Enter a new one\n2. Saved account";
            String how = in.next();
            if ("2".equals(how)) {
                Step<Beneficiary> b = choose(c, in, "Saved accounts:", saved, x -> clip(x.getName(), 18) + " " + x.getAccountRef());
                if (b.asking()) return b.prompt();
                account = b.value().getAccountRef();
            } else if ("1".equals(how)) {
                if (!in.has()) return "CON " + label + ":";
                account = in.next();
            } else return "END Invalid choice.";
        } else {
            if (!in.has()) return "CON " + label + ":";
            account = in.next();
        }
        account = account.trim().replaceAll("\\s+", "");
        Optional<BillerAdaptor> biller = billers.find(p.getProviderCode());
        List<String> ccys = new ArrayList<>(currencies(c, p.getCode()));
        if (biller.isPresent()) ccys.retainAll(biller.get().profile().currencies());
        if (ccys.isEmpty()) return "END " + p.getName() + " cannot be paid in this country's currencies.";
        String holderName = null;
        if (biller.isPresent()) {
            BillerAdaptor.Account a;
            try {
                a = billerLookups.lookupAccount(p.getProviderCode(), account, ccys.get(0), c.phone());
            } catch (IllegalArgumentException e) {
                return "END " + clip(e.getMessage(), 150);
            } catch (RuntimeException e) {
                return "END " + clip(e.getMessage() == null ? p.getName() + " is not responding. Please try again later. No money was taken." : e.getMessage(), 150);
            }
            if (!a.payable()) return "END " + clip(a.notPayableReason() == null ? "This account cannot be paid." : a.notPayableReason(), 150);
            if (a.acceptedCurrencies() != null && !a.acceptedCurrencies().isEmpty()) ccys.retainAll(a.acceptedCurrencies());
            if (ccys.isEmpty()) return "END This account cannot be paid in the currencies offered.";
            holderName = a.holderName();
        }
        if (!in.has()) return "CON " + clip(holderName == null ? account : holderName, 40) + "\nCurrency:\n" + numbered(ccys);
        String ccy = pick(ccys, in.next());
        if (!in.has()) return "CON Amount (" + ccy + "):";
        long amount = minor(in.next());
        String limit = biller.map(b -> b.validateAmount(amount, ccy)).orElse(null);
        if (limit == null) limit = limits(p.getCode(), ccy, amount);
        if (limit != null) return "END " + clip(limit, 150);
        return pay(c, in, p.getCode(), account, amount, ccy, "Pay " + ccy + " " + major(amount) + " to " + p.getName() + " " + account, p.getName(), true);
    }

    // ─── merchant (six-digit merchant code, as /api/v1/wallet/pay-merchant) ──

    private String merchant(Ctx c, In in) {
        if (!in.has()) return "CON Enter the merchant code:";
        String code = in.next().replaceAll("[^0-9]", "");
        Map<String, Object> m;
        try {
            m = merchantCodes.lookup(code);
        } catch (RuntimeException e) {
            return "END " + clip(e.getMessage() == null ? "No merchant with that code." : e.getMessage(), 150);
        }
        String name = clip(String.valueOf(m.get("name")), 30);
        if (!in.has()) return "CON Pay " + name + (m.get("address") == null ? "" : ", " + clip(String.valueOf(m.get("address")), 40)) + "?\n1. Yes\n2. No";
        if (!"1".equals(in.next())) return "END Cancelled. No money was taken.";
        @SuppressWarnings("unchecked") List<String> ccys = (List<String>) m.get("currencies");
        if (ccys == null || ccys.isEmpty()) return "END " + name + " cannot take payments at the moment.";
        if (!in.has()) return "CON Currency:\n" + numbered(ccys);
        String ccy = pick(ccys, in.next());
        if (!in.has()) return "CON Amount (" + ccy + "):";
        long amount = minor(in.next());
        return payMerchant(c, in, code, name, amount, ccy);
    }

    private String payMerchant(Ctx c, In in, String code, String merchant, long amount, String ccy) {
        List<String> methods = methods(c.wallet());
        if (!in.has()) return "CON Pay " + merchant + " " + ccy + " " + major(amount) + " with:\n" + numbered(methods.stream().map(this::methodName).toList());
        String method = pick(methods, in.next());
        String payer = c.phone();
        if (needsOwnNumber(method, c.network())) {
            if (!in.has()) return "CON Enter your " + methodName(method) + " number:";
            payer = "+" + phone(in.next(), c.market());
        }
        long due = WalletService.RAIL.equals(method) ? withCharge(c, List.of("MERCHANT_PAYMENT"), ccy, amount) : amount;
        if (!in.has()) return "CON Pay " + merchant + " " + ccy + " " + major(due) + " with " + methodName(method) + "?\n1. Confirm\n2. Cancel";
        if (!"1".equals(in.next())) return "END Cancelled. No money was taken.";
        if (in.has()) return omari(c, in.last());
        final String from = payer;
        return once(c, () -> {
            try {
                boolean wallet = WalletService.RAIL.equals(method);
                var s = merchantCodes.start(code, amount, ccy, c.rules(), wallet ? c.holder().getPhone() : from, null);
                if (wallet) {
                    Map<String, Object> r = walletPayments.payCheckoutAs(c.holder(), s.getId(), c.channel().toLowerCase(Locale.ROOT), c.rules());
                    record(c, (String) r.get("reference"));
                    return walletResult(r, merchant);
                }
                CheckoutSessionService.PaymentSubmissionResult r = checkout.submitPayment(s.getId(), from, null, method, c.channel().toLowerCase(Locale.ROOT));
                String ref = r.session().getTransactionReference();
                record(c, ref);
                if (CARD.contains(method) || r.widgetJsUrl() != null) return cardLink(c, ref, ccy, amount, from);
                return afterSubmit(ref, method, from, ccy, amount, merchant, null);
            } catch (Exception e) {
                return "END " + clip(e.getMessage() == null ? "Payment could not be started. No money was taken." : e.getMessage(), 150);
            }
        });
    }

    private long withCharge(Ctx c, List<String> products, String ccy, long amount) {
        return amount + wallets.quoteCharge(products, ccy, c.rules(), amount).total();
    }

    /** What the customer pays for a wallet operation (the charge is never shown on its own). */
    private String totalNote(Ctx c, List<String> products, String ccy, long amount) {
        long total = withCharge(c, products, ccy, amount);
        return total == amount ? "" : ". You pay " + ccy + " " + major(total);
    }

    // ─── send money (as /api/v1/wallet/transfers) ────────────────────────────

    private String send(Ctx c, In in) {
        wallets.requireOn();
        List<Beneficiary> saved = savedPhones(c);
        String to;
        if (!saved.isEmpty()) {
            if (!in.has()) return "CON Send to:\n1. A phone or wallet number\n2. Saved number";
            String how = in.next();
            if ("2".equals(how)) {
                Step<Beneficiary> b = choose(c, in, "Saved numbers:", saved, x -> clip(x.getName(), 18) + " " + x.getPhone());
                if (b.asking()) return b.prompt();
                to = "+" + phone(b.value().getPhone(), c.market());
            } else if ("1".equals(how)) {
                if (!in.has()) return "CON Send to (phone or wallet number):";
                to = in.next();
            } else return "END Invalid choice.";
        } else {
            if (!in.has()) return "CON Send to (phone or wallet number):";
            to = in.next();
        }
        if (!to.toUpperCase(Locale.ROOT).startsWith("W") && !to.startsWith("+")) to = "+" + phone(to, c.market());
        List<WalletService.Wallet> mine = mine(c);
        Step<WalletService.Wallet> pw = choose(c, in, "From which wallet?", mine, w -> w.currency() + " " + major(wallets.balance(w)));
        if (pw.asking()) return pw.prompt();
        WalletService.Wallet w = pw.value();
        if (!in.has()) return "CON Amount (" + w.currency() + "):";
        long amount = minor(in.next());
        if (!in.has()) return "CON Send " + w.currency() + " " + major(amount) + " to " + to + totalNote(c, List.of("P2P_TRANSFER"), w.currency(), amount) + "?\n1. Confirm\n2. Cancel";
        if (!"1".equals(in.next())) return "END Cancelled. No money was sent.";
        final String recipient = to;
        return once(c, () -> {
            try {
                Map<String, Object> r = wallets.transfer(c.holder().getId(), recipient, w.currency(), amount, c.channel().toLowerCase(Locale.ROOT) + ":" + c.session(),
                        "customer:" + c.holder().getPhone(), c.rules());
                return "END Sent " + w.currency() + " " + major(amount) + " to " + r.get("to") + ". Balance " + w.currency() + " " + major(((Number) r.get("balanceMinor")).longValue()) + ".";
            } catch (RuntimeException e) {
                return "END " + clip(e.getMessage() == null ? "The transfer could not be made." : e.getMessage(), 150);
            }
        });
    }

    // ─── my wallet ───────────────────────────────────────────────────────────

    private String balances(Ctx c) {
        wallets.requireOn();
        List<WalletService.Wallet> mine = held(c);
        if (mine.isEmpty()) return "END You have no PayPort wallet yet. Choose My wallet > Open a wallet.";
        StringBuilder sb = new StringBuilder("END ");
        for (WalletService.Wallet w : mine) sb.append(w.currency()).append(" ").append(major(wallets.balance(w))).append(" (").append(w.ref()).append(")\n");
        return fit(c, sb.toString().trim());
    }

    private String statement(Ctx c, In in) {
        List<WalletService.Wallet> mine = mine(c);
        Step<WalletService.Wallet> pw = choose(c, in, "Statement for:", mine, w -> w.currency() + " " + w.ref());
        if (pw.asking()) return pw.prompt();
        WalletService.Wallet w = pw.value();
        List<Map<String, Object>> lines = wallets.statement(w, c.ussd() ? 5 : 15);
        if (lines.isEmpty()) return "END No transactions on your " + w.currency() + " wallet yet.";
        StringBuilder sb = new StringBuilder("END " + w.currency() + " " + major(wallets.balance(w)) + "\n");
        for (Map<String, Object> l : lines) {
            long a = ((Number) l.get("amount_minor")).longValue();
            String who = l.get("to_name") == null ? String.valueOf(l.get("kind")).replace('_', ' ').toLowerCase(Locale.ROOT) : String.valueOf(l.get("to_name"));
            sb.append("DR".equals(l.get("direction")) ? "-" : "+").append(major(a)).append(" ").append(clip(who, c.ussd() ? 16 : 40)).append("\n");
        }
        return fit(c, sb.toString().trim());
    }

    private String topUp(Ctx c, In in) {
        List<WalletService.Wallet> mine = mine(c);
        Step<WalletService.Wallet> pw = choose(c, in, "Top up which wallet?", mine, w -> w.currency() + " " + w.ref());
        if (pw.asking()) return pw.prompt();
        WalletService.Wallet w = pw.value();
        if (!in.has()) return "CON Amount (" + w.currency() + "):";
        long amount = minor(in.next());
        return pay(c, in, "WALLET_TOPUP", w.ref(), amount, w.currency(), "Top up " + w.currency() + " " + major(amount) + " to your wallet", "wallet top-up", false);
    }

    /** As /api/v1/wallet/fx/quotes and /conversions: the rate shown is the rate used (the quote is kept for the session). */
    private String convert(Ctx c, In in) {
        List<WalletService.Wallet> mine = mine(c);
        if (mine.size() < 2) return "END Open a wallet in another currency first (My wallet > Open a wallet).";
        Step<WalletService.Wallet> pf = choose(c, in, "Convert from:", mine, w -> w.currency() + " " + major(wallets.balance(w)));
        if (pf.asking()) return pf.prompt();
        WalletService.Wallet from = pf.value();
        List<WalletService.Wallet> others = mine.stream().filter(w -> !w.currency().equals(from.currency())).toList();
        Step<WalletService.Wallet> pt = choose(c, in, "Convert to:", others, w -> w.currency());
        if (pt.asking()) return pt.prompt();
        WalletService.Wallet to = pt.value();
        if (!in.has()) return "CON Amount (" + from.currency() + "):";
        long amount = minor(in.next());
        if (!in.has()) {
            Map<String, Object> q = wallets.quoteConversion(c.holder().getId(), from.currency(), to.currency(), amount);
            putSecret(c, "fx_quote", String.valueOf(q.get("quoteRef")));
            long converted = ((Number) q.get("convertedMinor")).longValue();
            return "CON Convert " + from.currency() + " " + major(amount) + " to " + to.currency() + " " + major(converted) + "?\n1. Confirm\n2. Cancel";
        }
        if (!"1".equals(in.next())) return "END Cancelled.";
        String quote = getSecret(c, "fx_quote");
        if (quote == null) throw expired();
        return once(c, () -> {
            try {
                Map<String, Object> r = wallets.convert(c.holder().getId(), quote, "customer:" + c.holder().getPhone(), c.rules());
                Object credited = r.get("creditedMinor");
                return "END Converted " + from.currency() + " " + major(amount) + (credited == null ? "" : " to " + to.currency() + " " + major(((Number) credited).longValue())) + ".";
            } catch (RuntimeException e) {
                return "END " + clip(e.getMessage() == null ? "The conversion could not be made." : e.getMessage(), 150);
            }
        });
    }

    private String openWallet(Ctx c, In in) {
        wallets.requireOn();
        List<String> have = held(c).stream().map(WalletService.Wallet::currency).toList();
        List<String> can = new ArrayList<>(liveCurrencies(c.market()));
        can.removeAll(have);
        if (can.isEmpty()) return "END You already have a wallet in every currency.";
        if (!in.has()) return "CON Open a wallet in:\n" + numbered(can);
        String ccy = pick(can, in.next());
        return once(c, () -> {
            try {
                WalletService.Wallet w = wallets.open(c.holder().getId(), c.holder().getPhone(), ccy, "customer:" + c.holder().getPhone());
                return "END Your " + w.currency() + " wallet " + w.ref() + " is ready.";
            } catch (RuntimeException e) {
                return "END " + clip(e.getMessage() == null ? "The wallet could not be opened." : e.getMessage(), 150);
            }
        });
    }

    /** Cash-out at an agent (as /api/v1/wallet/cash-out): the agent's name is shown before the amount. */
    private String cashOut(Ctx c, In in) {
        if (!in.has()) return "CON Enter the agent code:";
        String code = in.next().replaceAll("[^0-9]", "");
        Map<String, Object> a;
        try {
            a = agents.lookup(code);
        } catch (RuntimeException e) {
            return "END " + clip(e.getMessage() == null ? "No agent with that code." : e.getMessage(), 150);
        }
        String name = clip(String.valueOf(a.get("name")), 30);
        if (!in.has()) return "CON Agent: " + name + (a.get("location") == null ? "" : ", " + clip(String.valueOf(a.get("location")), 40)) + "\nIs this correct?\n1. Yes\n2. No";
        if (!"1".equals(in.next())) return "END Cancelled. Check the agent code and try again.";
        List<WalletService.Wallet> mine = mine(c);
        Step<WalletService.Wallet> pw = choose(c, in, "Cash out from which wallet?", mine, w -> w.currency() + " " + major(wallets.balance(w)));
        if (pw.asking()) return pw.prompt();
        WalletService.Wallet w = pw.value();
        if (!in.has()) return "CON Amount (" + w.currency() + "):";
        long amount = minor(in.next());
        if (!in.has()) return "CON Cash out " + w.currency() + " " + major(amount) + " at " + name + totalNote(c, List.of("CASH_OUT"), w.currency(), amount) + "?\n1. Confirm\n2. Cancel";
        if (!"1".equals(in.next())) return "END Cancelled.";
        return once(c, () -> {
            try {
                Map<String, Object> r = agents.customerCashOut(c.holder(), code, w.currency(), amount, c.channel().toLowerCase(Locale.ROOT) + ":" + c.session(), c.rules());
                return "END Done. Collect " + w.currency() + " " + major(amount) + " cash from " + name + ". Balance " + w.currency() + " "
                        + major(((Number) r.get("balanceMinor")).longValue()) + ". Ref " + r.get("opRef");
            } catch (RuntimeException e) {
                return "END " + clip(e.getMessage() == null ? "The cash-out could not be made." : e.getMessage(), 150);
            }
        });
    }

    private List<WalletService.Wallet> held(Ctx c) {
        return wallets.forUser(c.holder().getId()).stream().filter(w -> !"CLOSED".equals(w.status())).toList();
    }

    private List<WalletService.Wallet> mine(Ctx c) {
        wallets.requireOn();
        List<WalletService.Wallet> mine = held(c);
        if (mine.isEmpty()) throw new IllegalArgumentException("You have no PayPort wallet yet. Choose My wallet > Open a wallet.");
        return mine;
    }

    // ─── history (as /api/v1/transactions/my-history) ────────────────────────

    private String history(Ctx c) {
        List<Transaction> list = transactions.findByCustomerPhone(c.holder().getPhone(), PageRequest.of(0, c.ussd() ? 4 : 10, Sort.by("createdAt").descending())).getContent();
        if (list.isEmpty()) return "END No payments yet.";
        DateTimeFormatter f = DateTimeFormatter.ofPattern("dd/MM");
        StringBuilder sb = new StringBuilder("END ");
        for (Transaction t : list) {
            String what = t.getProductType() == null ? "Payment" : t.getProductType().getName();
            String to = counterparties.forTransaction(t);
            sb.append(t.getCreatedAt() == null ? "" : t.getCreatedAt().format(f) + " ")
                    .append(clip(what, c.ussd() ? 14 : 30)).append(to == null || c.ussd() ? "" : " to " + to).append(" ")
                    .append(t.getCurrency()).append(" ").append(major(t.getAmountCents() == null ? 0 : t.getAmountCents())).append(" ")
                    .append(outcomes.of(t).outcome().name().replace('_', ' ').toLowerCase(Locale.ROOT)).append("\n");
        }
        return fit(c, sb.toString().trim());
    }

    // ─── saved numbers and accounts (as /api/v1/beneficiaries) ───────────────

    private List<Beneficiary> savedAll(Ctx c) {
        return c.wallet() ? beneficiaries.findByUserPhoneOrderByCreatedAtDesc(c.holder().getPhone()) : List.of();
    }

    private List<Beneficiary> savedPhones(Ctx c) {
        return savedAll(c).stream().filter(b -> "PHONE".equals(b.getType()) && b.getPhone() != null && !b.getPhone().isBlank()).toList();
    }

    private List<Beneficiary> savedAccounts(Ctx c, String billerCode) {
        return savedAll(c).stream().filter(b -> "BILLS".equals(b.getType()) && billerCode != null && billerCode.equalsIgnoreCase(b.getBillerCode())
                && b.getAccountRef() != null && !b.getAccountRef().isBlank()).toList();
    }

    private String saved(Ctx c, In in) {
        if (!in.has()) return "CON Saved numbers\n1. View\n2. Add a phone number\n3. Add a bill account\n4. Remove";
        switch (in.next()) {
            case "1": {
                List<Beneficiary> all = savedAll(c);
                if (all.isEmpty()) return "END Nothing saved yet.";
                StringBuilder sb = new StringBuilder("END ");
                for (Beneficiary b : all) sb.append(clip(b.getName(), 18)).append(" ").append("BILLS".equals(b.getType())
                        ? clip(b.getBillerName(), 14) + " " + b.getAccountRef() : b.getPhone()).append("\n");
                return fit(c, sb.toString().trim());
            }
            case "2": {
                if (!in.has()) return "CON Name:";
                String name = clip(in.next().trim(), 100);
                if (name.isBlank()) return "END Enter a name.";
                if (!in.has()) return "CON Phone number:";
                String n = phone(in.next(), c.market());
                String network = networkOf(local(n, c.market()));
                return once(c, () -> {
                    beneficiaries.save(Beneficiary.builder().userPhone(c.holder().getPhone()).type("PHONE").name(name).phone("+" + n)
                            .networkName(network == null ? null : capital(network)).build());
                    return "END Saved " + name + " (" + local(n, c.market()) + ").";
                });
            }
            case "3": {
                List<ProductType> list = billProducts();
                if (list.isEmpty()) return "END No bills can be paid here at the moment.";
                Step<ProductType> pp = choose(c, in, "Which bill?", list, ProductType::getName);
                if (pp.asking()) return pp.prompt();
                ProductType p = pp.value();
                if (!in.has()) return "CON " + accountLabel(p) + ":";
                String account = in.next().trim().replaceAll("\\s+", "");
                String invalid = billers.find(p.getProviderCode()).map(b -> b.validateAccount(account)).orElse(account.isEmpty() ? "Enter the account number" : null);
                if (invalid != null) return "END " + clip(invalid, 150);
                if (!in.has()) return "CON Name for this account:";
                String name = clip(in.next().trim(), 100);
                if (name.isBlank()) return "END Enter a name.";
                return once(c, () -> {
                    beneficiaries.save(Beneficiary.builder().userPhone(c.holder().getPhone()).type("BILLS").name(name)
                            .billerCode(p.getProviderCode()).billerName(p.getName()).accountRef(account).build());
                    return "END Saved " + name + " (" + p.getName() + " " + account + ").";
                });
            }
            case "4": {
                List<Beneficiary> all = savedAll(c);
                if (all.isEmpty()) return "END Nothing saved yet.";
                Step<Beneficiary> b = choose(c, in, "Remove which?", all, x -> clip(x.getName(), 18) + " " + ("BILLS".equals(x.getType()) ? x.getAccountRef() : x.getPhone()));
                if (b.asking()) return b.prompt();
                Beneficiary x = b.value();
                if (!in.has()) return "CON Are you sure?\n1. Yes\n2. No";
                if (!"1".equals(in.next())) return "END Cancelled.";
                return once(c, () -> {
                    beneficiaries.findByIdAndUserPhone(x.getId(), c.holder().getPhone()).ifPresent(beneficiaries::delete);
                    return "END Removed " + x.getName() + ".";
                });
            }
            default:
                return "END Invalid choice.";
        }
    }

    // ─── cards (as /api/v1/wallet/cards) ─────────────────────────────────────

    private static final Map<String, Object> NEW_CARD = Map.of("new", true);

    private String cardLabel(Map<String, Object> k) {
        String scheme = "ZIMSWITCH".equals(k.get("scheme")) ? "ZimSwitch" : String.valueOf(k.get("scheme"));
        String form = String.valueOf(k.get("form")).toLowerCase(Locale.ROOT);
        String last = k.get("last4") == null ? "" : " *" + k.get("last4");
        return scheme + " " + form + last + " " + String.valueOf(k.get("status")).toLowerCase(Locale.ROOT);
    }

    private String cardsMenu(Ctx c, In in) {
        wallets.requireOn();
        List<Map<String, Object>> mine = new ArrayList<>(cards.forUser(c.holder().getId()).stream()
                .filter(k -> !"CANCELLED".equals(k.get("status")) && !"REFUSED".equals(k.get("status"))).toList());
        mine.add(NEW_CARD);
        Step<Map<String, Object>> pk = choose(c, in, "Cards", mine, k -> k == NEW_CARD ? "Request a card" : cardLabel(k));
        if (pk.asking()) return pk.prompt();
        Map<String, Object> k = pk.value();
        if (k == NEW_CARD) return requestCard(c, in);
        String status = String.valueOf(k.get("status"));
        List<String> actions = new ArrayList<>();
        if ("ACTIVE".equals(status)) actions.add("FREEZE");
        if ("FROZEN".equals(status)) actions.add("UNFREEZE");
        actions.add("CANCEL");
        if (!in.has()) return "CON " + cardLabel(k) + "\n" + numbered(actions.stream().map(a -> switch (a) {
            case "FREEZE" -> "Block card"; case "UNFREEZE" -> "Unblock card"; default -> "Cancel card"; }).toList());
        String action = pick(actions, in.next());
        if (!in.has()) return "CON Are you sure?\n1. Yes\n2. No";
        if (!"1".equals(in.next())) return "END Cancelled.";
        return once(c, () -> {
            try {
                Map<String, Object> r = cards.customerAction(c.holder().getId(), String.valueOf(k.get("cardRef")), action, c.holder().getPhone());
                return "END Your card is now " + String.valueOf(r.get("status")).toLowerCase(Locale.ROOT) + ".";
            } catch (RuntimeException e) {
                return "END " + clip(e.getMessage() == null ? "The card could not be changed." : e.getMessage(), 150);
            }
        });
    }

    /** VISA links to the USD wallet only; ZimSwitch to the USD and ZWG wallets. */
    private String requestCard(Ctx c, In in) {
        if (!in.has()) return "CON Card:\n1. VISA (USD wallet)\n2. ZimSwitch (USD and ZWG wallets)";
        String scheme = pick(List.of("VISA", "ZIMSWITCH"), in.next());
        if (!in.has()) return "CON Type:\n1. Virtual\n2. Physical";
        String form = pick(List.of("VIRTUAL", "PHYSICAL"), in.next());
        String address = null;
        if ("PHYSICAL".equals(form)) {
            if (!in.has()) return "CON Delivery address:";
            address = in.next().trim();
            if (address.length() < 5) return "END Enter the delivery address.";
        }
        String label = ("ZIMSWITCH".equals(scheme) ? "ZimSwitch" : "VISA") + " " + form.toLowerCase(Locale.ROOT) + " card";
        if (!in.has()) return "CON Request a " + label + " in the name " + clip(c.holder().getFullName(), 26) + "?\n1. Confirm\n2. Cancel";
        if (!"1".equals(in.next())) return "END Cancelled.";
        final String deliver = address;
        return once(c, () -> {
            try {
                Map<String, Object> r = cards.request(c.holder().getId(), null, null, scheme, form, c.holder().getFullName(), null, deliver, c.holder().getPhone());
                return "END Your " + label + " has been requested (" + r.get("cardRef") + "). Status: " + String.valueOf(r.get("status")).toLowerCase(Locale.ROOT) + ".";
            } catch (RuntimeException e) {
                return "END " + clip(e.getMessage() == null ? "The card could not be requested." : e.getMessage(), 150);
            }
        });
    }

    // ─── change PIN (as /api/v1/account/change-pin) ──────────────────────────

    private String changePin(Ctx c, In in) {
        if (!in.has()) return "CON Enter your current PIN:";
        String current = in.next();
        in.secret();
        if (MASK.equals(current)) {
            if (getSecret(c, "pin_checked") == null) throw expired();
        } else {
            pins.verify(c.holder().getPhone(), current);
            putSecret(c, "pin_checked", "1");
        }
        if (!in.has()) return "CON Enter your new 4-digit PIN:";
        String first = in.next();
        in.secret();
        String hash;
        if (MASK.equals(first)) {
            hash = getSecret(c, "new_pin");
            if (hash == null) throw expired();
        } else {
            if (!first.matches("\\d{4}")) return "END Your PIN must be exactly 4 digits.";
            hash = encoder.encode(first);
            putSecret(c, "new_pin", hash);
        }
        if (!in.has()) return "CON Enter the new PIN again:";
        String again = in.next();
        in.secret();
        if (MASK.equals(again)) throw expired();
        if (!encoder.matches(again, hash)) return "END The PINs do not match. Your PIN was not changed.";
        return once(c, () -> {
            try {
                auth.setCustomerPin(c.holder().getPhone(), again);
                return "END Your PIN has been changed.";
            } catch (RuntimeException e) {
                return "END " + clip(e.getMessage() == null ? "Your PIN could not be changed." : e.getMessage(), 150);
            }
        });
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

    // ─── paying (as /transactions/initiate then /pay, or /wallet/pay) ────────

    private String pay(Ctx c, In in, String product, String recipient, long amount, String ccy, String summary, String what, boolean walletAllowed) {
        return pay(c, in, product, recipient, amount, ccy, summary, what, walletAllowed, null);
    }

    /** Method, payer number (when the method needs one on another network), confirmation, then payment. */
    private String pay(Ctx c, In in, String product, String recipient, long amount, String ccy, String summary, String what, boolean walletAllowed, Map<String, Object> productConfig) {
        List<String> methods = methods(c.wallet() && walletAllowed);
        if (!in.has()) return "CON Pay with:\n" + numbered(methods.stream().map(this::methodName).toList());
        String method = pick(methods, in.next());
        String payer = c.phone();
        if (needsOwnNumber(method, c.network())) {
            if (!in.has()) return "CON Enter your " + methodName(method) + " number:";
            payer = "+" + phone(in.next(), c.market());
        }
        if (!in.has()) {
            var ch = chargeQuotes == null ? PartyRules.Charge.NONE : chargeQuotes.quote(product, ccy, amount, c.channel());
            // The customer sees only what they pay: the total replaces the face value in the summary.
            String shown = ch.total() > 0 ? summary.replace(ccy + " " + major(amount), ccy + " " + major(amount + ch.total())) : summary;
            return "CON " + shown + " with " + methodName(method) + "?\n1. Confirm\n2. Cancel";
        }
        if (!"1".equals(in.next())) return "END Cancelled. No money was taken.";
        if (in.has()) return omari(c, in.last());
        final String from = payer;
        return once(c, () -> start(c, product, recipient, amount, ccy, method, from, what, productConfig));
    }

    private String start(Ctx c, String product, String recipient, long amount, String ccy, String method, String payer, String what, Map<String, Object> productConfig) {
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
            if (productConfig != null) init.setProductConfig(productConfig);
            orchestrator.initiateTransaction(init, channel.toLowerCase(Locale.ROOT));
            if (WalletService.RAIL.equals(method)) return walletResult(walletPayments.payTransactionAs(c.holder(), ref), what);
            PayTransactionRequest pay = new PayTransactionRequest();
            pay.setReference(ref);
            pay.setPaymentMethodCode(method);
            pay.setCustomerPhone(payer);
            TransactionResponse r = orchestrator.submitPayment(pay);
            if (r.getStatus() == TransactionStatus.PAYMENT_FAILED) {
                return "END " + clip(r.getFailureReason() == null ? "The payment was declined. No money was taken." : r.getFailureReason(), 130) + " Ref " + ref;
            }
            long total = r.getTotalCents() != null ? r.getTotalCents() : amount;
            if (CARD.contains(method) || r.getWidgetJsUrl() != null) return cardLink(c, ref, ccy, total, payer);
            return afterSubmit(ref, method, payer, ccy, total, what, r.getPaymentCode());
        } catch (Exception e) {
            return "END " + clip(e.getMessage() == null ? "Payment could not be started. No money was taken." : e.getMessage(), 140) + " Ref " + ref;
        }
    }

    /** A card payment is finished on the secure card page: the link in the chat (WhatsApp) or by SMS (USSD). */
    private String cardLink(Ctx c, String ref, String ccy, long amount, String payer) {
        String link = consumerBase + "/pay/zimswitch/" + ref;
        if (c.ussd()) {
            sms.sendText(c.phone(), "PayPort: pay " + ccy + " " + major(amount) + " by card here: " + link + " Ref: " + ref, ref, "CARD_LINK");
            return "END We have sent you an SMS with a link to pay " + ccy + " " + major(amount) + " by card. Ref " + ref;
        }
        return "END Pay " + ccy + " " + major(amount) + " by card here:\n" + link + "\nRef " + ref;
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
                + ". You will get a message when it is done. Ref " + ref;
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
        if (refs.isEmpty() || refs.get(0) == null) return "END Nothing to confirm. Please start again.";
        String ref = refs.get(0);
        try {
            TransactionResponse r = orchestrator.confirmOmariOtp(ref, otp);
            if (r.getStatus() == TransactionStatus.PAYMENT_FAILED) return "END " + clip(r.getFailureReason() == null ? "O'mari declined the payment." : r.getFailureReason(), 130) + " Ref " + ref;
            return "END Payment confirmed. You will get a message when it is done. Ref " + ref;
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

    /** EcoCash works on Econet numbers, OneMoney on NetOne and Telecash on Telecel: from another network the payer's number is asked. */
    private static boolean needsOwnNumber(String method, String network) {
        String home = HOME_NETWORK.get(method);
        return home != null && !home.equals(network);
    }

    private static String capital(String c) {
        return c == null || c.isEmpty() ? "" : c.charAt(0) + c.substring(1).toLowerCase(Locale.ROOT);
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

    // ─── catalogue (the same configuration the website and app read) ─────────

    /** The product's switched-on currencies that are live in this market (as /api/v1/products enabledCurrencies). */
    private List<String> currencies(Ctx c, String productCode) {
        ProductType p = products.findByCodeAndIsActiveTrue(productCode)
                .orElseThrow(() -> new IllegalArgumentException("This service is not available at the moment."));
        List<String> live = liveCurrencies(c.market());
        List<ProductCurrency> rows = productCurrencies.findActiveByProductId(p.getId());
        if (rows.isEmpty()) return live;
        List<String> out = rows.stream().map(ProductCurrency::getCurrency).filter(live::contains).distinct().toList();
        if (out.isEmpty()) throw new IllegalArgumentException("This service is not available in this country's currencies.");
        return out;
    }

    /** The product's minimum and maximum for the currency, as the website and app enforce them. */
    private String limits(String productCode, String ccy, long amount) {
        Optional<ProductType> p = products.findByCodeAndIsActiveTrue(productCode);
        if (p.isEmpty()) return null;
        Optional<ProductCurrency> pc = productCurrencies.findActiveByProductIdAndCurrency(p.get().getId(), ccy);
        if (pc.isEmpty()) return null;
        long min = pc.get().effectiveMin(p.get()), max = pc.get().effectiveMax(p.get());
        if (min > 0 && amount < min) return "The minimum is " + ccy + " " + major(min) + ".";
        if (max > 0 && amount > max) return "The maximum is " + ccy + " " + major(max) + ".";
        return null;
    }

    private String networkOf(String local) {
        if (local == null || local.length() < 4) return null;
        return prefixes.findByPrefix(local.substring(0, 4)).map(p -> p.getNetworkCode()).orElse(null);
    }

    private static String local(String msisdn, MarketRegistry.Market m) {
        return msisdn.startsWith(m.phoneCountryCode()) ? "0" + msisdn.substring(m.phoneCountryCode().length()) : msisdn;
    }

    private static String phone(String typed, MarketRegistry.Market m) {
        String n = typed == null ? "" : typed.replaceAll("[^0-9]", "");
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

    /** The payment methods switched on (as /api/v1/products/payment-methods); a logged-in customer's wallet comes first. */
    private List<String> methods(boolean withWallet) {
        List<String> active = jdbc.queryForList("SELECT code FROM payment_methods WHERE is_active = TRUE ORDER BY display_order", String.class);
        List<String> out = new ArrayList<>();
        if (withWallet) out.add(WalletService.RAIL);
        for (String m : active) if (!NOT_OFFERED.contains(m) && !out.contains(m)) out.add(m);
        if (out.isEmpty()) throw new IllegalArgumentException("No payment methods are available at the moment.");
        return out;
    }

    private String methodName(String code) {
        if (WalletService.RAIL.equals(code)) return "PayPort wallet";
        List<String> n = jdbc.queryForList("SELECT name FROM payment_methods WHERE code = ?", String.class, code);
        return n.isEmpty() ? code : n.get(0);
    }

    /** A list to choose from, a page at a time when it does not fit on one screen ("0. More"). */
    private <T> Step<T> choose(Ctx c, In in, String title, List<T> items, Function<T, String> label) {
        if (items.isEmpty()) throw new IllegalArgumentException("Nothing to choose from.");
        int start = 0;
        while (true) {
            int budget = c.room() - title.length() - 1;
            List<String> lines = new ArrayList<>();
            int used = 0, end = start;
            while (end < items.size()) {
                String line = (lines.size() + 1) + ". " + label.apply(items.get(end));
                int reserve = end + 1 < items.size() ? 8 : 0;
                if (!lines.isEmpty() && used + line.length() + 1 + reserve > budget) break;
                lines.add(line);
                used += line.length() + 1;
                end++;
            }
            boolean more = end < items.size();
            if (!in.has()) return Step.ask("CON " + title + "\n" + String.join("\n", lines) + (more ? "\n" + MORE + ". More" : ""));
            String tok = in.next();
            if (MORE.equals(tok) && more) {
                start = end;
                continue;
            }
            int k;
            try { k = Integer.parseInt(tok.trim()); } catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid choice."); }
            if (k < 1 || k > end - start) throw new IllegalArgumentException("Invalid choice.");
            return Step.of(items.get(start + k - 1));
        }
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

    /** A final screen that fits the channel (USSD screens are short). */
    private static String fit(Ctx c, String text) {
        return clip(text, c.ussd() ? 182 : 3000);
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
