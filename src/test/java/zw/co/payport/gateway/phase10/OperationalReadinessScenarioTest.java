package zw.co.payport.gateway.phase10;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
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
import zw.co.payport.gateway.iso20022.ProviderSettlementService;
import zw.co.payport.gateway.ledger.LedgerClient;
import zw.co.payport.gateway.markets.MarketRegistry;
import zw.co.payport.gateway.risk.UnSanctionsList;
import zw.co.payport.gateway.service.EmailService;
import zw.co.payport.gateway.service.SmsNotificationService;
import zw.co.payport.gateway.wallets.WalletService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 10 operational readiness, on MySQL 8 with Hibernate in {@code validate} mode (the schema Flyway
 * builds must satisfy every entity: evidence for defect D10-1, ddl-auto=update in production config).
 *  1. On a fresh database every unapproved line, channel, method and product is off and settlement is
 *     in PARALLEL (draft/approval/comparison) mode with automation off.
 *  2. Provider settlement on representative bank statements: partial settlement, the same bank line on
 *     two statements, exact re-import, reversal, unknown credit, over-settlement, a changed amount for a
 *     booked bank reference; the positions comparison view.
 *  3. Customer and merchant isolation, refund and export permissions, API-key scope, over HTTP.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperationalReadinessScenarioTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("DB_URL", MYSQL::getJdbcUrl);
        r.add("DB_USERNAME", MYSQL::getUsername);
        r.add("DB_PASSWORD", MYSQL::getPassword);
        r.add("JWT_SECRET", () -> "phase10-ops-secret-phase10-ops-secret-0123456789abcdef");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("payport.payments.resolver.enabled", () -> "false");
        r.add("payport.payments.watchdog.enabled", () -> "false");
        r.add("payport.fulfilment.worker.enabled", () -> "false");
        r.add("payport.push.enabled", () -> "false");
        zw.co.payport.gateway.support.UnListStub.register(r);
    }

    @MockitoBean SmsNotificationService sms;
    @MockitoBean EmailService email;

    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired UserDetailsService users;
    @Autowired MarketRegistry markets;
    @Autowired UnSanctionsList unList;
    @Autowired WalletService wallets;
    @Autowired LedgerClient ledger;
    @Autowired BankMessageService bank;
    @Autowired ProviderSettlementService providerSettlements;
    @Autowired Environment env;
    @Value("${local.server.port}") int port;

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000));
    static final String PIN = "7391";
    static final String PIN_HASH = new BCryptPasswordEncoder().encode(PIN);
    static final String ACCOUNT = "ZW-SPONSOR-USD-0001";

    record Res(int status, JsonNode body, String raw) {
        JsonNode data() { return body.path("data"); }
    }

    @BeforeEach
    void fresh() {
        zw.co.payport.gateway.support.UnListStub.STATUS.set(200);
        unList.refresh("test");
        markets.reload();
    }

    Res call(String method, String path, String bearer, Object body) throws Exception {
        String raw = body == null ? null : JSON.writeValueAsString(body);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        b.method(method, raw == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(raw));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        String t = r.body() == null ? "" : r.body();
        JsonNode n;
        try { n = t.isBlank() ? JSON.createObjectNode() : JSON.readTree(t); } catch (Exception e) { n = JSON.createObjectNode().put("text", t); }
        return new Res(r.statusCode(), n, t);
    }

    String mobile() {
        return String.format("+26377%07d", SEQ.incrementAndGet() % 10_000_000);
    }

    String staff(String role) {
        String phone = mobile();
        jdbc.update("INSERT INTO users (full_name, phone, pin_hash, role, is_active, is_verified, email, must_change_password) VALUES (?, ?, 'x', ?, TRUE, TRUE, ?, FALSE)",
                role + " Staff", phone, role, role.toLowerCase() + SEQ.incrementAndGet() + "@payport.test");
        return jwt.generateToken(users.loadUserByUsername(phone));
    }

    record Customer(long id, String phone, String token) {}

    Customer customer() {
        String phone = mobile();
        jdbc.update("INSERT INTO users (full_name, phone, pin_hash, role, is_active, is_verified) VALUES ('Customer', ?, ?, 'CUSTOMER', TRUE, TRUE)", phone, PIN_HASH);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE phone = ?", Long.class, phone);
        return new Customer(id, phone, jwt.generateToken(users.loadUserByUsername(phone)));
    }

    record MerchantLogin(long id, String token) {}

    MerchantLogin merchant(String name) throws Exception {
        int n = SEQ.incrementAndGet();
        String emailAddr = "owner" + n + "@phase10.test";
        Res signup = call("POST", "/api/v1/merchant-portal/signup", null, Map.of("businessName", name + " " + n, "registrationNumber", "REG-P10-" + n,
                "businessCategory", "RETAIL", "contactEmail", emailAddr, "contactPhone", "+263772" + String.format("%06d", n % 1_000_000),
                "address", "12 Samora Machel Ave, Harare", "ownerFullName", "Owner " + n, "password", "Str0ng!Pass"));
        assertEquals(201, signup.status(), signup.raw());
        long id = signup.data().path("id").asLong();
        jdbc.update("UPDATE merchants SET status = 'ACTIVE' WHERE id = ?", id);
        jdbc.update("INSERT INTO merchant_enablements (merchant_id, country, currency, status, enabled_by, enabled_at) VALUES (?, 'ZW', 'USD', 'ENABLED', 'test', NOW(6))", id);
        String token = call("POST", "/api/v1/merchant-portal/login", null, Map.of("email", emailAddr, "password", "Str0ng!Pass")).data().path("token").asText();
        assertFalse(token.isBlank());
        return new MerchantLogin(id, token);
    }

    void lineOn(String line) {
        jdbc.update("UPDATE service_lines SET licence_status = 'NOT_REQUIRED', decided_by = 'test / test', decided_at = NOW(6), enabled = TRUE WHERE code = ?", line);
    }

    /** Refused before anything runs: 401 (not a staff login at all) or 403 (wrong role). */
    static void assertRefused(Res r, String what) {
        assertTrue(r.status() == 401 || r.status() == 403, what + " must be refused, got " + r.status() + " " + r.raw());
    }

    String status(String reference) {
        return jdbc.queryForObject("SELECT status FROM transactions WHERE reference = ?", String.class, reference);
    }

    // ─── 1. everything unapproved ships off ─────────────────────────────────

    @Test
    @Order(1)
    void freshDatabase_everyUnapprovedOperationIsOff_settlementInParallelMode() throws Exception {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM service_lines WHERE enabled = TRUE", Integer.class), "no product line is on until the owner switches it on");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM platform_channels WHERE licence_gate IS NOT NULL AND enabled = TRUE", Integer.class),
                "no licence-gated channel is on");
        assertFalse(jdbc.queryForObject("SELECT is_active FROM payment_methods WHERE code = 'WALLET'", Boolean.class));
        assertFalse(jdbc.queryForObject("SELECT is_active FROM product_types WHERE code = 'WALLET_TOPUP'", Boolean.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM fx_rates WHERE status = 'ACTIVE'", Integer.class), "no FX rate until finance approves one (four-eyes)");
        assertEquals("PARALLEL", env.getProperty("payport.settlement.mode"));
        assertEquals("false", env.getProperty("payport.settlement.automation.enabled"));
        assertTrue(env.getProperty("payport.settlement.require-reconciled", Boolean.class, false));

        Customer c = customer();
        assertEquals(503, call("GET", "/api/v1/fx/quote?from=USD&to=ZWG&amountMinor=1000", null, null).status(), "FX quotes off");
        assertEquals(503, call("POST", "/api/v1/wallet/open", c.token(), Map.of("currency", "USD")).status(), "wallets off");
        Res topUp = call("POST", "/api/v1/transactions/initiate", null, Map.of("reference", String.format("PP-WEB-20261022-%06d", SEQ.incrementAndGet() % 1_000_000),
                "productTypeCode", "WALLET_TOPUP", "recipientIdentifier", "W00000000000", "amountCents", 500, "currency", "USD", "customerPhone", c.phone(), "channel", "WEB"));
        assertTrue(topUp.status() >= 400 && topUp.status() < 500, "an inactive product cannot be bought: " + topUp.raw());
    }

    // ─── 2. provider settlement on the bank statement ───────────────────────

    record Line(String dir, long minor, String bankRef, String party, String status) {}

    static String statement(String id, String bookingDate, long openingMinor, Line... lines) {
        long credits = 0, debits = 0;
        StringBuilder entries = new StringBuilder();
        for (Line l : lines) {
            if ("BOOK".equals(l.status())) {
                if ("CRDT".equals(l.dir())) credits += l.minor(); else debits += l.minor();
            }
            String role = "CRDT".equals(l.dir()) ? "Dbtr" : "Cdtr";
            entries.append("<Ntry><Amt Ccy=\"USD\">").append(major(l.minor())).append("</Amt><CdtDbtInd>").append(l.dir()).append("</CdtDbtInd>")
                    .append("<Sts><Cd>").append(l.status()).append("</Cd></Sts><BookgDt><Dt>").append(bookingDate).append("</Dt></BookgDt>")
                    .append("<ValDt><Dt>").append(bookingDate).append("</Dt></ValDt><AcctSvcrRef>").append(l.bankRef()).append("</AcctSvcrRef>")
                    .append("<BkTxCd><Domn><Cd>PMNT</Cd><Fmly><Cd>").append("CRDT".equals(l.dir()) ? "RCDT" : "ICDT").append("</Cd><SubFmlyCd>DMCT</SubFmlyCd></Fmly></Domn></BkTxCd>")
                    .append("<NtryDtls><TxDtls><Refs><EndToEndId>NOTPROVIDED</EndToEndId></Refs><RltdPties><").append(role).append("><Pty><Nm>")
                    .append(l.party()).append("</Nm></Pty></").append(role).append("></RltdPties><RmtInf><Ustrd>Collections sweep</Ustrd></RmtInf></TxDtls></NtryDtls></Ntry>");
        }
        long closing = openingMinor + credits - debits;
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><!-- Fictitious statement for tests. -->"
                + "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.08\"><BkToCstmrStmt>"
                + "<GrpHdr><MsgId>MSG-" + id + "</MsgId><CreDtTm>" + bookingDate + "T18:00:00</CreDtTm></GrpHdr><Stmt><Id>" + id + "</Id>"
                + "<CreDtTm>" + bookingDate + "T18:00:00</CreDtTm><FrToDt><FrDtTm>" + bookingDate + "T00:00:00</FrDtTm><ToDtTm>" + bookingDate + "T23:59:59</ToDtTm></FrToDt>"
                + "<Acct><Id><Othr><Id>" + ACCOUNT + "</Id></Othr></Id><Ccy>USD</Ccy></Acct>"
                + "<Bal><Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp><Amt Ccy=\"USD\">" + major(openingMinor) + "</Amt><CdtDbtInd>CRDT</CdtDbtInd><Dt><Dt>" + bookingDate + "</Dt></Dt></Bal>"
                + "<Bal><Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp><Amt Ccy=\"USD\">" + major(closing) + "</Amt><CdtDbtInd>CRDT</CdtDbtInd><Dt><Dt>" + bookingDate + "</Dt></Dt></Bal>"
                + entries + "</Stmt></BkToCstmrStmt></Document>";
    }

    static String major(long minor) {
        return String.format("%d.%02d", minor / 100, minor % 100);
    }

    @SuppressWarnings("unchecked")
    Map<String, Integer> importTrace(String xml) {
        Map<String, Object> out = bank.importStatement(xml.getBytes(StandardCharsets.UTF_8), "fin.importer");
        assertEquals(false, out.get("alreadyImported"));
        return (Map<String, Integer>) ((List<Map<String, Object>>) out.get("statements")).get(0).get("trace");
    }

    Map<String, Object> ecocashPosition() throws Exception {
        String fin = staff("FINANCE");
        Res r = call("GET", "/api/v1/admin/provider-settlement-rules/positions", fin, null);
        assertEquals(200, r.status(), r.raw());
        for (JsonNode p : r.data()) {
            if ("ECOCASH".equals(p.path("provider_code").asText()) && "USD".equals(p.path("currency").asText())) {
                return Map.of("outstanding", p.path("outstanding_minor").asLong(), "collected", p.path("collected_minor").asLong(),
                        "settled", p.path("settled_minor").asLong(), "position", p.path("position").asText());
            }
        }
        fail("no ECOCASH USD position");
        return null;
    }

    long exceptionsFor(String bankRef) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM recon_exceptions WHERE provider_reference = ? AND status = 'OPEN'", Long.class, bankRef);
    }

    @Test
    @Order(2)
    void providerSettlement_partialDuplicateReversalUnknownAndOverSettlement_allVisible_nothingDoubleBooked() throws Exception {
        jdbc.update("UPDATE market_currencies SET bank_account_number = ? WHERE currency = 'USD'", ACCOUNT);
        markets.reload();
        Map<String, Object> rule = providerSettlements.propose("ECOCASH", "USD", "wallet operator", null, "fin.a");
        providerSettlements.decide(((Number) rule.get("id")).longValue(), true, "fin.b");

        // 1,000.00 collected on EcoCash (wallet top-ups book Dr PROVIDER_RECEIVABLE[ECOCASH]).
        lineOn("WALLETS");
        jdbc.update("UPDATE product_types SET is_active = TRUE WHERE code = 'WALLET_TOPUP'");
        Customer c = customer();
        WalletService.Wallet w = wallets.open(c.id(), c.phone(), "USD", "test");
        wallets.setTier(w.ref(), "TIER3", "KYC evidence on file", "staff:comp");
        for (int i = 0; i < 4; i++) wallets.creditTopUp("PP-WEB-20261022-9" + String.format("%05d", SEQ.incrementAndGet() % 100_000), w.ref(), "USD", 25_000, "ECOCASH", "test");
        assertEquals(Map.of("outstanding", 100_000L, "collected", 100_000L, "settled", 0L, "position", "OUTSTANDING"), ecocashPosition());

        // Intraday statement: a partial settlement of 400.00, a reversal, an unknown credit and a pending line.
        String s1 = statement("STMT-P10-INTRADAY", "2026-10-22", 1_000_000,
                new Line("CRDT", 40_000, "BREF-1", "Wallet operator settlement", "BOOK"),
                new Line("DBIT", 2_000, "BREF-R", "Wallet operator settlement", "BOOK"),
                new Line("CRDT", 300, "BREF-U", "Mystery Trading", "BOOK"),
                new Line("DBIT", 500, "BREF-P", "Bank charges", "PDNG"));
        assertEquals(Map.of("PROVIDER_SETTLEMENT", 1, "UNIDENTIFIED", 2, "NOT_BOOKED", 1), importTrace(s1));
        assertEquals(Map.of("outstanding", 60_000L, "collected", 100_000L, "settled", 40_000L, "position", "OUTSTANDING"), ecocashPosition(),
                "partial settlement leaves 600.00 outstanding");
        assertEquals(1, exceptionsFor("BREF-R"), "a reversal (debit) is never booked automatically: it is an exception");
        assertEquals(1, exceptionsFor("BREF-U"), "an unknown credit is an exception");

        // The same file again: recognised, nothing new.
        Map<String, Object> again = bank.importStatement(s1.getBytes(StandardCharsets.UTF_8), "fin.importer");
        assertEquals(true, again.get("alreadyImported"));
        // A different file reusing the statement id is refused.
        assertThrows(IllegalStateException.class, () -> bank.importStatement(statement("STMT-P10-INTRADAY", "2026-10-22", 1_000_000,
                new Line("CRDT", 100, "BREF-X", "Wallet operator settlement", "BOOK")).getBytes(StandardCharsets.UTF_8), "fin.importer"));

        // End-of-day statement repeats the intraday line (same bank reference) and adds the rest.
        String s2 = statement("STMT-P10-EOD", "2026-10-22", 1_000_000,
                new Line("CRDT", 40_000, "BREF-1", "Wallet operator settlement", "BOOK"),
                new Line("CRDT", 60_000, "BREF-2", "Wallet operator settlement", "BOOK"));
        assertEquals(Map.of("PROVIDER_SETTLEMENT", 2), importTrace(s2));
        assertEquals(Map.of("outstanding", 0L, "collected", 100_000L, "settled", 100_000L, "position", "SETTLED"), ecocashPosition(),
                "the repeated line was not booked twice");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries WHERE idempotency_key = ?", Integer.class,
                "PROVIDER_SETTLEMENT:" + ACCOUNT + ":BANKREF:BREF-1"));
        assertTrue(jdbc.queryForObject("SELECT detail FROM bank_statement_entries e JOIN bank_statements s ON s.id = e.statement_id "
                + "WHERE s.statement_id = 'STMT-P10-EOD' AND e.bank_reference = 'BREF-1'", String.class).contains("already booked"));

        // More than was collected: visible as over-settled for investigation.
        importTrace(statement("STMT-P10-D2", "2026-10-23", 1_098_000, new Line("CRDT", 5_000, "BREF-3", "Wallet operator settlement", "BOOK")));
        assertEquals("OVER_SETTLED", ecocashPosition().get("position"));
        assertEquals(-5_000L, ecocashPosition().get("outstanding"));

        // The bank later shows the same reference with a different amount: not booked, raised as an exception.
        assertEquals(Map.of("LEDGER_PENDING", 1), importTrace(statement("STMT-P10-D3", "2026-10-22", 1_103_000,
                new Line("CRDT", 41_000, "BREF-1", "Wallet operator settlement", "BOOK"))));
        assertEquals(1, exceptionsFor("BREF-1"));
        assertEquals(-5_000L, ecocashPosition().get("outstanding"), "nothing booked for the conflicting line");

        // Settlement stays in comparison mode: nothing was instructed, nothing paid out.
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM stl_batches WHERE instructed_at IS NOT NULL", Integer.class));
        assertTrue(ledger.integrityReport().isEmpty(), ledger.integrityReport().toString());

        // Who can see the comparison view.
        for (String role : List.of("OPS", "CUSTOMER")) {
            String t = "CUSTOMER".equals(role) ? customer().token() : staff(role);
            assertEquals(403, call("GET", "/api/v1/admin/provider-settlement-rules/positions", t, null).status(), role);
        }
        assertRefused(call("GET", "/api/v1/admin/provider-settlement-rules/positions", merchant("Nosy").token(), null), "merchant");
        int anonymous = call("GET", "/api/v1/admin/provider-settlement-rules/positions", null, null).status();
        assertTrue(anonymous == 401 || anonymous == 403, "anonymous refused: " + anonymous);
    }

    // ─── 3. isolation and permissions ───────────────────────────────────────

    @Test
    @Order(3)
    void customersAndMerchantsCannotReachEachOthersData_andOnlyFinanceRefunds() throws Exception {
        lineOn("WALLETS");
        lineOn("POS_ACQUIRING");
        jdbc.update("UPDATE payment_methods SET is_active = TRUE WHERE code = 'WALLET'");
        Customer ann = customer(), bob = customer();
        WalletService.Wallet annW = wallets.open(ann.id(), ann.phone(), "USD", "test");
        WalletService.Wallet bobW = wallets.open(bob.id(), bob.phone(), "USD", "test");
        wallets.creditTopUp("PP-WEB-20261022-8" + String.format("%05d", SEQ.incrementAndGet() % 100_000), annW.ref(), "USD", 5_000, "ONEMONEY", "test");
        wallets.creditTopUp("PP-WEB-20261022-8" + String.format("%05d", SEQ.incrementAndGet() % 100_000), bobW.ref(), "USD", 5_000, "ONEMONEY", "test");

        // Bob cannot read Ann's wallet statement.
        Res stmt = call("GET", "/api/v1/wallet/" + annW.ref() + "/statement", bob.token(), null);
        assertEquals(400, stmt.status(), stmt.raw());
        assertFalse(stmt.raw().contains("TOPUP"));
        assertEquals(200, call("GET", "/api/v1/wallet/" + annW.ref() + "/statement", ann.token(), null).status());

        // Ann's payment: Bob can neither pay it from his wallet nor see her details.
        String ref = String.format("PP-WEB-20261022-%06d", SEQ.incrementAndGet() % 1_000_000);
        Res init = call("POST", "/api/v1/transactions/initiate", null, Map.of("reference", ref, "productTypeCode", "AIRTIME_ECONET", "recipientIdentifier", "0771234567",
                "amountCents", 500, "currency", "USD", "customerPhone", ann.phone(), "channel", "WEB"));
        assertEquals(201, init.status(), init.raw());
        Res bobPays = call("POST", "/api/v1/wallet/pay", bob.token(), Map.of("reference", ref, "pin", PIN));
        assertEquals(400, bobPays.status(), bobPays.raw());
        assertEquals(5_000, wallets.balance(bobW));
        assertEquals("INITIATED", status(ref));
        Res peek = call("GET", "/api/v1/transactions/" + ref, bob.token(), null);
        assertFalse(peek.raw().contains(ann.phone().substring(4)), "another customer never sees the payer's phone: " + peek.raw());

        // Merchants: B cannot disable A's terminal or read A's checkout session.
        MerchantLogin a = merchant("Alpha"), b = merchant("Bravo");
        Res term = call("POST", "/api/v1/merchant/pos/terminals", a.token(), Map.of("label", "Till 1"));
        assertEquals(200, term.status(), term.raw());
        String tid = term.data().path("terminalId").asText();
        assertEquals(400, call("POST", "/api/v1/merchant/pos/terminals/" + tid + "/status", b.token(), Map.of("active", false, "reason", "mine now")).status());
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM pos_terminals WHERE terminal_id = ?", String.class, tid));
        assertFalse(call("GET", "/api/v1/merchant/pos/terminals", b.token(), null).raw().contains(tid));
        String orderRef = "ORD-P10-" + SEQ.incrementAndGet();
        jdbc.update("INSERT INTO checkout_sessions (merchant_id, reference, amount_cents, currency, status, expires_at, created_at, updated_at, livemode) "
                + "VALUES (?, ?, 700, 'USD', 'CREATED', ?, NOW(), NOW(), TRUE)", a.id(), orderRef, LocalDateTime.now().plusMinutes(30));
        long sid = jdbc.queryForObject("SELECT id FROM checkout_sessions WHERE reference = ?", Long.class, orderRef);
        assertEquals(200, call("GET", "/api/v1/merchant/checkout-sessions/" + sid, a.token(), null).status());
        assertEquals(404, call("GET", "/api/v1/merchant/checkout-sessions/" + sid, b.token(), null).status());
        assertEquals(404, call("GET", "/api/v1/merchant/checkout-sessions/by-reference/" + orderRef, b.token(), null).status());

        // A merchant API key is for the checkout API only: never the POS management or dashboard routes.
        Res key = call("POST", "/api/v1/merchant/api-keys", a.token(), Map.of("environment", "TEST", "label", "server", "scopes", List.of("checkout:read", "checkout:write")));
        assertEquals(201, key.status(), key.raw());
        String sk = key.data().path("secretKey").asText();
        for (String path : List.of("/api/v1/merchant/pos/terminals", "/api/v1/merchant/api-keys")) {
            assertEquals(403, call("GET", path, sk, null).status(), "API key on " + path);
        }

        // Roles on the wrong side of the fence.
        assertEquals(403, call("GET", "/api/v1/merchant/checkout-sessions/" + sid, ann.token(), null).status(), "customer on a merchant route");
        assertRefused(call("GET", "/api/v1/admin/transactions-export/csv", a.token(), null), "merchant cannot export");
        assertEquals(403, call("GET", "/api/v1/admin/transactions-export/csv", ann.token(), null).status(), "customer cannot export");
        assertEquals(200, call("GET", "/api/v1/admin/transactions-export/csv", staff("FINANCE"), null).status(), "finance can export");

        // Refunds: only finance and administrators refund to a wallet; customers, merchants, ops and compliance cannot.
        Res paid = call("POST", "/api/v1/wallet/pay", ann.token(), Map.of("reference", ref, "pin", PIN));
        assertEquals(200, paid.status(), paid.raw());
        jdbc.update("UPDATE transactions SET status = 'FULFILMENT_FAILED' WHERE reference = ?", ref);
        for (String t : List.of(ann.token(), a.token(), staff("OPS"), staff("COMPLIANCE"))) {
            assertRefused(call("POST", "/api/v1/admin/wallets/refunds/" + ref, t, Map.of("reason", "x")), "wallet refund");
        }
        for (String t : List.of(ann.token(), a.token(), staff("COMPLIANCE"))) {
            assertRefused(call("POST", "/api/v1/admin/transactions/" + ref + "/refund", t, Map.of("reason", "x")), "payment refund");
        }
        assertEquals("FULFILMENT_FAILED", status(ref), "nothing changed");
        assertEquals(4_500, wallets.balance(annW));
        Res refund = call("POST", "/api/v1/admin/wallets/refunds/" + ref, staff("FINANCE"), Map.of("reason", "vendor failed"));
        assertEquals(200, refund.status(), refund.raw());
        assertEquals(5_000, wallets.balance(annW));
        assertEquals(Set.of(), Set.copyOf(ledger.integrityReport()));
    }
}
