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
                "address", "12 Samora Machel Ave, Harare", "ownerFullName", "Owner " + n, "password", "Str0ng!Pass"));
        assertEquals(201, signup.status(), signup.body().toString());
        long id = signup.data().path("id").asLong();
        jdbc.update("UPDATE merchants SET status = 'ACTIVE' WHERE id = ?", id);
        jdbc.update("INSERT INTO merchant_enablements (merchant_id, country, currency, status, enabled_by, enabled_at) VALUES (?, 'ZW', 'USD', 'ENABLED', 'test', NOW(6))", id);
        lastLogin = call("POST", "/api/v1/merchant-portal/login", null, Map.of("email", emailAddr, "password", "Str0ng!Pass")).data().path("token").asText();
        assertFalse(lastLogin.isBlank());
        return id;
    }

    String lastLogin;

    @Test
    void merchantCodes_uniqueSixDigits_payByCode_andWalletRulesChargeAndLimit() throws Exception {
        ensureOn("WALLETS", null);
        jdbc.update("UPDATE payment_methods SET is_active = TRUE WHERE code = 'WALLET'");
        jdbc.update("UPDATE product_types SET is_active = TRUE WHERE code = 'WALLET_TOPUP'");
        long m1 = activeMerchantWithLogin("Code Shop"), m2 = activeMerchantWithLogin("Other Shop");
        String c1 = jdbc.queryForObject("SELECT merchant_code FROM merchants WHERE id = ?", String.class, m1);
        String c2 = jdbc.queryForObject("SELECT merchant_code FROM merchants WHERE id = ?", String.class, m2);
        assertTrue(c1.matches("[1-9]\\d{5}") && c2.matches("[1-9]\\d{5}"), c1 + " " + c2);
        assertNotEquals(c1, c2);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT code FROM party_codes GROUP BY code HAVING COUNT(*) > 1) d", Integer.class));
        assertEquals("12 Samora Machel Ave, Harare", jdbc.queryForObject("SELECT address FROM merchants WHERE id = ?", String.class, m1));

        Res who = call("GET", "/api/v1/merchants/by-code/" + c1, null, null);
        assertEquals(200, who.status(), who.body().toString());
        assertTrue(who.data().path("name").asText().startsWith("Code Shop"));
        assertEquals(400, call("GET", "/api/v1/merchants/by-code/000000", null, null).status());
        Res guest = call("POST", "/api/v1/merchants/by-code/" + c1 + "/payments", null, Map.of("amountMinor", 700, "currency", "USD", "channel", "WEB"));
        assertEquals(200, guest.status(), guest.body().toString());
        assertTrue(guest.data().path("sessionId").asLong() > 0);

        Customer dee = customer("Dee Rules"), eve = customer("Eve Rules");
        String deeRef = openWallet(dee, "USD");
        openWallet(eve, "USD");
        topUp(deeRef, 20_000);
        Res paid = call("POST", "/api/v1/wallet/pay-merchant", dee.token(), Map.of("merchantCode", c1, "currency", "USD", "amountMinor", 1_000, "pin", PIN));
        assertEquals(200, paid.status(), paid.body().toString());
        assertEquals(19_000, balance(dee, "USD"));

        // Wallet rule for P2P on the mobile app only: 0.50 flat, 20% tax on the charge, at most 50.00 per transfer.
        String fin = staff("Fin Rules", "FINANCE");
        assertEquals(403, call("POST", "/api/v1/admin/rules/WALLET", staff("Ops Rules", "OPS"), Map.of("product", "P2P_TRANSFER", "currency", "USD")).status());
        Res rule = call("POST", "/api/v1/admin/rules/WALLET", fin, Map.of("product", "P2P_TRANSFER", "currency", "USD", "channel", "MOB",
                "maxAmount", "50.00", "chargeFlat", "0.50", "taxChargeable", true, "taxPercent", "20", "taxBase", "CHARGE"));
        assertEquals(200, rule.status(), rule.body().toString());
        assertTrue(call("GET", "/api/v1/admin/rules/WALLET", fin, null).body().toString().contains("P2P_TRANSFER"));
        assertEquals(409, call("POST", "/api/v1/admin/rules/WALLET", fin, Map.of("product", "P2P_TRANSFER", "currency", "USD", "channel", "MOB")).status(), "one rule per scope");
        Map<String, String> mob = Map.of("X-PayPort-Channel", "MOB");
        Res q = call("GET", "/api/v1/wallet/charges?product=P2P_TRANSFER&currency=USD&amountMinor=1000", dee.token(), null, mob);
        assertEquals(200, q.status(), q.body().toString());
        Res big = call("POST", "/api/v1/wallet/transfers", dee.token(), Map.of("to", eve.phone(), "currency", "USD", "amountMinor", 6_000, "pin", PIN), mob);
        assertEquals(400, big.status(), big.body().toString());
        Res sent = call("POST", "/api/v1/wallet/transfers", dee.token(), Map.of("to", eve.phone(), "currency", "USD", "amountMinor", 1_000, "pin", PIN, "clientRef", "rule-1"), mob);
        assertEquals(200, sent.status(), sent.body().toString());
        assertEquals(50, sent.data().path("chargeMinor").asLong());
        assertEquals(10, sent.data().path("taxMinor").asLong());
        assertEquals(19_000 - 1_000 - 60, balance(dee, "USD"));
        assertEquals(1_000, balance(eve, "USD"));
        Res web = call("POST", "/api/v1/wallet/transfers", dee.token(), Map.of("to", eve.phone(), "currency", "USD", "amountMinor", 6_000, "pin", PIN));
        assertEquals(200, web.status(), "the mobile-only rule does not apply to the web: " + web.body());
        assertEquals(19_000 - 1_000 - 60 - 6_000, balance(dee, "USD"));
        long ruleId = rule.data().path("id").asLong();
        assertEquals(200, call("DELETE", "/api/v1/admin/rules/WALLET/" + ruleId, fin, null).status());

        // A customer charge on a product: USD 1.00 airtime costs USD 1.10 on the mobile app (any payment method).
        Res air = call("POST", "/api/v1/admin/rules/WALLET", fin, Map.of("product", "AIRTIME", "currency", "USD", "channel", "MOB", "chargeFlat", "0.10"));
        assertEquals(200, air.status(), air.body().toString());
        Res quote = call("GET", "/api/v1/charges/quote?product=AIRTIME_ECONET&currency=USD&amountCents=100&channel=MOB", null, null);
        assertEquals(200, quote.status(), quote.body().toString());
        assertEquals(110, quote.data().path("totalCents").asLong());
        assertEquals(100, call("GET", "/api/v1/charges/quote?product=AIRTIME_ECONET&currency=USD&amountCents=100&channel=WEB", null, null).data().path("totalCents").asLong());
        String airRef = ref("MOB");
        Res started = call("POST", "/api/v1/transactions/initiate", null, Map.of("reference", airRef, "productTypeCode", "AIRTIME_ECONET",
                "recipientIdentifier", "0771234567", "amountCents", 100, "currency", "USD", "customerPhone", dee.phone(), "channel", "MOB"));
        assertEquals(201, started.status(), started.body().toString());
        assertEquals(10, started.data().path("chargeCents").asLong());
        assertEquals(110, started.data().path("totalCents").asLong());
        long before = balance(dee, "USD");
        Res paidAir = call("POST", "/api/v1/wallet/pay", dee.token(), Map.of("reference", airRef, "pin", PIN), mob);
        assertEquals(200, paidAir.status(), paidAir.body().toString());
        assertEquals(before - 110, balance(dee, "USD"), "face value + charge from the wallet");
        assertEquals(100L, jdbc.queryForObject("SELECT amount_cents FROM transactions WHERE reference = ?", Long.class, airRef), "the face value is what is delivered");
        assertEquals(200, call("DELETE", "/api/v1/admin/rules/WALLET/" + air.data().path("id").asLong(), fin, null).status());

        // Raw wallet report: filtered, with the charge and tax, exported to Excel and PDF.
        Res report = call("GET", "/api/v1/admin/wallets/report?q=" + deeRef + "&kind=P2P_OUT", fin, null);
        assertEquals(200, report.status(), report.body().toString());
        assertEquals(2, report.data().path("total").asLong());
        assertTrue(report.body().toString().contains("\"charge_minor\":50"), report.body().toString());
        assertEquals(400, call("GET", "/api/v1/admin/wallets/report?from=yesterday", fin, null).status());
        assertEquals(403, call("GET", "/api/v1/admin/wallets/report", dee.token(), null).status());
        Res pdf = call("GET", "/api/v1/admin/wallets/report/export?format=pdf&q=" + deeRef, fin, null);
        assertEquals(200, pdf.status());
        assertTrue(pdf.body().path("text").asText().startsWith("%PDF"), "a PDF");
        Res xlsx = call("GET", "/api/v1/admin/wallets/report/export?format=xlsx&q=" + deeRef, fin, null);
        assertEquals(200, xlsx.status());
        assertTrue(xlsx.body().path("text").asText().startsWith("PK"), "an Excel workbook");

        // Cards: requested against the wallet; they wait for the issuer; one of a kind per wallet; cancel.
        Res none = call("GET", "/api/v1/wallet/cards", dee.token(), null);
        assertEquals(200, none.status(), none.body().toString());
        assertEquals(0, none.data().path("cards").size());
        Res zim = call("POST", "/api/v1/wallet/cards", dee.token(), Map.of("scheme", "ZIMSWITCH", "form", "VIRTUAL", "nickname", "Dual", "pin", PIN));
        assertEquals(200, zim.status(), "a ZimSwitch card links the holder's USD and ZWG wallets: " + zim.body());
        assertEquals("USD", zim.data().path("currencies").get(0).asText());
        assertEquals(409, call("POST", "/api/v1/wallet/cards", dee.token(), Map.of("scheme", "ZIMSWITCH", "form", "VIRTUAL", "pin", PIN)).status(), "one of a kind");
        Res card = call("POST", "/api/v1/wallet/cards", dee.token(), Map.of("walletRef", deeRef, "scheme", "VISA", "form", "VIRTUAL", "nameOnCard", "Dee Rules", "pin", PIN));
        assertEquals(200, card.status(), card.body().toString());
        assertEquals("REQUESTED", card.data().path("status").asText());
        assertFalse(card.data().path("issuerConnected").asBoolean());
        String cardRef = card.data().path("cardRef").asText();
        assertEquals(409, call("POST", "/api/v1/wallet/cards", dee.token(), Map.of("walletRef", deeRef, "scheme", "VISA", "form", "VIRTUAL", "nameOnCard", "Dee Rules", "pin", PIN)).status());
        assertEquals(400, call("POST", "/api/v1/wallet/cards", eve.token(), Map.of("walletRef", deeRef, "scheme", "VISA", "form", "PHYSICAL", "nameOnCard", "Eve", "deliveryAddress", "1 Main St", "pin", PIN)).status(),
                "not someone else's wallet");
        assertEquals(409, call("POST", "/api/v1/wallet/cards/" + cardRef + "/action", dee.token(), Map.of("action", "FREEZE", "pin", PIN)).status(), "not issued yet");
        assertTrue(call("GET", "/api/v1/admin/cards?q=" + cardRef, fin, null).body().toString().contains(cardRef));
        Res cancelled = call("POST", "/api/v1/wallet/cards/" + cardRef + "/action", dee.token(), Map.of("action", "CANCEL", "pin", PIN));
        assertEquals(200, cancelled.status(), cancelled.body().toString());
        assertEquals("CANCELLED", cancelled.data().path("status").asText());

        // Wallets are found by wallet number or by the mobile number in any form.
        String local = "0" + dee.phone().substring(dee.phone().length() - 9);
        assertTrue(call("GET", "/api/v1/admin/wallets?q=" + local, fin, null).body().toString().contains(deeRef), "found by 07… number");
        assertTrue(call("GET", "/api/v1/admin/wallets?q=" + deeRef.toLowerCase(), fin, null).body().toString().contains(deeRef), "found by wallet number");
        assertTrue(call("GET", "/api/v1/admin/wallets/report?q=" + local, fin, null).body().toString().contains(deeRef), "report found by 07… number");
        assertTrue(ledger.integrityReport().isEmpty(), ledger.integrityReport().toString());
    }

    @Test
    void merchantCodes_branchCodesByApplication_staticQrReissued_reportPerCode() throws Exception {
        long m = activeMerchantWithLogin("Branch Shop");
        String mt = lastLogin;
        String main = jdbc.queryForObject("SELECT merchant_code FROM merchants WHERE id = ?", String.class, m);

        Res mine = call("GET", "/api/v1/merchant/codes", mt, null);
        assertEquals(200, mine.status(), mine.body().toString());
        assertEquals(1, mine.data().path("codes").size());
        assertEquals(main, mine.data().path("codes").get(0).path("code").asText());
        String qr1 = mine.data().path("codes").get(0).path("qrPayload").asText();
        assertTrue(qr1.startsWith("PAYPORT-QR:"), qr1);
        assertEquals(qr1, call("GET", "/api/v1/merchant/codes", mt, null).data().path("codes").get(0).path("qrPayload").asText(), "the QR is static");
        Res scan = call("GET", "/api/v1/codes/scan?payload=" + java.net.URLEncoder.encode(qr1, StandardCharsets.UTF_8), null, null);
        assertEquals(200, scan.status(), scan.body().toString());
        assertEquals("MERCHANT", scan.data().path("type").asText());
        assertEquals(main, scan.data().path("code").asText());

        Res reissued = call("POST", "/api/v1/merchant/codes/" + main + "/qr", mt, null);
        assertEquals(200, reissued.status(), reissued.body().toString());
        String qr2 = reissued.data().path("codes").get(0).path("qrPayload").asText();
        assertNotEquals(qr1, qr2);
        assertEquals(400, call("GET", "/api/v1/codes/scan?payload=" + java.net.URLEncoder.encode(qr1, StandardCharsets.UTF_8), null, null).status(), "the old QR stops working");
        assertEquals(main, call("GET", "/api/v1/codes/scan?payload=" + java.net.URLEncoder.encode(qr2, StandardCharsets.UTF_8), null, null).data().path("code").asText(), "same code");

        // A branch code: applied for in the portal, approved in the admin portal.
        Res applied = call("POST", "/api/v1/merchant/codes/requests", mt, Map.of("label", "Avondale branch", "address", "1 King George Rd, Avondale"));
        assertEquals(200, applied.status(), applied.body().toString());
        long reqId = applied.data().path("id").asLong();
        String ops = staff("Ops Codes", "OPS");
        assertTrue(call("GET", "/api/v1/admin/codes/requests?status=PENDING", ops, null).body().toString().contains("Avondale branch"));
        assertEquals(403, call("POST", "/api/v1/admin/codes/requests/" + reqId + "/decide", staff("Fin Codes", "FINANCE"), Map.of("approve", true)).status());
        Res decided = call("POST", "/api/v1/admin/codes/requests/" + reqId + "/decide", ops, Map.of("approve", true));
        assertEquals(200, decided.status(), decided.body().toString());
        String branch = decided.data().path("code").asText();
        assertTrue(branch.matches("[1-9]\\d{5}") && !branch.equals(main), branch);
        assertEquals(409, call("POST", "/api/v1/admin/codes/requests/" + reqId + "/decide", ops, Map.of("approve", true)).status(), "decided once");
        assertEquals(2, call("GET", "/api/v1/merchant/codes", mt, null).data().path("codes").size());
        assertTrue(call("GET", "/api/v1/admin/codes?q=" + branch, ops, null).body().toString().contains("Avondale branch"));

        Res who = call("GET", "/api/v1/merchants/by-code/" + branch, null, null);
        assertEquals(200, who.status(), who.body().toString());
        assertTrue(who.data().path("name").asText().endsWith("Avondale branch"), who.body().toString());
        Res started = call("POST", "/api/v1/merchants/by-code/" + branch + "/payments", null, Map.of("amountMinor", 900, "currency", "USD", "channel", "WEB"));
        assertEquals(200, started.status(), started.body().toString());
        assertEquals(branch, jdbc.queryForObject("SELECT party_code FROM checkout_sessions WHERE id = ?", String.class, started.data().path("sessionId").asLong()));
        Res rep = call("GET", "/api/v1/merchant/codes/report?code=" + branch, mt, null);
        assertEquals(200, rep.status(), rep.body().toString());
        assertEquals(1, rep.data().path("rows").size());
        assertEquals(0, call("GET", "/api/v1/merchant/codes/report?code=" + main, mt, null).data().path("rows").size());
        assertEquals(400, call("GET", "/api/v1/merchant/codes/report?code=000000", mt, null).status());
        assertEquals(200, call("GET", "/api/v1/admin/codes/report?type=MERCHANT&code=" + branch, ops, null).status());

        // A suspended branch code takes no payments; the main code cannot be suspended.
        assertEquals(200, call("POST", "/api/v1/admin/codes/" + branch + "/status", ops, Map.of("status", "SUSPENDED")).status());
        assertEquals(400, call("GET", "/api/v1/merchants/by-code/" + branch, null, null).status());
        assertEquals(409, call("POST", "/api/v1/admin/codes/" + main + "/status", ops, Map.of("status", "SUSPENDED")).status());
    }

    @Test
    void merchantPasswords_opsResetToTemporary_andForgotPasswordByLink() throws Exception {
        long m = activeMerchantWithLogin("Forgetful Shop");
        String ops = staff("Op Reset", "OPS");
        Res list = call("GET", "/api/v1/admin/merchants/" + m + "/users", ops, null);
        assertEquals(200, list.status(), list.body().toString());
        long userId = list.data().get(0).path("id").asLong();
        String mail = list.data().get(0).path("email").asText();
        jdbc.update("UPDATE merchant_users SET account_locked = TRUE, failed_login_attempts = 3 WHERE id = ?", userId);

        Res reset = call("POST", "/api/v1/admin/merchants/" + m + "/users/" + userId + "/reset-password", ops, null);
        assertEquals(200, reset.status(), reset.body().toString());
        String temp = reset.data().path("temporaryPassword").asText();
        Res in = call("POST", "/api/v1/merchant-portal/login", null, Map.of("email", mail, "password", temp));
        assertEquals(200, in.status(), "the reset also unlocks: " + in.body());
        assertTrue(in.data().path("user").path("mustChangePassword").asBoolean());
        assertEquals(403, call("POST", "/api/v1/admin/merchants/" + m + "/users/" + userId + "/reset-password", customer("Not Ops").token(), null).status());

        assertEquals(200, call("POST", "/api/v1/merchant-portal/forgot-password", null, Map.of("email", "nobody@nowhere.test")).status(), "no hint whether an email exists");
        assertEquals(200, call("POST", "/api/v1/merchant-portal/forgot-password", null, Map.of("email", mail)).status());
        String stored = jdbc.queryForObject("SELECT reset_token FROM merchant_users WHERE id = ?", String.class, userId);
        assertEquals(64, stored.length(), "only the link's hash is stored");
        jdbc.update("UPDATE merchant_users SET reset_token = ? WHERE id = ?", zw.co.payport.gateway.platform.PortalCredentials.sha256("known-link-token"), userId);
        assertEquals(400, call("POST", "/api/v1/merchant-portal/reset-password", null, Map.of("token", "known-link-token", "newPassword", "weak")).status());
        assertEquals(200, call("POST", "/api/v1/merchant-portal/reset-password", null, Map.of("token", "known-link-token", "newPassword", "New!Passw0rd")).status());
        assertEquals(400, call("POST", "/api/v1/merchant-portal/reset-password", null, Map.of("token", "known-link-token", "newPassword", "New!Passw0rd")).status(), "a link works once");
        assertEquals(200, call("POST", "/api/v1/merchant-portal/login", null, Map.of("email", mail, "password", "New!Passw0rd")).status());
    }

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

        // Vendor failed the airtime: operations asks for the refund on the reversal desk, finance approves it; the
        // payment is closed as REFUNDED and the money is back in the wallet.
        jdbc.update("UPDATE transactions SET status = 'FULFILMENT_FAILED' WHERE reference = ?", airtime);
        String fin = staff("Fin Refunds", "FINANCE");
        String opsDesk = staff("Ops Desk", "OPS");
        Res asked = call("POST", "/api/v1/admin/reversals", opsDesk, Map.of("kind", "WALLET_REFUND", "targetRef", airtime, "reason", "vendor failed"));
        assertEquals(200, asked.status(), asked.body().toString());
        String rvs = asked.data().path("reversal_ref").asText();
        Res refund = call("POST", "/api/v1/admin/reversal-approvals/" + rvs + "/approve", fin, Map.of());
        assertEquals(200, refund.status(), refund.body().toString());
        assertEquals("EXECUTED", refund.data().path("status").asText(), refund.body().toString());
        assertEquals("REFUNDED", jdbc.queryForObject("SELECT status FROM transactions WHERE reference = ?", String.class, airtime));
        assertEquals(3_800, balance(ann, "USD"));
        assertEquals(409, call("POST", "/api/v1/admin/reversals", opsDesk, Map.of("kind", "WALLET_REFUND", "targetRef", airtime, "reason", "again please")).status(), "refunded once");
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
    void agents_onboardedByOps_ownPortalLogin_separateFromMerchants() throws Exception {
        String admin = staff("Ad Onboard", "ADMIN"), ops = staff("Op Onboard", "OPS");
        Customer cust = customer("Not Staff");
        long m = activeMerchantWithLogin("A Merchant, Not An Agent");
        String merchantToken = lastLogin;
        String mail = "mbare" + SEQ.incrementAndGet() + "@agents.test";

        assertEquals(403, call("POST", "/api/v1/admin/agents", cust.token(), Map.of("name", "X", "phone", "+263779990001", "email", mail, "location", "Harare")).status());
        assertEquals(400, call("POST", "/api/v1/admin/agents", admin, Map.of("name", "No Mail", "phone", "+263779990009", "location", "Harare")).status(),
                "a portal login needs an email address");
        Res on = call("POST", "/api/v1/admin/agents", admin, Map.of("name", "Mbare Agent", "contactPerson", "Rudo Moyo", "phone", "+263779990001",
                "email", mail, "location", "Mbare, Harare", "idNumber", "63-123456A42", "commissionBps", 60));
        assertEquals(200, on.status(), on.body().toString());
        long id = on.data().path("id").asLong();
        assertTrue(on.data().path("agent_code").asText().matches("AG\\d{8}"), on.body().toString());
        assertEquals("ACTIVE", on.data().path("status").asText(), "onboarded agents are live at once");
        assertFalse(on.data().has("merchant_id"), "an agent is not a merchant");
        String temp = on.data().path("login").path("temporaryPassword").asText();
        assertFalse(temp.isBlank());
        assertEquals(409, call("POST", "/api/v1/admin/agents", admin, Map.of("name", "Again", "phone", "+263779990001", "email", "x" + mail, "location", "Harare")).status(),
                "one agent per phone number");

        // The agent's own portal: its own login, a forced password change, then its own dashboard.
        assertEquals(403, call("POST", "/api/v1/agent-portal/login", null, Map.of("email", mail, "password", "wrong")).status());
        Res login = call("POST", "/api/v1/agent-portal/login", null, Map.of("email", mail, "password", temp));
        assertEquals(200, login.status(), login.body().toString());
        String agentToken = login.data().path("token").asText();
        assertTrue(login.data().path("user").path("mustChangePassword").asBoolean());
        assertEquals(403, call("GET", "/api/v1/agent/dashboard", agentToken, null).status(), "the temporary password must be changed first");
        assertEquals(200, call("POST", "/api/v1/agent/change-password", agentToken, Map.of("currentPassword", temp, "newPassword", "Agent!Pass2026")).status());
        Res mine = call("GET", "/api/v1/agent/dashboard", agentToken, null);
        assertEquals(200, mine.status(), mine.body().toString());
        assertEquals(id, mine.data().path("agent").path("id").asLong());
        assertTrue(mine.data().has("today") && mine.data().has("commissionThisMonth") && mine.data().has("recent"));

        int asMerchant = call("GET", "/api/v1/agent/me", merchantToken, null).status();
        assertTrue(asMerchant == 401 || asMerchant == 403, "a merchant login is not an agent login: " + asMerchant);
        int asAgent = call("GET", "/api/v1/merchant/checkout-sessions", agentToken, null).status();
        assertTrue(asAgent == 401 || asAgent == 403, "an agent login is not a merchant login: " + asAgent);
        assertEquals(403, call("GET", "/api/v1/agent/me", cust.token(), null).status());

        Res upd = call("PUT", "/api/v1/admin/agents/" + id, ops, Map.of("location", "Mbare Musika, Harare", "commissionBps", 75));
        assertEquals(200, upd.status(), upd.body().toString());
        assertEquals(75, upd.data().path("commission_bps").asInt());
        Res detail = call("GET", "/api/v1/admin/agents/" + id, ops, null);
        assertEquals("Mbare Musika, Harare", detail.data().path("location").asText());
        assertTrue(detail.data().has("transactions") && detail.data().has("deposits") && detail.data().has("totals"));
        long userId = detail.data().path("users").get(0).path("id").asLong();
        Res reset = call("POST", "/api/v1/admin/agents/" + id + "/users/" + userId + "/reset-password", ops, null);
        assertEquals(200, reset.status(), reset.body().toString());
        assertEquals(200, call("POST", "/api/v1/agent-portal/login", null, Map.of("email", mail, "password", reset.data().path("temporaryPassword").asText())).status());
        assertEquals(200, call("POST", "/api/v1/agent-portal/forgot-password", null, Map.of("email", "nobody@agents.test")).status(), "no hint whether an email exists");

        Res dash = call("GET", "/api/v1/admin/agents/dashboard", ops, null);
        assertEquals(200, dash.status(), dash.body().toString());
        assertTrue(dash.data().path("byStatus").isArray() && dash.data().has("float") && dash.data().has("topAgents"));
        assertEquals(403, call("GET", "/api/v1/admin/agents/dashboard", cust.token(), null).status());
        assertTrue(m > 0);

        // Countries and currencies for the apps' drop-downs: public, read-only.
        Res mk = call("GET", "/api/v1/markets", null, null);
        assertEquals(200, mk.status(), mk.body().toString());
        boolean zwUsd = false;
        for (JsonNode x : mk.data()) if ("ZW".equals(x.path("country").asText())) for (JsonNode c : x.path("currencies")) zwUsd |= "USD".equals(c.path("code").asText());
        assertTrue(zwUsd, mk.body().toString());
    }


    @Test
    void agents_applyAndTrack_complianceApproval_fourEyesFloat_cashInCashOut_singleUseCodes() throws Exception {
        ensureOn("WALLETS", null);
        ensureOn("AGENTS", "AGENT");
        String mail = "corner" + SEQ.incrementAndGet() + "@agents.test";
        Res applied = call("POST", "/api/v1/agent-portal/apply", null, Map.of("name", "Corner Agent", "contactPerson", "Tino", "phone", "+263772100100",
                "email", mail, "location", "Mbare, Harare"));
        assertEquals(200, applied.status(), applied.body().toString());
        assertEquals("PENDING", applied.data().path("status").asText());
        String ref = applied.data().path("applicationReference").asText();
        assertEquals("PENDING", call("GET", "/api/v1/agent-portal/track/" + ref, null, null).data().path("status").asText());
        long agentId = 0;
        for (JsonNode a : call("GET", "/api/v1/admin/agents", staff("Ag Viewer", "OPS"), null).data()) if (ref.equals(a.path("application_reference").asText())) agentId = a.path("id").asLong();
        assertTrue(agentId > 0);
        assertEquals(403, call("POST", "/api/v1/agent-portal/login", null, Map.of("email", mail, "password", "anything")).status(), "no login before approval");

        String comp = staff("Ag Comp", "COMPLIANCE");
        Res approved = call("POST", "/api/v1/admin/agents/" + agentId + "/decide", comp, Map.of("approve", true));
        assertEquals(200, approved.status(), approved.body().toString());
        assertEquals("ACTIVE", approved.data().path("status").asText());
        assertEquals("ACTIVE", call("GET", "/api/v1/agent-portal/track/" + ref, null, null).data().path("status").asText());
        String temp = approved.data().path("login").path("temporaryPassword").asText();
        String agentToken = call("POST", "/api/v1/agent-portal/login", null, Map.of("email", mail, "password", temp)).data().path("token").asText();
        assertEquals(200, call("POST", "/api/v1/agent/change-password", agentToken, Map.of("currentPassword", temp, "newPassword", "Corner!Agent1")).status());

        // Float: the agent declares the bank deposit; finance approves (not the declarer); a bank reference is used once.
        String bankRef = "DEP-" + SEQ.incrementAndGet();
        Res dep = call("POST", "/api/v1/agent/float-deposits", agentToken, Map.of("amountMinor", 10_000, "currency", "USD", "bankReference", bankRef));
        assertEquals(200, dep.status(), dep.body().toString());
        assertEquals(409, call("POST", "/api/v1/agent/float-deposits", agentToken, Map.of("amountMinor", 10_000, "currency", "USD", "bankReference", bankRef)).status());
        String fin = staff("Fin Float", "FINANCE");
        Res ok = call("POST", "/api/v1/admin/agents/float-deposits/" + dep.data().path("id").asLong() + "/decide", fin, Map.of("approve", true, "note", "seen on statement"));
        assertEquals(200, ok.status(), ok.body().toString());
        Res own = call("POST", "/api/v1/admin/agents/float-deposits", fin, Map.of("agentId", agentId, "amountMinor", 500, "currency", "USD", "bankReference", "DEP-SELF-" + SEQ.incrementAndGet()));
        assertEquals(409, call("POST", "/api/v1/admin/agents/float-deposits/" + own.data().path("id").asLong() + "/decide", fin, Map.of("approve", true)).status(),
                "four eyes: the recorder cannot approve");
        assertEquals(10_000, call("GET", "/api/v1/agent/me", agentToken, null).data().path("agent").path("float").path("USD").asLong());

        // Cash-in: float -> wallet, commission 0.50% on top of float.
        Customer cara = customer("Cara Cash");
        openWallet(cara, "USD");
        Res who = call("GET", "/api/v1/agent/customer?customer=" + java.net.URLEncoder.encode(cara.phone(), java.nio.charset.StandardCharsets.UTF_8) + "&currency=USD", agentToken, null);
        assertEquals(200, who.status(), who.body().toString());
        assertEquals("Cara C***", who.data().path("name").asText());
        Res in = call("POST", "/api/v1/agent/cash-in", agentToken, Map.of("customer", cara.phone(), "currency", "USD", "amountMinor", 3_000, "clientRef", "ci-1"));
        assertEquals(200, in.status(), in.body().toString());
        assertEquals(15, in.data().path("commissionMinor").asLong());
        assertEquals(7_015, in.data().path("floatMinor").asLong());
        assertEquals(3_000, balance(cara, "USD"));
        assertEquals(402, call("POST", "/api/v1/agent/cash-in", agentToken, Map.of("customer", cara.phone(), "currency", "USD", "amountMinor", 9_000)).status(),
                "an agent cannot hand out more e-value than its float");

        // Cash-out: the customer enters the agent's six-digit code, sees the agent's name, confirms with the PIN.
        String agentCode = approved.data().path("code").asText();
        // A further agent code (a second outlet), added by ops; customers see which outlet they are at.
        Res outlet = call("POST", "/api/v1/admin/codes/AGENT/" + agentId, comp, Map.of("label", "Mbare outlet", "address", "Mbare Musika"));
        assertEquals(200, outlet.status(), outlet.body().toString());
        assertEquals(2, outlet.data().path("codes").size());
        String outletCode = outlet.data().path("codes").get(1).path("code").asText();
        Res mineCodes = call("GET", "/api/v1/agent/codes", agentToken, null);
        assertEquals(200, mineCodes.status(), mineCodes.body().toString());
        assertEquals(2, mineCodes.data().path("codes").size());
        assertTrue(mineCodes.data().path("codes").get(0).path("qrPayload").asText().startsWith("PAYPORT-QR:"));
        Res atOutlet = call("GET", "/api/v1/wallet/agents/" + outletCode, cara.token(), null);
        assertEquals(200, atOutlet.status(), atOutlet.body().toString());
        assertEquals("Corner Agent – Mbare outlet", atOutlet.data().path("name").asText());
        assertEquals(200, call("GET", "/api/v1/agent/codes/report", agentToken, null).status());
        assertTrue(agentCode.matches("[1-9]\\d{5}"), approved.body().toString());
        Res agentWho = call("GET", "/api/v1/wallet/agents/" + agentCode, cara.token(), null);
        assertEquals(200, agentWho.status(), agentWho.body().toString());
        assertEquals("Corner Agent", agentWho.data().path("name").asText());
        assertEquals(400, call("GET", "/api/v1/wallet/agents/000000", cara.token(), null).status());
        assertEquals(400, call("POST", "/api/v1/wallet/cash-out", cara.token(), Map.of("agentCode", agentCode, "currency", "USD", "amountMinor", 1_000, "pin", "0000", "clientRef", "co-1")).status(), "wrong PIN");
        Res out = call("POST", "/api/v1/wallet/cash-out", cara.token(), Map.of("agentCode", agentCode, "currency", "USD", "amountMinor", 1_000, "pin", PIN, "clientRef", "co-1"));
        assertEquals(200, out.status(), out.body().toString());
        assertEquals("Corner Agent", out.data().path("agentName").asText());
        assertEquals(2_000, balance(cara, "USD"));
        assertEquals(7_015 + 1_000 + 10, call("GET", "/api/v1/agent/me", agentToken, null).data().path("agent").path("float").path("USD").asLong());
        assertEquals(200, call("POST", "/api/v1/wallet/cash-out", cara.token(), Map.of("agentCode", agentCode, "currency", "USD", "amountMinor", 1_000, "pin", PIN, "clientRef", "co-1")).status());
        assertEquals(2_000, balance(cara, "USD"), "the same request twice cashes out once");
        assertEquals(2, call("GET", "/api/v1/agent/transactions", agentToken, null).data().size());
        assertTrue(call("GET", "/api/v1/agent/commissions", agentToken, null).data().size() >= 2);

        // Suspension is immediate.
        String ops = staff("Ag Ops", "OPS");
        assertEquals(200, call("POST", "/api/v1/admin/agents/" + agentId + "/status", ops, Map.of("status", "SUSPENDED")).status());
        assertEquals(409, call("POST", "/api/v1/agent/cash-in", agentToken, Map.of("customer", cara.phone(), "currency", "USD", "amountMinor", 100)).status());
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
        assertTrue(reply("wamid.2b").startsWith("Econet Airtime\nCurrency:"), reply("wamid.2b"));
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

        // Connection settings from ops: identifiers shown, secrets stored encrypted and never shown.
        String admin = staff("Wa Admin", "ADMIN");
        assertEquals(403, call("PUT", "/api/v1/admin/whatsapp/settings", ops, Map.of("phoneNumberId", "123456789012345")).status(), "administrators only");
        Res set = call("PUT", "/api/v1/admin/whatsapp/settings", admin, Map.of("businessNumber", "+263 719 183 557", "phoneNumberId", "123456789012345",
                "accessToken", "EAAG-test-token-value"));
        assertEquals(200, set.status(), set.body().toString());
        assertEquals("+263719183557", set.data().path("businessNumber").asText());
        assertTrue(set.data().path("accessTokenSet").asBoolean());
        assertFalse(set.body().toString().contains("EAAG-test-token-value"));
        assertTrue(jdbc.queryForObject("SELECT access_token FROM whatsapp_settings WHERE id = 1", String.class).startsWith("enc:v1:"), "encrypted at rest");
        assertEquals(400, call("PUT", "/api/v1/admin/whatsapp/settings", admin, Map.of("phoneNumberId", "not-a-number")).status());
    }

    /** Owner, 2026-10-30: complaints are traced, and money is reversed only from the reversal desk after someone else approves. */
    @Test
    void complaintsAndTheReversalDesk_wrongNumberTransfer() throws Exception {
        ensureOn("WALLETS", null);
        jdbc.update("UPDATE product_types SET is_active = TRUE WHERE code = 'WALLET_TOPUP'");
        Customer sam = customer("Sam Sender"), will = customer("Will Wrong");
        String samRef = openWallet(sam, "USD");
        openWallet(will, "USD");
        topUp(samRef, 5_000);
        long samBefore = balance(sam, "USD"), willBefore = balance(will, "USD");
        Res sent = call("POST", "/api/v1/wallet/transfers", sam.token(), Map.of("to", will.phone(), "currency", "USD", "amountMinor", 1_500, "pin", PIN, "clientRef", "oops-1"));
        assertEquals(200, sent.status(), sent.body().toString());
        String op = sent.data().path("transferRef").asText();
        long samAfterSend = balance(sam, "USD");

        String ops = staff("Care Ops", "OPS"), admin = staff("Care Admin", "ADMIN"), fin = staff("Care Fin", "FINANCE");
        Map<String, Object> complaint = Map.of("category", "A_WRONG_NUMBER", "channel", "PHONE", "customerPhone", sam.phone(), "reference", op,
                "description", "Sent money to the wrong number");
        assertEquals(403, call("POST", "/api/v1/admin/complaints", fin, complaint).status(), "finance reads complaints, operations opens them");
        Res cmp = call("POST", "/api/v1/admin/complaints", ops, complaint);
        assertEquals(200, cmp.status(), cmp.body().toString());
        String cref = cmp.data().path("complaint_ref").asText();
        assertTrue(cref.startsWith("CMP-"), cref);
        assertEquals("NORMAL", cmp.data().path("priority").asText());
        assertEquals("WALLET_REVERSAL", cmp.data().path("trace").path("options").get(0).path("kind").asText(), cmp.body().toString());

        Res asked = call("POST", "/api/v1/admin/reversals", admin, Map.of("kind", "WALLET_REVERSAL", "targetRef", op, "reason", "Customer sent to the wrong number", "complaintRef", cref));
        assertEquals(200, asked.status(), asked.body().toString());
        String rvs = asked.data().path("reversal_ref").asText();
        assertEquals(1_500, asked.data().path("amount_minor").asLong(), "the amount comes from the record");
        assertEquals(403, call("POST", "/api/v1/admin/reversal-approvals/" + rvs + "/approve", admin, Map.of()).status(), "nobody approves their own request");
        assertEquals(samAfterSend, balance(sam, "USD"), "nothing moves before approval");
        Res ok = call("POST", "/api/v1/admin/reversal-approvals/" + rvs + "/approve", fin, Map.of("note", "confirmed with both parties"));
        assertEquals(200, ok.status(), ok.body().toString());
        assertEquals("EXECUTED", ok.data().path("status").asText(), ok.body().toString());
        assertEquals(samBefore, balance(sam, "USD"), "the sender has the money back, charges included");
        assertEquals(willBefore, balance(will, "USD"));
        Res c = call("GET", "/api/v1/admin/complaints/" + cmp.data().path("id").asLong(), ops, null);
        assertEquals("AWAITING_REVERSAL", c.data().path("status").asText());
        assertTrue(c.data().path("notes").toString().contains("approved"), c.body().toString());
        assertEquals(0, c.data().path("trace").path("options").size(), "a reversed transfer cannot be reversed again");
        assertEquals(200, call("POST", "/api/v1/admin/complaints/" + cmp.data().path("id").asLong() + "/status", ops,
                Map.of("status", "RESOLVED", "resolution", "The money is back in your wallet.")).status());

        // Money already spent by the recipient: the reversal is refused and recorded as failed.
        Res again = call("POST", "/api/v1/wallet/transfers", sam.token(), Map.of("to", will.phone(), "currency", "USD", "amountMinor", 1_000, "pin", PIN, "clientRef", "oops-2"));
        String op2 = again.data().path("transferRef").asText();
        assertEquals(200, call("POST", "/api/v1/wallet/transfers", will.token(), Map.of("to", sam.phone(), "currency", "USD", "amountMinor", 900, "pin", PIN, "clientRef", "spent")).status());
        String rvs2 = call("POST", "/api/v1/admin/reversals", ops, Map.of("kind", "WALLET_REVERSAL", "targetRef", op2, "reason", "Wrong number again")).data().path("reversal_ref").asText();
        Res failed = call("POST", "/api/v1/admin/reversal-approvals/" + rvs2 + "/approve", fin, Map.of());
        assertEquals("FAILED", failed.data().path("status").asText(), failed.body().toString());
        assertTrue(failed.data().path("error").asText().contains("no longer holds"), failed.body().toString());
    }

    /**
     * Owner, 2026-10-31: a WhatsApp bundle may have no internet for a web page, so the PIN is typed in the chat. It is
     * checked once and never kept: the session keeps "#", and PayPort records only its own replies.
     */
    @Test
    void whatsapp_pinTypedInTheChat_neverKept_wrongAmountAskedAgain_00GoesHome() throws Exception {
        ensureOn("WHATSAPP_CHANNEL", "WHATSAPP");
        String wa = String.format("263778%06d", 700000 + (int) (System.nanoTime() % 99999));
        jdbc.update("INSERT INTO users (full_name, phone, pin_hash, role, is_active, is_verified) VALUES ('Chipo Banda', ?, ?, 'CUSTOMER', TRUE, TRUE)",
                "+" + wa, new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("4826"));
        String p = "wamid.pin." + wa + ".";
        whatsapp(wa, p + 1, "hi", true);
        String hello = reply(p + 1);
        assertTrue(hello.contains("Welcome to PayPort, Chipo") && hello.contains("Enter your PayPort PIN") && !hello.contains("http"), hello);

        whatsapp(wa, p + 2, "4826", true);
        String menu = reply(p + 2);
        assertTrue(menu.contains("what would you like to do") && menu.contains("My wallet") && menu.contains("00 for the main menu"), menu);
        assertEquals("#", jdbc.queryForObject("SELECT path FROM whatsapp_sessions WHERE wa_id = ?", String.class, wa), "the PIN is never kept");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM whatsapp_messages_seen WHERE wa_id = ? AND reply LIKE '%4826%'", Integer.class, wa));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM channel_session_secrets WHERE value = '4826'", Integer.class));

        whatsapp(wa, p + 3, "1", true);
        whatsapp(wa, p + 4, "1", true);
        whatsapp(wa, p + 5, "1", true);
        assertTrue(reply(p + 5).startsWith("Amount (USD)"), reply(p + 5));
        whatsapp(wa, p + 6, "abc", true);
        assertTrue(reply(p + 6).contains("Enter another amount (USD)") && reply(p + 6).contains("0. Cancel"), reply(p + 6));
        whatsapp(wa, p + 7, "2.50", true);
        assertTrue(reply(p + 7).startsWith("Pay with:\n1. PayPort wallet"), "the corrected amount carries on: " + reply(p + 7));
        whatsapp(wa, p + 8, "00", true);
        assertTrue(reply(p + 8).contains("what would you like to do"), "00 goes back to the main menu, still logged in: " + reply(p + 8));
        assertEquals("#", jdbc.queryForObject("SELECT path FROM whatsapp_sessions WHERE wa_id = ?", String.class, wa));

        whatsapp(wa, p + 9, "menu", true);
        whatsapp(wa, p + 10, "1111", true);
        assertTrue(reply(p + 10).contains("Wrong PIN"), reply(p + 10));
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
