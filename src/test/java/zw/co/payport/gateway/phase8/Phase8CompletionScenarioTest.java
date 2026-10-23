package zw.co.payport.gateway.phase8;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import zw.co.payport.gateway.config.JwtService;
import zw.co.payport.gateway.iso20022.BankMessageService;
import zw.co.payport.gateway.ledger.LedgerClient;
import zw.co.payport.gateway.markets.MarketRegistry;
import zw.co.payport.gateway.pos.PosService;
import zw.co.payport.gateway.risk.UnSanctionsList;
import zw.co.payport.gateway.service.EmailService;
import zw.co.payport.gateway.service.SmsNotificationService;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;
import zw.co.payport.gateway.wallets.WalletService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 8 completion over HTTP on MySQL 8. Everything starts OFF; an administrator switches each
 * line and its channel on, then:
 *  - wallets: top-up through the orchestrator, PIN-confirmed transfers, overspend refused by the
 *    ledger, daily limits, pay a payment and a checkout with the wallet, refund to wallet, FX
 *    conversion between the holder's own wallets, idempotent retries;
 *  - agents: compliance approval, four-eyes float, cash-in, single-use cash-out codes, commission;
 *  - POS: signed terminal requests, replay refused, sale = checkout session, cards fail closed;
 *  - WhatsApp: signed webhook, USSD menus, de-duplication, channel switch;
 *  - provider settlements recognised on the bank statement by a four-eyes rule;
 *  - the ledger stays consistent throughout (integrity report empty).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Phase8CompletionScenarioTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0");

    static final String WA_SECRET = "wa-app-secret-test";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("DB_URL", MYSQL::getJdbcUrl);
        r.add("DB_USERNAME", MYSQL::getUsername);
        r.add("DB_PASSWORD", MYSQL::getPassword);
        r.add("JWT_SECRET", () -> "phase8-test-secret-phase8-test-secret-0123456789abcdef");
        r.add("payport.payments.resolver.enabled", () -> "false");
        r.add("payport.payments.watchdog.enabled", () -> "false");
        r.add("payport.fulfilment.worker.enabled", () -> "false");
        r.add("payport.push.enabled", () -> "false");
        r.add("payport.ussd.secret", () -> "ussd-test-secret");
        r.add("payport.whatsapp.app-secret", () -> WA_SECRET);
        r.add("payport.whatsapp.verify-token", () -> "wa-verify");
        zw.co.payport.gateway.support.UnListStub.register(r);
    }

    @MockitoBean SmsNotificationService sms;
    @MockitoBean EmailService email;

    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired UserDetailsService users;
    @Autowired MarketRegistry markets;
    @Autowired UnSanctionsList unList;
    @Autowired TransactionOrchestratorService orchestrator;
    @Autowired WalletService wallets;
    @Autowired LedgerClient ledger;
    @Autowired BankMessageService bank;
    @Value("${local.server.port}") int port;

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000));
    static final String PIN = "2580";
    static final String PIN_HASH = new BCryptPasswordEncoder().encode(PIN);

    record Res(int status, JsonNode body) {
        JsonNode data() { return body.path("data"); }
        String code() { return body.path("error").path("code").asText(); }
        String message() { return body.path("error").path("message").asText(body.path("message").asText()); }
    }

    @BeforeEach
    void fresh() {
        zw.co.payport.gateway.support.UnListStub.STATUS.set(200);
        unList.refresh("test");
        markets.reload();
    }

    Res call(String method, String path, String bearer, Object body) throws Exception {
        return call(method, path, bearer, body, Map.of());
    }

    Res call(String method, String path, String bearer, Object body, Map<String, String> headers) throws Exception {
        String raw = body == null ? null : body instanceof String s ? s : JSON.writeValueAsString(body);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        headers.forEach(b::header);
        b.method(method, raw == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(raw));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        String t = r.body();
        JsonNode n;
        try { n = t == null || t.isBlank() ? JSON.createObjectNode() : JSON.readTree(t); } catch (Exception e) { n = JSON.createObjectNode().put("text", t); }
        return new Res(r.statusCode(), n);
    }

    String mobile() {
        return String.format("+26377%07d", SEQ.incrementAndGet() % 10_000_000);
    }

    String staff(String name, String role) {
        String phone = mobile();
        jdbc.update("INSERT INTO users (full_name, phone, pin_hash, role, is_active, is_verified, email, must_change_password) VALUES (?, ?, 'x', ?, TRUE, TRUE, ?, FALSE)",
                name, phone, role, name.replace(' ', '.').toLowerCase() + SEQ.incrementAndGet() + "@payport.test");
        return jwt.generateToken(users.loadUserByUsername(phone));
    }

    record Customer(String phone, String token) {}

    Customer customer(String name) {
        String phone = mobile();
        jdbc.update("INSERT INTO users (full_name, phone, pin_hash, role, is_active, is_verified) VALUES (?, ?, ?, 'CUSTOMER', TRUE, TRUE)", name, phone, PIN_HASH);
        return new Customer(phone, jwt.generateToken(users.loadUserByUsername(phone)));
    }

    /** The owner's switch-on: an administrator switches the line (and its channel) on. */
    void switchOn(String line, String channel) throws Exception {
        String admin = staff("Ad Min", "ADMIN");
        Res on = call("POST", "/api/v1/admin/service-lines/" + line, admin, Map.of("enabled", true));
        assertEquals(200, on.status(), on.body().toString());
        if (channel != null) {
            Res ch = call("POST", "/api/v1/admin/channels/" + channel, admin, Map.of("enabled", true));
            assertEquals(200, ch.status(), ch.body().toString());
        }
    }

    boolean isOn(String line) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT enabled FROM service_lines WHERE code = ?", Boolean.class, line));
    }

    void ensureOn(String line, String channel) throws Exception {
        if (!isOn(line)) switchOn(line, channel);
        else if (channel != null) call("POST", "/api/v1/admin/channels/" + channel, staff("Ad Min", "ADMIN"), Map.of("enabled", true, "reason", "launch"));
    }

    String openWallet(Customer c, String ccy) throws Exception {
        Res w = call("POST", "/api/v1/wallet/open", c.token(), Map.of("currency", ccy));
        assertEquals(200, w.status(), w.body().toString());
        return w.data().path("walletRef").asText();
    }

    long balance(Customer c, String ccy) throws Exception {
        for (JsonNode w : call("GET", "/api/v1/wallet", c.token(), null).data().path("wallets")) {
            if (ccy.equals(w.path("currency").asText())) return w.path("balanceMinor").asLong();
        }
        return -1;
    }

    String ref(String channel) {
        return String.format("PP-%s-20261016-%06d", channel, SEQ.incrementAndGet() % 1_000_000);
    }

    /** A top-up through the real lifecycle: initiate, payment confirmed by the rail, delivery credits the wallet. */
    void topUp(String walletRef, long cents) throws Exception {
        String r = ref("WEB");
        Res init = call("POST", "/api/v1/transactions/initiate", null, Map.of("reference", r, "productTypeCode", "WALLET_TOPUP", "recipientIdentifier", walletRef,
                "amountCents", cents, "currency", "USD", "customerPhone", "+263771234567", "channel", "WEB"));
        assertEquals(201, init.status(), init.body().toString());
        orchestrator.simulateTestPayment(r, true);
        assertEquals(TransactionOrchestratorService.FulfilmentOutcome.Kind.DELIVERED, orchestrator.executeFulfilment(r).kind());
    }

    long activeMerchantWithLogin(String name) throws Exception {
        int n = SEQ.incrementAndGet();
        String emailAddr = "owner" + n + "@phase8.test";
        Res signup = call("POST", "/api/v1/merchant-portal/signup", null, Map.of("businessName", name + " " + n, "registrationNumber", "REG-P8-" + n,
                "businessCategory", "RETAIL", "contactEmail", emailAddr, "contactPhone", "+263772" + String.format("%06d", n % 1_000_000),
                "ownerFullName", "Owner " + n, "password", "Str0ng!Pass"));
        assertEquals(201, signup.status(), signup.body().toString());
        long id = signup.data().path("id").asLong();
        jdbc.update("UPDATE merchants SET status = 'ACTIVE' WHERE id = ?", id);
        jdbc.update("INSERT INTO merchant_enablements (merchant_id, country, currency, status, enabled_by, enabled_at) VALUES (?, 'ZW', 'USD', 'ENABLED', 'test', NOW(6))", id);
        lastLogin = call("POST", "/api/v1/merchant-portal/login", null, Map.of("email", emailAddr, "password", "Str0ng!Pass")).data().path("token").asText();
        assertFalse(lastLogin.isBlank());
        return id;
    }

    String lastLogin;

    // ─── wallets ─────────────────────────────────────────────────────────────

    @Test
    void wallets_offByDefault_thenTopUpTransferPayRefundConvert_ledgerAuthoritative() throws Exception {
        Customer ann = customer("Ann Wallet"), ben = customer("Ben Wallet");
        if (!isOn("WALLETS")) {
            Res list = call("GET", "/api/v1/wallet", ann.token(), null);
            assertEquals(200, list.status());
            assertFalse(list.data().path("enabled").asBoolean(), "wallets ship switched off");
            assertEquals(503, call("POST", "/api/v1/wallet/open", ann.token(), Map.of("currency", "USD")).status());
            switchOn("WALLETS", null);
        }
        String annRef = openWallet(ann, "USD"), benRef = openWallet(ben, "USD");
        assertEquals(annRef, openWallet(ann, "USD"), "opening twice returns the same wallet");
        assertEquals(400, call("POST", "/api/v1/wallet/open", ann.token(), Map.of("currency", "ZMW")).status(), "a sandbox currency takes no live wallets");

        // Top-up through the orchestrator (product off by default, switched on by ops).
        jdbc.update("UPDATE product_types SET is_active = TRUE WHERE code = 'WALLET_TOPUP'");
        Res noWallet = call("POST", "/api/v1/transactions/initiate", null, Map.of("reference", ref("WEB"), "productTypeCode", "WALLET_TOPUP",
                "recipientIdentifier", "W00000000000", "amountCents", 1000, "currency", "USD", "channel", "WEB"));
        assertEquals(422, noWallet.status(), "a top-up to a wallet that does not exist is refused before anyone pays");
        topUp(annRef, 5_000);
        assertEquals(5_000, balance(ann, "USD"));

        // Transfers: PIN, idempotent retry, overspend refused by the ledger, daily limit.
        assertEquals("PIN_REJECTED", call("POST", "/api/v1/wallet/transfers", ann.token(),
                Map.of("to", ben.phone(), "currency", "USD", "amountMinor", 1200, "pin", "0000")).code());
        Map<String, Object> t = Map.of("to", ben.phone(), "currency", "USD", "amountMinor", 1200, "pin", PIN, "clientRef", "pay-ben-1");
        Res sent = call("POST", "/api/v1/wallet/transfers", ann.token(), t);
        assertEquals(200, sent.status(), sent.body().toString());
        assertEquals(200, call("POST", "/api/v1/wallet/transfers", ann.token(), t).status(), "a retry with the same clientRef");
        assertEquals(3_800, balance(ann, "USD"), "the retry did not send twice");
        assertEquals(1_200, balance(ben, "USD"));
        Res over = call("POST", "/api/v1/wallet/transfers", ann.token(), Map.of("to", benRef, "currency", "USD", "amountMinor", 10_000, "pin", PIN));
        assertEquals(402, over.status(), over.body().toString());
        assertEquals("INSUFFICIENT_FUNDS", over.code());
        Res limit = call("POST", "/api/v1/wallet/transfers", ann.token(), Map.of("to", benRef, "currency", "USD", "amountMinor", 25_000, "pin", PIN));
        assertEquals(400, limit.status());
        assertTrue(limit.message().contains("daily limit"), limit.message());

        // Pay an airtime payment from the wallet (method off by default; guests cannot use it).
        jdbc.update("UPDATE payment_methods SET is_active = TRUE WHERE code = 'WALLET'");
        String airtime = ref("WEB");
        assertEquals(201, call("POST", "/api/v1/transactions/initiate", null, Map.of("reference", airtime, "productTypeCode", "AIRTIME_ECONET",
                "recipientIdentifier", "0771234567", "amountCents", 500, "currency", "USD", "customerPhone", ann.phone(), "channel", "WEB")).status());
        String guest = ref("WEB");
        String guestToken = call("POST", "/api/v1/transactions/initiate", null, Map.of("reference", guest, "productTypeCode", "AIRTIME_ECONET",
                "recipientIdentifier", "0771234567", "amountCents", 500, "currency", "USD", "customerPhone", ann.phone(), "channel", "WEB"))
                .data().path("accessToken").asText();
        call("POST", "/api/v1/transactions/pay", null, Map.of("reference", guest, "paymentMethodCode", "WALLET", "customerPhone", ann.phone()),
                Map.of("X-Transaction-Token", guestToken));
        assertEquals("PAYMENT_FAILED", jdbc.queryForObject("SELECT status FROM transactions WHERE reference = ?", String.class, guest),
                "without the holder's PIN nothing is taken");
        assertEquals(3_800, balance(ann, "USD"));
        Res paid = call("POST", "/api/v1/wallet/pay", ann.token(), Map.of("reference", airtime, "pin", PIN));
        assertEquals(200, paid.status(), paid.body().toString());
        assertEquals("PAYMENT_CONFIRMED", paid.data().path("status").asText(), paid.body().toString());
        assertEquals(3_300, balance(ann, "USD"));

        // Vendor failed the airtime: finance refunds it to the wallet; the payment is closed as REFUNDED.
        jdbc.update("UPDATE transactions SET status = 'FULFILMENT_FAILED' WHERE reference = ?", airtime);
        String fin = staff("Fin Refunds", "FINANCE");
        Res refund = call("POST", "/api/v1/admin/wallets/refunds/" + airtime, fin, Map.of("reason", "vendor failed"));
        assertEquals(200, refund.status(), refund.body().toString());
        assertEquals("REFUNDED", refund.data().path("status").asText());
        assertEquals(3_800, balance(ann, "USD"));
        assertEquals(409, call("POST", "/api/v1/admin/wallets/refunds/" + airtime, fin, Map.of("reason", "again")).status(), "refunded once");
        assertEquals(3_800, balance(ann, "USD"));

        // Pay a merchant's hosted checkout from the wallet.
        long m = activeMerchantWithLogin("Wallet Cafe");
        jdbc.update("INSERT INTO checkout_sessions (merchant_id, reference, amount_cents, currency, status, expires_at, created_at, updated_at, livemode) "
                + "VALUES (?, ?, 700, 'USD', 'CREATED', ?, NOW(), NOW(), TRUE)", m, "ORD-W-" + SEQ.incrementAndGet(), LocalDateTime.now().plusMinutes(30));
        long sid = jdbc.queryForObject("SELECT MAX(id) FROM checkout_sessions", Long.class);
        Res cs = call("POST", "/api/v1/wallet/pay-checkout", ann.token(), Map.of("sessionId", sid, "pin", PIN));
        assertEquals(200, cs.status(), cs.body().toString());
        assertEquals(3_100, balance(ann, "USD"));

        // FX between Ann's own wallets: approved rate, quote, PIN, executed once, expired quotes refused.
        ensureOn("FX_CONVERSION", null);
        String fiona = staff("Fiona Fx", "FINANCE"), frank = staff("Frank Fx", "FINANCE");
        long rid = call("POST", "/api/v1/admin/fx/rates", fiona, Map.of("base", "USD", "quote", "ZWG", "rate", 26.5, "spreadBps", 100, "source", "RBZ interbank"))
                .data().path("id").asLong();
        assertEquals(200, call("POST", "/api/v1/admin/fx/rates/" + rid + "/decide", frank, Map.of("approve", true)).status());
        openWallet(ann, "ZWG");
        Res q = call("POST", "/api/v1/wallet/fx/quotes", ann.token(), Map.of("from", "USD", "to", "ZWG", "amountMinor", 1_000));
        assertEquals(200, q.status(), q.body().toString());
        assertEquals(26_235, q.data().path("convertedMinor").asLong());
        String quote = q.data().path("quoteRef").asText();
        assertEquals("PIN_REJECTED", call("POST", "/api/v1/wallet/fx/conversions", ann.token(), Map.of("quoteRef", quote, "pin", "1111")).code());
        Res fx = call("POST", "/api/v1/wallet/fx/conversions", ann.token(), Map.of("quoteRef", quote, "pin", PIN));
        assertEquals(200, fx.status(), fx.body().toString());
        assertEquals(200, call("POST", "/api/v1/wallet/fx/conversions", ann.token(), Map.of("quoteRef", quote, "pin", PIN)).status());
        assertEquals(2_100, balance(ann, "USD"), "converted once");
        assertEquals(26_235, balance(ann, "ZWG"));
        String stale = call("POST", "/api/v1/wallet/fx/quotes", ann.token(), Map.of("from", "USD", "to", "ZWG", "amountMinor", 100)).data().path("quoteRef").asText();
        jdbc.update("UPDATE fx_quotes SET expires_at = ? WHERE quote_ref = ?", LocalDateTime.now().minusSeconds(1), stale);
        assertEquals(409, call("POST", "/api/v1/wallet/fx/conversions", ann.token(), Map.of("quoteRef", stale, "pin", PIN)).status());

        // Ops: freeze stops spending at once; only compliance/admin unfreezes; statement and totals.
        String ops = staff("Olga Ops", "OPS"), comp = staff("Cathy Comp", "COMPLIANCE");
        assertEquals(200, call("POST", "/api/v1/admin/wallets/" + annRef + "/status", ops, Map.of("status", "FROZEN", "reason", "fraud report")).status());
        assertEquals(409, call("POST", "/api/v1/wallet/transfers", ann.token(), Map.of("to", benRef, "currency", "USD", "amountMinor", 100, "pin", PIN)).status());
        assertEquals(403, call("POST", "/api/v1/admin/wallets/" + annRef + "/status", ops, Map.of("status", "ACTIVE", "reason", "cleared")).status());
        assertEquals(200, call("POST", "/api/v1/admin/wallets/" + annRef + "/status", comp, Map.of("status", "ACTIVE", "reason", "cleared")).status());
        assertTrue(call("GET", "/api/v1/wallet/" + annRef + "/statement", ann.token(), null).data().size() >= 5);
        assertEquals(400, call("GET", "/api/v1/wallet/" + annRef + "/statement", ben.token(), null).status(), "not someone else's statement");
        assertEquals(200, call("GET", "/api/v1/admin/wallets/totals", fin, null).status());

        assertTrue(ledger.integrityReport().isEmpty(), ledger.integrityReport().toString());
    }

    // ─── agents ──────────────────────────────────────────────────────────────

    @Test
    void agents_onboardedByOps_withCode_detailDashboardAndEdit() throws Exception {
        String admin = staff("Ad Onboard", "ADMIN"), ops = staff("Op Onboard", "OPS");
        Customer cust = customer("Not Staff");
        long m = activeMerchantWithLogin("Onboarded Agent Shop");
        Res picker = call("GET", "/api/v1/admin/agents/onboardable-merchants", ops, null);
        assertEquals(200, picker.status(), picker.body().toString());
        boolean listed = false;
        for (JsonNode x : picker.data()) listed |= x.path("id").asLong() == m;
        assertTrue(listed, "an active merchant without an agent is offered");

        assertEquals(403, call("POST", "/api/v1/admin/agents", cust.token(), Map.of("merchantId", m, "name", "X", "phone", "+263779990001", "location", "Harare")).status());
        Res on = call("POST", "/api/v1/admin/agents", admin, Map.of("merchantId", m, "name", "Mbare Agent", "phone", "+263779990001", "location", "Mbare, Harare", "commissionBps", 60));
        assertEquals(200, on.status(), on.body().toString());
        long id = on.data().path("id").asLong();
        assertTrue(on.data().path("agent_code").asText().matches("AG\\d{8}"), on.body().toString());
        assertEquals("ACTIVE", on.data().path("status").asText(), "onboarded agents are live at once");
        assertEquals(409, call("POST", "/api/v1/admin/agents", admin, Map.of("merchantId", m, "name", "Again", "phone", "+263779990001", "location", "Harare")).status(),
                "one agent per merchant");

        Res upd = call("PUT", "/api/v1/admin/agents/" + id, ops, Map.of("location", "Mbare Musika, Harare", "commissionBps", 75));
        assertEquals(200, upd.status(), upd.body().toString());
        assertEquals(75, upd.data().path("commission_bps").asInt());
        Res detail = call("GET", "/api/v1/admin/agents/" + id, ops, null);
        assertEquals("Mbare Musika, Harare", detail.data().path("location").asText());
        assertTrue(detail.data().has("transactions") && detail.data().has("deposits") && detail.data().has("totals"));
        Res dash = call("GET", "/api/v1/admin/agents/dashboard", ops, null);
        assertEquals(200, dash.status(), dash.body().toString());
        assertTrue(dash.data().path("byStatus").isArray() && dash.data().has("float") && dash.data().has("topAgents"));
        assertEquals(403, call("GET", "/api/v1/admin/agents/dashboard", cust.token(), null).status());

        // Countries and currencies for the apps' drop-downs: public, read-only.
        Res mk = call("GET", "/api/v1/markets", null, null);
        assertEquals(200, mk.status(), mk.body().toString());
        boolean zwUsd = false;
        for (JsonNode x : mk.data()) if ("ZW".equals(x.path("country").asText())) for (JsonNode c : x.path("currencies")) zwUsd |= "USD".equals(c.path("code").asText());
        assertTrue(zwUsd, mk.body().toString());
    }


    @Test
    void agents_complianceApproval_fourEyesFloat_cashInCashOut_singleUseCodes() throws Exception {
        ensureOn("WALLETS", null);
        ensureOn("AGENTS", "AGENT");
        long merchantId = activeMerchantWithLogin("Corner Agent");
        String agentToken = lastLogin;
        Res applied = call("POST", "/api/v1/merchant/agent/apply", agentToken, Map.of("name", "Corner Agent", "phone", "+263772100100", "location", "Mbare, Harare"));
        assertEquals(200, applied.status(), applied.body().toString());
        assertEquals("PENDING", applied.data().path("status").asText());
        long agentId = applied.data().path("id").asLong();
        assertEquals(409, call("POST", "/api/v1/merchant/agent/cash-in", agentToken, Map.of("customer", "+263771234567", "currency", "USD", "amountMinor", 100)).status(),
                "not active yet");

        String comp = staff("Ag Comp", "COMPLIANCE");
        Res approved = call("POST", "/api/v1/admin/agents/" + agentId + "/decide", comp, Map.of("approve", true, "reason", "due diligence complete"));
        assertEquals(200, approved.status(), approved.body().toString());
        assertEquals("ACTIVE", approved.data().path("status").asText());

        // Float: the agent declares the bank deposit; finance approves (not the declarer); a bank reference is used once.
        String bankRef = "DEP-" + SEQ.incrementAndGet();
        Res dep = call("POST", "/api/v1/merchant/agent/float-deposits", agentToken, Map.of("amountMinor", 10_000, "currency", "USD", "bankReference", bankRef));
        assertEquals(200, dep.status(), dep.body().toString());
        assertEquals(409, call("POST", "/api/v1/merchant/agent/float-deposits", agentToken, Map.of("amountMinor", 10_000, "currency", "USD", "bankReference", bankRef)).status());
        String fin = staff("Fin Float", "FINANCE");
        Res ok = call("POST", "/api/v1/admin/agents/float-deposits/" + dep.data().path("id").asLong() + "/decide", fin, Map.of("approve", true, "note", "seen on statement"));
        assertEquals(200, ok.status(), ok.body().toString());
        Res own = call("POST", "/api/v1/admin/agents/float-deposits", fin, Map.of("agentId", agentId, "amountMinor", 500, "currency", "USD", "bankReference", "DEP-SELF-" + SEQ.incrementAndGet()));
        assertEquals(409, call("POST", "/api/v1/admin/agents/float-deposits/" + own.data().path("id").asLong() + "/decide", fin, Map.of("approve", true)).status(),
                "four eyes: the recorder cannot approve");
        assertEquals(10_000, call("GET", "/api/v1/merchant/agent", agentToken, null).data().path("agent").path("float").path("USD").asLong());

        // Cash-in: float -> wallet, commission 0.50% on top of float.
        Customer cara = customer("Cara Cash");
        openWallet(cara, "USD");
        Res in = call("POST", "/api/v1/merchant/agent/cash-in", agentToken, Map.of("customer", cara.phone(), "currency", "USD", "amountMinor", 3_000, "clientRef", "ci-1"));
        assertEquals(200, in.status(), in.body().toString());
        assertEquals(15, in.data().path("commissionMinor").asLong());
        assertEquals(7_015, in.data().path("floatMinor").asLong());
        assertEquals(3_000, balance(cara, "USD"));
        assertEquals(402, call("POST", "/api/v1/merchant/agent/cash-in", agentToken, Map.of("customer", cara.phone(), "currency", "USD", "amountMinor", 9_000)).status(),
                "an agent cannot hand out more e-value than its float");

        // Cash-out: the holder's one-time code; wrong code refused; redeemed once.
        Res code = call("POST", "/api/v1/wallet/cash-out-codes", cara.token(), Map.of("currency", "USD", "amountMinor", 1_000, "pin", PIN));
        assertEquals(200, code.status(), code.body().toString());
        String c = code.data().path("code").asText();
        assertEquals(400, call("POST", "/api/v1/merchant/agent/cash-out", agentToken, Map.of("customerPhone", cara.phone(), "code", "00000000")).status());
        Res out = call("POST", "/api/v1/merchant/agent/cash-out", agentToken, Map.of("customerPhone", cara.phone(), "code", c));
        assertEquals(200, out.status(), out.body().toString());
        assertEquals(2_000, balance(cara, "USD"));
        assertEquals(7_015 + 1_000 + 10, out.data().path("floatMinor").asLong());
        assertEquals(400, call("POST", "/api/v1/merchant/agent/cash-out", agentToken, Map.of("customerPhone", cara.phone(), "code", c)).status(), "single use");
        assertEquals(2, call("GET", "/api/v1/merchant/agent/transactions", agentToken, null).data().size());
        assertTrue(call("GET", "/api/v1/merchant/agent/commissions", agentToken, null).data().size() >= 2);
        assertEquals(merchantId, call("GET", "/api/v1/admin/agents", comp, null).data().get(0).path("merchant_id").asLong());

        // Suspension is immediate.
        String ops = staff("Ag Ops", "OPS");
        assertEquals(200, call("POST", "/api/v1/admin/agents/" + agentId + "/status", ops, Map.of("status", "SUSPENDED", "reason", "complaint")).status());
        assertEquals(409, call("POST", "/api/v1/merchant/agent/cash-in", agentToken, Map.of("customer", cara.phone(), "currency", "USD", "amountMinor", 100)).status());
        assertTrue(ledger.integrityReport().isEmpty(), ledger.integrityReport().toString());
    }

    // ─── POS ─────────────────────────────────────────────────────────────────

    Res signed(String method, String path, String terminal, String secret, String body, String nonce, long ts) throws Exception {
        String sig = PosService.sign(secret, method, path, String.valueOf(ts), nonce, body == null ? "" : body);
        return call(method, path, null, body, Map.of("X-Terminal-Id", terminal, "X-Timestamp", String.valueOf(ts), "X-Nonce", nonce, "X-Signature", sig));
    }

    @Test
    void pos_signedTerminal_replayRefused_saleIsACheckoutSession_cardsFailClosed() throws Exception {
        activeMerchantWithLogin("Till Shop");
        String m = lastLogin;
        assertEquals(503, call("POST", "/api/v1/merchant/pos/terminals", m, Map.of("label", "Till 1")).status(), "POS ships switched off");
        ensureOn("POS_ACQUIRING", "POS");
        Res reg = call("POST", "/api/v1/merchant/pos/terminals", m, Map.of("label", "Till 1"));
        assertEquals(200, reg.status(), reg.body().toString());
        String tid = reg.data().path("terminalId").asText(), secret = reg.data().path("secret").asText();
        assertFalse(call("GET", "/api/v1/merchant/pos/terminals", m, null).body().toString().contains(secret), "the secret is shown once only");

        long now = Instant.now().getEpochSecond();
        String body = "{\"saleRef\":\"S-1\",\"amountMinor\":1250,\"currency\":\"USD\",\"description\":\"2 coffees\"}";
        Res sale = signed("POST", "/api/v1/pos/sales", tid, secret, body, "nonce-a-0001", now);
        assertEquals(200, sale.status(), sale.body().toString());
        long session = sale.data().path("checkoutSessionId").asLong();
        assertEquals("CREATED", sale.data().path("status").asText());
        assertEquals(1250, jdbc.queryForObject("SELECT amount_cents FROM checkout_sessions WHERE id = ?", Long.class, session));
        assertEquals(403, signed("POST", "/api/v1/pos/sales", tid, secret, body, "nonce-a-0001", now).status(), "replay refused");
        assertEquals(session, signed("POST", "/api/v1/pos/sales", tid, secret, body, "nonce-a-0002", now).data().path("checkoutSessionId").asLong(),
                "same saleRef, same sale");
        assertEquals(403, signed("POST", "/api/v1/pos/sales", tid, "wrong-secret", body, "nonce-a-0003", now).status());
        assertEquals(403, signed("POST", "/api/v1/pos/sales", tid, secret, body, "nonce-a-0004", now - 3600).status(), "stale timestamp");
        assertEquals(403, call("POST", "/api/v1/pos/sales", null, body).status(), "unsigned");
        assertEquals(200, signed("GET", "/api/v1/pos/sales/S-1", tid, secret, "", "nonce-a-0005", now).status());
        Res card = signed("POST", "/api/v1/pos/sales/S-1/card", tid, secret, "{\"encryptedCardData\":\"p2pe-blob\"}", "nonce-a-0006", now);
        assertEquals(503, card.status(), card.body().toString());
        assertEquals("CHANNEL_UNAVAILABLE", card.code());

        assertEquals(200, call("POST", "/api/v1/merchant/pos/terminals/" + tid + "/status", m, Map.of("active", false, "reason", "stolen")).status());
        assertEquals(403, signed("GET", "/api/v1/pos/sales/S-1", tid, secret, "", "nonce-a-0007", now).status(), "disabled terminal");
        assertEquals(1, call("GET", "/api/v1/merchant/pos/sales", m, null).data().size());
    }

    // ─── WhatsApp ────────────────────────────────────────────────────────────

    Res whatsapp(String from, String id, String text, boolean sign) throws Exception {
        String body = JSON.writeValueAsString(Map.of("object", "whatsapp_business_account", "entry", List.of(Map.of("changes", List.of(Map.of("value",
                Map.of("messages", List.of(Map.of("from", from, "id", id, "type", "text", "text", Map.of("body", text))))))))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WA_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        return call("POST", "/api/v1/whatsapp/webhook", null, body, sign ? Map.of("X-Hub-Signature-256", sig) : Map.of());
    }

    String reply(String id) {
        return jdbc.queryForObject("SELECT reply FROM whatsapp_messages_seen WHERE message_id = ?", String.class, id);
    }

    @Test
    void whatsapp_verifiedWebhook_sameMenusAsUssd_dedupAndChannelSwitch() throws Exception {
        Res v = call("GET", "/api/v1/whatsapp/webhook?hub.mode=subscribe&hub.verify_token=wa-verify&hub.challenge=12345", null, null);
        assertEquals(200, v.status());
        assertEquals(403, call("GET", "/api/v1/whatsapp/webhook?hub.mode=subscribe&hub.verify_token=nope&hub.challenge=1", null, null).status());
        assertEquals(401, whatsapp("263771234567", "wamid.unsigned", "hi", false).status(), "unsigned deliveries are refused");

        String from = "263771234567";
        assertEquals(200, whatsapp(from, "wamid.off", "hi", true).status());
        assertTrue(reply("wamid.off").contains("not available"), "the channel ships switched off: " + reply("wamid.off"));

        ensureOn("WHATSAPP_CHANNEL", "WHATSAPP");
        whatsapp(from, "wamid.1", "hi", true);
        assertTrue(reply("wamid.1").contains("1. Buy airtime"), reply("wamid.1"));
        whatsapp(from, "wamid.2", "1", true);
        assertTrue(reply("wamid.2").startsWith("Airtime for:"), reply("wamid.2"));
        whatsapp(from, "wamid.2", "1", true);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM whatsapp_messages_seen WHERE message_id = 'wamid.2'", Integer.class), "Meta retries are answered once");
        whatsapp(from, "wamid.2b", "1", true);
        assertTrue(reply("wamid.2b").startsWith("Currency:"), reply("wamid.2b"));
        whatsapp(from, "wamid.3", "1", true);
        assertTrue(reply("wamid.3").startsWith("Amount (USD)"), reply("wamid.3"));
        whatsapp(from, "wamid.4", "menu", true);
        assertTrue(reply("wamid.4").contains("1. Buy airtime"), "MENU starts again");
        assertFalse(jdbc.queryForObject("SELECT sent FROM whatsapp_messages_seen WHERE message_id = 'wamid.4'", Boolean.class), "no sender configured: recorded, not sent");

        String ops = staff("Wa Ops", "OPS");
        assertEquals(200, call("GET", "/api/v1/admin/whatsapp", ops, null).status());
        call("POST", "/api/v1/admin/channels/WHATSAPP", ops, Map.of("enabled", false, "reason", "Meta outage"));
        whatsapp(from, "wamid.5", "hi", true);
        assertTrue(reply("wamid.5").contains("not available"));
    }

    // ─── provider settlements on the bank statement ─────────────────────────

    @Test
    void providerSettlement_creditRecognisedByAnApprovedRule_bookedToTheLedger() throws Exception {
        String account = "ZW-SPONSOR-USD-0001";
        jdbc.update("UPDATE market_currencies SET bank_account_number = ? WHERE currency = 'USD'", account);
        markets.reload();
        String fiona = staff("Fiona Rules", "FINANCE"), frank = staff("Frank Rules", "FINANCE");
        Res rule = call("POST", "/api/v1/admin/provider-settlement-rules", fiona,
                Map.of("providerCode", "ECOCASH", "currency", "USD", "counterpartyPattern", "wallet operator"));
        assertEquals(200, rule.status(), rule.body().toString());
        long id = rule.data().path("id").asLong();
        assertEquals(409, call("POST", "/api/v1/admin/provider-settlement-rules/" + id + "/decide", fiona, Map.of("approve", true)).status());
        assertEquals(200, call("POST", "/api/v1/admin/provider-settlement-rules/" + id + "/decide", frank, Map.of("approve", true)).status());

        String xml;
        try (var in = getClass().getResourceAsStream("/iso20022/camt053-sample.xml")) {
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("{{E2E_PAID}}", "NOTPROVIDED").replace("STMT-20261016-USD", "STMT-P8-USD");
        }
        Map<String, Object> imported = bank.importStatement(xml.getBytes(StandardCharsets.UTF_8), "fin.frank");
        @SuppressWarnings("unchecked") Map<String, Object> st = ((List<Map<String, Object>>) imported.get("statements")).get(0);
        assertEquals(Map.of("PROVIDER_SETTLEMENT", 1, "UNIDENTIFIED", 1, "NOT_BOOKED", 1), st.get("trace"));
        Map<String, Object> e = jdbc.queryForMap("SELECT * FROM bank_statement_entries WHERE trace_status = 'PROVIDER_SETTLEMENT'");
        Map<String, Object> j = jdbc.queryForMap("SELECT template_code, entity_code FROM ledger_entries WHERE entry_ref = ?", e.get("ledger_entry_ref"));
        assertEquals(Map.of("template_code", "PROVIDER_SETTLEMENT", "entity_code", "PAYPORT_ZW"), new LinkedHashMap<>(j));
        assertTrue(ledger.integrityReport().isEmpty());
    }
}
