package zw.co.payport.gateway.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import zw.co.payport.gateway.config.JwtService;
import zw.co.payport.gateway.markets.MarketRegistry;
import zw.co.payport.gateway.products.EmvQr;
import zw.co.payport.gateway.risk.UnSanctionsList;
import zw.co.payport.gateway.service.CheckoutSessionService;
import zw.co.payport.gateway.service.EmailService;
import zw.co.payport.gateway.service.SmsNotificationService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The owner's controls from the portal, over HTTP on MySQL 8:
 *  - Zambia switched from SANDBOX to LIVE without code: ADMIN requests, a COMPLIANCE officer approves
 *    (not the requester, not another admin), readiness blockers first; then live ZMW payments work;
 *    suspending is immediate;
 *  - currencies switched off immediately and back on through approval;
 *  - channels and products switched off and on, enforced on the payment path, audited;
 *  - an incomplete screening is never reported as clear;
 *  - Phase 8: licence-gated product lines, QR, FX quotes, USSD.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformControlsScenarioTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0");

    static final String USSD_SECRET = "ussd-test-secret";
    static final FakeCommerce COMMERCE;

    static {
        try {
            COMMERCE = new FakeCommerce();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("DB_URL", MYSQL::getJdbcUrl);
        r.add("DB_USERNAME", MYSQL::getUsername);
        r.add("DB_PASSWORD", MYSQL::getPassword);
        r.add("JWT_SECRET", () -> "controls-test-secret-controls-test-secret-0123456789abcdef");
        r.add("payport.payments.resolver.enabled", () -> "false");
        r.add("payport.payments.watchdog.enabled", () -> "false");
        r.add("payport.fulfilment.worker.enabled", () -> "false");
        r.add("payport.push.enabled", () -> "false");
        r.add("payport.ussd.secret", () -> USSD_SECRET);
        r.add("payport.commerce.url", COMMERCE::url);
        r.add("payport.commerce.key", () -> FakeCommerce.KEY);
        zw.co.payport.gateway.support.UnListStub.register(r);
    }

    @MockitoBean SmsNotificationService sms;
    @MockitoBean EmailService email;

    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired UserDetailsService users;
    @Autowired MarketRegistry markets;
    @Autowired CheckoutSessionService checkout;
    @Autowired UnSanctionsList unList;
    @Value("${local.server.port}") int port;

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000));

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
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        String t = r.body();
        JsonNode n;
        try { n = t == null || t.isBlank() ? JSON.createObjectNode() : JSON.readTree(t); } catch (Exception e) { n = JSON.createObjectNode().put("text", t); }
        return new Res(r.statusCode(), n);
    }

    String phone() {
        return String.format("+26371%07d", SEQ.incrementAndGet());
    }

    String staff(String name, String role) {
        String phone = phone();
        jdbc.update("INSERT INTO users (full_name, phone, pin_hash, role, is_active, is_verified, email, must_change_password) VALUES (?, ?, 'x', ?, TRUE, TRUE, ?, FALSE)",
                name, phone, role, name.replace(' ', '.').toLowerCase() + SEQ.incrementAndGet() + "@payport.test");
        return jwt.generateToken(users.loadUserByUsername(phone));
    }

    Res initiate(String channel, String product, String currency, long cents) throws Exception {
        String ref = String.format("PP-%s-20261016-%06d", channel.length() >= 2 ? channel : "XX", SEQ.incrementAndGet() % 1_000_000);
        return call("POST", "/api/v1/transactions/initiate", null, Map.of("reference", ref, "productTypeCode", product, "recipientIdentifier", "0771234567",
                "amountCents", cents, "currency", currency, "customerPhone", "+263771234567", "channel", channel));
    }

    long activeMerchant(String name) {
        jdbc.update("INSERT INTO merchants (business_name, registration_number, contact_email, contact_phone, status, default_currency) "
                + "VALUES (?, ?, ?, '+263770000000', 'ACTIVE', 'USD')", name, "REG-" + name + SEQ.incrementAndGet(), "m" + SEQ.get() + "@example.test");
        return jdbc.queryForObject("SELECT MAX(id) FROM merchants", Long.class);
    }

    // ─── markets and currencies ──────────────────────────────────────────────

    @Test
    void zambiaGoesLiveFromThePortal_withoutCode_compliancesApprovesAndSuspendIsImmediate() throws Exception {
        String adam = staff("Adam Markets", "ADMIN"), alice = staff("Alice Admin", "ADMIN"), carol = staff("Carol Markets", "COMPLIANCE"),
                olivia = staff("Olivia Markets", "OPS");
        JsonNode zm = null;
        for (JsonNode m : call("GET", "/api/v1/admin/markets/settings", olivia, null).data()) if ("ZM".equals(m.path("country").asText())) zm = m;
        assertNotNull(zm);
        assertEquals("SANDBOX", zm.path("status").asText());
        assertFalse(zm.path("readiness").path("readyForLive").asBoolean());
        assertFalse(markets.liveCurrency("ZMW"));

        // Going live without a live currency is refused, with the reason.
        Res blocked = call("POST", "/api/v1/admin/markets/ZM/change-requests", adam, Map.of("changes", Map.of("status", "LIVE"), "reason", "launch"));
        assertEquals(400, blocked.status());
        assertTrue(blocked.message().contains("no currency is marked live"), blocked.message());

        // A complete request: legal name, real bank account, ZMW live.
        Res req = call("POST", "/api/v1/admin/markets/ZM/change-requests", adam, Map.of(
                "changes", Map.of("status", "LIVE", "legalEntityName", "Payport Zambia Limited", "bankName", "Example Bank Zambia",
                        "bankBic", "EXBKZMLU", "bankFormatStatus", "CONFIRMED by the bank 2026-10-16", "statusNote", "Licensed"),
                "currencies", List.of(Map.of("currency", "ZMW", "live", true, "bankAccountNumber", "0012345678901", "bankAccountName", "Payport Zambia settlement")),
                "reason", "Bank of Zambia licence granted"));
        assertEquals(200, req.status(), req.body().toString());
        assertEquals("COMPLIANCE", req.data().path("requiredRole").asText(), "enabling money needs compliance");
        long id = req.data().path("approvalId").asLong();
        assertFalse(markets.liveCurrency("ZMW"), "nothing changes until approved");

        assertEquals(409, call("POST", "/api/v1/admin/markets/requests/" + id + "/decide", adam, Map.of("approve", true)).status(), "not your own request");
        Res byAdmin = call("POST", "/api/v1/admin/markets/requests/" + id + "/decide", alice, Map.of("approve", true));
        assertEquals(409, byAdmin.status(), "another admin is not enough to enable money");
        assertEquals("APPROVAL_ROLE", byAdmin.code());
        Res ok = call("POST", "/api/v1/admin/markets/requests/" + id + "/decide", carol, Map.of("approve", true, "note", "licence checked"));
        assertEquals(200, ok.status(), ok.body().toString());

        markets.reload();
        assertTrue(markets.liveCurrency("ZMW"), "Zambia takes live money now: no code change, no deployment");
        assertTrue(markets.validMobileInAnyMarket("+260961234567"), "Zambian customers' numbers are accepted from the market settings");
        assertFalse(markets.validMobileInAnyMarket("+260111234567"), "but only real mobile ranges");
        assertTrue(jdbc.queryForObject("SELECT enabled FROM ref_countries WHERE code = 'ZM'", Boolean.class));
        assertTrue(jdbc.queryForObject("SELECT enabled FROM ref_currencies WHERE code = 'ZMW'", Boolean.class), "the ledger accepts ZMW");
        assertEquals("Payport Zambia Limited", jdbc.queryForObject("SELECT legal_name FROM ref_legal_entities WHERE code = 'PAYPORT_ZM_SANDBOX'", String.class));
        assertEquals(List.of(), markets.problems().getOrDefault("ZM", List.of()));

        // A merchant can now be enabled for live ZMW, and live checkout in ZMW is allowed.
        long m = activeMerchant("Lusaka Traders");
        assertEquals(200, call("POST", "/api/v1/admin/platform/merchants/" + m + "/enablements", olivia,
                Map.of("country", "ZM", "currency", "ZMW", "enabled", true)).status());
        assertDoesNotThrow(() -> checkout.requireLiveCurrency(m, "ZMW"));

        // Switching off is immediate, by one person; a reason is optional.
        assertEquals(200, call("POST", "/api/v1/admin/markets/ZM/suspend", olivia, Map.of("reason", "incident drill")).status());
        markets.reload();
        assertFalse(markets.liveCurrency("ZMW"));
        assertThrows(CheckoutSessionService.CurrencyNotEnabledException.class, () -> checkout.requireLiveCurrency(m, "ZMW"));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM compliance_audit_log WHERE action IN ('MARKET_CHANGE_REQUESTED','MARKET_CHANGE_APPROVED','MARKET_SUSPENDED') "
                + "AND subject = 'market:ZM'", Integer.class) >= 3);
    }

    @Test
    void currenciesOff_immediately_andBackOn_throughApproval() throws Exception {
        String fiona = staff("Fiona Currencies", "FINANCE"), adam = staff("Adam Currencies", "ADMIN"), carol = staff("Carol Currencies", "COMPLIANCE");
        assertNotEquals(503, initiate("WEB", "AIRTIME_ECONET", "ZWG", 500).status());
        assertEquals(200, call("POST", "/api/v1/admin/markets/currencies/ZWG/disable", fiona, Map.of("reason", "ZiG liquidity incident")).status());
        markets.reload();
        Res refused = initiate("WEB", "AIRTIME_ECONET", "ZWG", 500);
        assertTrue(List.of(400, 422).contains(refused.status()), refused.body().toString());
        assertTrue(refused.message().contains("not available"), refused.message());
        assertFalse(markets.supportedCurrency("ZWG"));
        assertThrows(RuntimeException.class, () -> checkout.requireLiveCurrency(1L, "ZWG"));

        Res back = call("POST", "/api/v1/admin/markets/ZW/change-requests", adam,
                Map.of("currencies", List.of(Map.of("currency", "ZWG", "enabled", true, "live", true)), "reason", "incident over"));
        assertEquals("COMPLIANCE", back.data().path("requiredRole").asText());
        assertEquals(200, call("POST", "/api/v1/admin/markets/requests/" + back.data().path("approvalId").asLong() + "/decide", carol, Map.of("approve", true)).status());
        markets.reload();
        assertTrue(markets.liveCurrency("ZWG"));
    }

    // ─── channels and products ───────────────────────────────────────────────

    @Test
    void channelsAndProducts_switchedFromThePortal_enforcedAndAudited() throws Exception {
        String olivia = staff("Olivia Channels", "OPS"), adam = staff("Adam Channels", "ADMIN");
        assertEquals(200, call("POST", "/api/v1/admin/channels/WEB", olivia, Map.of("enabled", false, "reason", "web app incident")).status());
        Res off = initiate("WEB", "AIRTIME_ECONET", "USD", 500);
        assertEquals(503, off.status());
        assertEquals("CHANNEL_UNAVAILABLE", off.code());
        assertTrue(off.message().contains("No money was taken"));
        assertNotEquals(503, initiate("MOB", "AIRTIME_ECONET", "USD", 500).status(), "other channels keep working");
        assertEquals(200, call("POST", "/api/v1/admin/channels/WEB", olivia, Map.of("enabled", true, "reason", "fixed")).status());
        assertNotEquals(503, initiate("WEB", "AIRTIME_ECONET", "USD", 500).status());
        assertTrue(List.of(400, 422).contains(initiate("FAX", "AIRTIME_ECONET", "USD", 500).status()), "unknown channels are refused");

        // Any channel is switched on and off directly by an administrator (no reason needed).
        assertEquals(200, call("POST", "/api/v1/admin/channels/QR", adam, Map.of("enabled", true)).status());
        assertEquals(200, call("POST", "/api/v1/admin/channels/QR", adam, Map.of("enabled", false)).status());

        // Product and product-currency switches (existing ops screens) are enforced and audited.
        long pid = jdbc.queryForObject("SELECT id FROM product_types WHERE code = 'AIRTIME_ECONET'", Long.class);
        assertEquals(200, call("PUT", "/api/v1/admin/config/products/" + pid + "/currencies/ZWG", adam, Map.of("isActive", false)).status());
        Res noZwg = initiate("WEB", "AIRTIME_ECONET", "ZWG", 500);
        assertTrue(List.of(400, 422).contains(noZwg.status()), noZwg.body().toString());
        assertTrue(noZwg.message().contains("not available in ZWG"), noZwg.message());
        assertEquals(200, call("PUT", "/api/v1/admin/config/products/" + pid + "/currencies/ZWG", adam, Map.of("isActive", true)).status());
        assertEquals(200, call("PUT", "/api/v1/admin/config/products/" + pid, adam, Map.of("isActive", false)).status());
        assertTrue(List.of(400, 422).contains(initiate("WEB", "AIRTIME_ECONET", "USD", 500).status()), "inactive product refused");
        assertEquals(200, call("PUT", "/api/v1/admin/config/products/" + pid, adam, Map.of("isActive", true)).status());
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM compliance_audit_log WHERE action IN ('CHANNEL_OFF','CHANNEL_ON','PRODUCT_UPDATED','PRODUCT_CURRENCY_UPDATED')",
                Integer.class) >= 6);
    }

    // ─── screening ───────────────────────────────────────────────────────────

    @Test
    void anIncompleteScreeningIsNeverReportedAsClear() throws Exception {
        String carol = staff("Carol Screening", "COMPLIANCE");
        Res check = call("POST", "/api/v1/admin/compliance/screening/check", carol, Map.of("names", List.of("Nobody Listed"), "identifiers", List.of()));
        assertEquals("CLEAR", check.data().path("outcome").asText(), check.body().toString());

        // Switching the required flag off does not make screening "clear": a market still requires the list.
        jdbc.update("UPDATE screening_lists SET required_for_clear = FALSE WHERE code = 'UN-CONSOLIDATED'");
        try {
            Res status = call("GET", "/api/v1/admin/compliance/screening/status", carol, null);
            assertFalse(status.data().path("complete").asBoolean());
            assertTrue(status.data().path("reason").asText().contains("no required screening list is active"), status.body().toString());
            Res incomplete = call("POST", "/api/v1/admin/compliance/screening/check", carol, Map.of("names", List.of("Nobody Listed"), "identifiers", List.of()));
            assertEquals("INCOMPLETE", incomplete.data().path("outcome").asText(), "the ad-hoc check shows the outcome, never just an empty match list");
            assertFalse(incomplete.data().path("incompleteReason").asText().isBlank());
        } finally {
            jdbc.update("UPDATE screening_lists SET required_for_clear = TRUE WHERE code = 'UN-CONSOLIDATED'");
        }
    }

    // ─── Phase 8 ─────────────────────────────────────────────────────────────

    @Test
    void phase8_productLines_switchedDirectly_qr_fx() throws Exception {
        String carol = staff("Carol Lines", "COMPLIANCE"), dave = staff("Dave Lines", "COMPLIANCE"), adam = staff("Adam Lines", "ADMIN"),
                fiona = staff("Fiona Fx", "FINANCE"), frank = staff("Frank Fx", "FINANCE");
        assertEquals(6, call("GET", "/api/v1/admin/service-lines", adam, null).data().size());
        // Lines ship off; an administrator switches them on directly. Customers and compliance cannot.
        assertEquals(403, call("POST", "/api/v1/admin/service-lines/QR_PAYMENTS", carol, Map.of("enabled", true)).status());
        assertEquals(200, call("POST", "/api/v1/admin/service-lines/QR_PAYMENTS", adam, Map.of("enabled", true)).status());
        assertEquals(200, call("POST", "/api/v1/admin/channels/QR", adam, Map.of("enabled", true, "reason", "launch QR")).status());

        // QR for an open checkout session; resolution checks it matches.
        long m = activeMerchant("QR Cafe");
        jdbc.update("INSERT INTO checkout_sessions (merchant_id, reference, amount_cents, currency, status, expires_at, created_at, updated_at, livemode) "
                + "VALUES (?, ?, 1234, 'USD', 'CREATED', ?, NOW(), NOW(), TRUE)", m, "ORD-QR-" + SEQ.incrementAndGet(), LocalDateTime.now().plusMinutes(30));
        long sid = jdbc.queryForObject("SELECT MAX(id) FROM checkout_sessions", Long.class);
        Res code = call("GET", "/api/v1/checkout/" + sid + "/qr", null, null);
        assertEquals(200, code.status(), code.body().toString());
        String payload = code.data().path("payload").asText();
        EmvQr.Payload p = EmvQr.parse(payload);
        assertEquals("840", p.currencyNumeric(), "ISO numeric from the market settings");
        assertEquals("ZW", p.countryCode());
        assertEquals(1234L, p.amountMinor());
        Res resolved = call("POST", "/api/v1/qr/resolve", null, Map.of("payload", payload));
        assertEquals(sid, resolved.data().path("sessionId").asLong());
        assertTrue(resolved.data().path("payable").asBoolean());
        char last = payload.charAt(payload.length() - 1);
        assertEquals("INVALID_QR", call("POST", "/api/v1/qr/resolve", null,
                Map.of("payload", payload.substring(0, payload.length() - 1) + (last == '0' ? '1' : '0'))).code(), "a changed checksum is refused");
        String cheaper = EmvQr.build(new EmvQr.Payload(true, p.merchantRef(), p.mcc(), p.currencyNumeric(), 1L, p.countryCode(), p.merchantName(), p.city(), p.reference()));
        assertEquals("QR_MISMATCH", call("POST", "/api/v1/qr/resolve", null, Map.of("payload", cheaper)).code(), "an altered amount is refused");

        // Switched off: the line and the channel that depends on it go off at once.
        assertEquals(200, call("POST", "/api/v1/admin/service-lines/QR_PAYMENTS", adam, Map.of("enabled", false)).status());
        assertEquals(503, call("GET", "/api/v1/checkout/" + sid + "/qr", null, null).status());
        assertFalse(jdbc.queryForObject("SELECT enabled FROM platform_channels WHERE code = 'QR'", Boolean.class));

        // FX: maker-checker rates; quotes only when the line is on.
        Res rate = call("POST", "/api/v1/admin/fx/rates", fiona, Map.of("base", "USD", "quote", "ZWG", "rate", 26.5, "spreadBps", 100, "source", "RBZ interbank 2026-10-16"));
        long rid = rate.data().path("id").asLong();
        assertEquals(409, call("POST", "/api/v1/admin/fx/rates/" + rid + "/decide", fiona, Map.of("approve", true)).status());
        assertEquals(200, call("POST", "/api/v1/admin/fx/rates/" + rid + "/decide", frank, Map.of("approve", true)).status());
        assertEquals(503, call("GET", "/api/v1/fx/quote?from=USD&to=ZWG&amountMinor=1000", null, null).status(), "FX line is off");
        assertEquals(200, call("POST", "/api/v1/admin/service-lines/FX_CONVERSION", adam, Map.of("enabled", true, "reason", "show quotes")).status());
        Res q = call("GET", "/api/v1/fx/quote?from=USD&to=ZWG&amountMinor=1000", null, null);
        assertEquals(26_235, q.data().path("convertedMinor").asLong(), "10.00 USD × 26.5 less 1% = 262.35 ZWG, rounded down");
        Res inv = call("GET", "/api/v1/fx/quote?from=ZWG&to=USD&amountMinor=26500", null, null);
        assertEquals(990, inv.data().path("convertedMinor").asLong(), "inverse rate, spread applied, rounded down");

        // A line whose code is not in this version cannot be switched on (all lines are BUILT since V52;
        // parking one here proves the guard still holds).
        jdbc.update("UPDATE service_lines SET harvest_status = 'PARKED' WHERE code = 'WALLETS'");
        Res parked = call("POST", "/api/v1/admin/service-lines/WALLETS", adam, Map.of("enabled", true, "reason", "try"));
        assertEquals(409, parked.status());
        assertTrue(parked.message().contains("not available in this version"));
        jdbc.update("UPDATE service_lines SET harvest_status = 'BUILT' WHERE code = 'WALLETS'");
        assertEquals(200, call("POST", "/api/v1/admin/service-lines/WALLETS", adam, Map.of("enabled", true, "reason", "built")).status());
    }

    // ─── USSD ────────────────────────────────────────────────────────────────

    Res ussd(String session, String phone, String text, boolean signed) throws Exception {
        String sig;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(USSD_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        sig = HexFormat.of().formatHex(mac.doFinal((session + "\n" + phone + "\n" + text).getBytes(StandardCharsets.UTF_8)));
        String form = "sessionId=" + URLEncoder.encode(session, StandardCharsets.UTF_8) + "&phoneNumber=" + URLEncoder.encode(phone, StandardCharsets.UTF_8)
                + "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/ussd")).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form));
        if (signed) b.header("X-PayPort-Ussd-Signature", sig);
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), JSON.createObjectNode().put("text", r.body()));
    }

    /** Owner, 31 Oct 2026: the store on USSD (and WhatsApp, the same menus): browse, order, pay on delivery, my orders. */
    @Test
    void ussdStore_browseOrderPayOnDelivery_myOrders() throws Exception {
        String s = "AT-SHOP-" + SEQ.incrementAndGet(), ph = String.format("+263778%06d", 600000 + SEQ.incrementAndGet());
        String menu = ussd(s, ph, "", true).body().path("text").asText();
        assertTrue(menu.contains("8. Shop"), menu);
        String shop = ussd(s, ph, "8", true).body().path("text").asText();
        assertTrue(shop.startsWith("CON Payport Store") && shop.contains("2. Food (1)") && shop.contains("3. My orders"), shop);
        String list = ussd(s, ph, "8*2", true).body().path("text").asText();
        assertTrue(list.contains("1. Mai's Kitchen"), list);
        String items = ussd(s, ph, "8*2*1", true).body().path("text").asText();
        assertTrue(items.contains("1. Sadza and beef USD 6.00"), items);
        String many = ussd(s, ph, "8*2*1*1", true).body().path("text").asText();
        assertTrue(many.contains("How many Sadza and beef?"), many);
        String chosen = ussd(s, ph, "8*2*1*1*2", true).body().path("text").asText();
        assertTrue(chosen.contains("1. Checkout"), chosen);
        assertTrue(ussd(s, ph, "8*2*1*1*2*1", true).body().path("text").asText().contains("Your name:"), "a guest gives a name");
        String disc = ussd(s, ph, "8*2*1*1*2*1*Tendai", true).body().path("text").asText();
        assertTrue(disc.contains("Discount code?"), disc);
        String pay = ussd(s, ph, "8*2*1*1*2*1*Tendai*1", true).body().path("text").asText();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\. Pay on delivery").matcher(pay);
        String all = pay;
        String path = "8*2*1*1*2*1*Tendai*1";
        while (!m.find()) {   // the list may run over more than one screen
            assertTrue(all.contains("0. More"), "pay on delivery offered: " + all);
            path += "*0";
            all = ussd(s, ph, path, true).body().path("text").asText();
            m = java.util.regex.Pattern.compile("(\\d+)\\. Pay on delivery").matcher(all);
        }
        path += "*" + m.group(1);
        String confirm = ussd(s, ph, path, true).body().path("text").asText();
        assertTrue(confirm.contains("2 x Sadza and beef") && confirm.contains("Total USD 12.00") && confirm.contains("1. Confirm"), confirm);
        String done = ussd(s, ph, path + "*1", true).body().path("text").asText();
        assertTrue(done.startsWith("END Order CO-TEST0001 placed") && done.contains("on delivery"), done);
        JsonNode placed = COMMERCE.orders.get(COMMERCE.orders.size() - 1);
        assertEquals("USSD", placed.path("channel").asText());
        assertEquals("CASH", placed.path("pay").asText());
        assertEquals("PICKUP", placed.path("method").asText());
        assertEquals("Tendai", placed.path("name").asText());
        assertEquals(ph, placed.path("phone").asText(), "the order is in this phone's name");
        assertEquals(2, placed.path("items").get(0).path("quantity").asInt());
        assertEquals(done, ussd(s, ph, path + "*1", true).body().path("text").asText(), "a repeated hop places nothing twice");
        assertEquals(1, COMMERCE.orders.size());
        String mine = ussd("AT-SHOP-" + SEQ.incrementAndGet(), ph, "8*3", true).body().path("text").asText();
        assertTrue(mine.startsWith("END Your orders:") && mine.contains("CO-TEST0001") && mine.contains("pay on delivery"), mine);
        // The app reads the same stores (browsing is open; ordering needs a login).
        Res apps = call("GET", "/api/v1/store/stores", null, null);
        assertEquals(200, apps.status(), apps.body().toString());
        assertTrue(apps.body().toString().contains("Mai's Kitchen"), apps.body().toString());
        int anon = call("POST", "/api/v1/store/orders", null, Map.of("store", "mais-kitchen")).status();
        assertTrue(anon == 401 || anon == 403, "ordering needs a login: " + anon);
    }

    @Test
    void ussdMenus_signed_channelSwitch_andStatusOnlyForThePayer() throws Exception {
        String s = "AT-" + SEQ.incrementAndGet(), ph = String.format("+263778%06d", 400000 + SEQ.incrementAndGet());
        assertEquals(401, ussd(s, ph, "", false).status(), "unsigned hops are refused");
        String menu = ussd(s, ph, "", true).body().path("text").asText();
        assertTrue(menu.startsWith("CON Payport") && menu.contains("1. Buy airtime") && menu.contains("5. Check a payment"), menu);
        assertTrue(ussd(s, ph, "1", true).body().path("text").asText().startsWith("CON Airtime for:"));
        String ccy = ussd(s, ph, "1*1", true).body().path("text").asText();
        assertTrue(ccy.startsWith("CON Econet Airtime\nCurrency:") && ccy.contains("USD"), "the product is named: " + ccy);
        assertTrue(ussd(s, ph, "1*1*1", true).body().path("text").asText().startsWith("CON Amount (USD)"));
        // Owner, 2026-10-31: a wrong amount is explained and asked again (change it, or 0 to cancel); the session goes on.
        String again = ussd(s, ph, "1*1*1*abc", true).body().path("text").asText();
        assertTrue(again.startsWith("CON Invalid amount") && again.contains("Enter another amount (USD)") && again.contains("0. Cancel"), again);
        assertTrue(ussd(s, ph, "1*1*1*abc*0", true).body().path("text").asText().startsWith("END Cancelled"));
        String with = ussd(s, ph, "1*1*1*abc*2.50", true).body().path("text").asText();
        assertTrue(with.startsWith("CON Pay with:") && !with.contains("Payport wallet"), "the amended amount carries on; guests pay with the four wallets only: " + with);
        assertTrue(ussd(s, ph, "1*1*1*2.50*x", true).body().path("text").asText().startsWith("CON Invalid choice.\nPay with:"), "a wrong choice asks again");
        // Owner, 2026-10-31: the paying number is never assumed.
        String from = ussd(s, ph, "1*1*1*2.50*1", true).body().path("text").asText();
        String local = "0" + ph.substring(4);
        assertTrue(from.contains("Pay from which EcoCash number?") && from.contains("1. " + local + " (this phone)") && from.contains("2. Another number"), from);
        String confirm = ussd(s, ph, "1*1*1*2.50*1*1", true).body().path("text").asText();
        assertTrue(confirm.contains("Econet Airtime USD 2.50 for " + local) && confirm.contains("EcoCash " + local) && confirm.contains("1. Confirm"), confirm);
        assertTrue(ussd(s, ph, "1*1*1*2.50*1*1*2", true).body().path("text").asText().startsWith("END Cancelled"));
        assertTrue(ussd(s, ph, "1*1*1*2.50*1*2", true).body().path("text").asText().contains("Enter the EcoCash number to pay from"));
        String notEcoCash = ussd(s, ph, "1*1*1*2.50*1*2*0712345678", true).body().path("text").asText();
        assertTrue(notEcoCash.contains("cannot pay with EcoCash") && notEcoCash.contains("0. Cancel"), notEcoCash);
        String other = ussd(s, ph, "1*1*1*2.50*1*2*0712345678*0771000111", true).body().path("text").asText();
        assertTrue(other.contains("EcoCash 0771000111") && other.contains("1. Confirm"), "another person's number pays: " + other);
        String home = ussd(s, ph, "1*1*1*2.50*00", true).body().path("text").asText();
        assertTrue(home.startsWith("CON Payport") && home.contains("1. Buy airtime"), "00 goes back to the main menu: " + home);
        assertTrue(ussd(s, ph, "5*PP-NOT-MINE", true).body().path("text").asText().contains("No payment with that reference"));
        assertTrue(ussd(s, "+1555000111", "", true).body().path("text").asText().contains("not supported"), "numbers outside configured markets");

        String olivia = staff("Olivia Ussd", "OPS");
        call("POST", "/api/v1/admin/channels/USSD", olivia, Map.of("enabled", false, "reason", "aggregator outage"));
        assertTrue(ussd(s, ph, "", true).body().path("text").asText().contains("not available"));
        call("POST", "/api/v1/admin/channels/USSD", olivia, Map.of("enabled", true, "reason", "back"));
    }

    String says(String s, String ph, String path) throws Exception {
        return ussd(s, ph, path, true).body().path("text").asText();
    }

    /** Owner, 2026-10-25: USSD and WhatsApp offer what the website and the app offer, guest and registered. */
    @Test
    void ussd_registerLoginSavedNumbersAndPinChange_likeTheApp() throws Exception {
        String ph = String.format("+263778%06d", 400000 + SEQ.incrementAndGet());
        String s = "AT-" + SEQ.incrementAndGet();
        String guest = says(s, ph, "");
        assertTrue(guest.contains("6. Register") && guest.contains("7. Help"), guest);
        assertTrue(says(s, ph, "7").contains("support@payport.africa"));
        assertTrue(says(s, ph, "6").startsWith("CON Register with Payport"));
        assertTrue(says(s, ph, "6*Rudo Moyo").contains("ID number"));
        assertTrue(says(s, ph, "6*Rudo Moyo*63-123456A42").contains("Terms and Privacy Policy"));
        assertTrue(says(s, ph, "6*Rudo Moyo*63-123456A42*1").contains("4-digit PIN"));
        assertTrue(says(s, ph, "6*Rudo Moyo*63-123456A42*1*1357").contains("PIN again"));
        assertTrue(says(s, ph, "6*Rudo Moyo*63-123456A42*1*1357*2468").contains("do not match"));
        String s2 = "AT-" + SEQ.incrementAndGet();
        String done = says(s2, ph, "6*Rudo Moyo*63-123456A42*1*1357*1357");
        assertTrue(done.startsWith("END Welcome to Payport, Rudo"), done);
        assertEquals("63-123456A42", jdbc.queryForObject("SELECT id_number FROM users WHERE phone = ?", String.class, ph));

        String s3 = "AT-" + SEQ.incrementAndGet();
        assertTrue(says(s3, ph, "").contains("Welcome to Payport, Rudo"));
        String menu = says(s3, ph, "1357");
        assertTrue(menu.contains("6. My wallet") && menu.contains("8. More") && menu.contains("5. Send money"), menu);
        assertTrue(says(s3, ph, "1357*8").contains("5. Change PIN"));
        assertTrue(says(s3, ph, "1357*6").contains("5. Open a wallet"));
        assertTrue(says(s3, ph, "1357*8*1").startsWith("END No payments yet"));

        String s4 = "AT-" + SEQ.incrementAndGet();
        String saved = says(s4, ph, "1357*8*3*2*Mum*0771234567");
        assertTrue(saved.startsWith("END Saved Mum"), saved);
        String s5 = "AT-" + SEQ.incrementAndGet();
        assertTrue(says(s5, ph, "1357*1").contains("3. Saved number"));
        assertTrue(says(s5, ph, "1357*1*3").contains("Mum"));
        String ccy = says(s5, ph, "1357*1*3*1");
        assertTrue(ccy.contains("Currency:"), ccy);
        assertTrue(says(s5, ph, "1357*1*3*1*00").contains("what would you like to do"), "00 keeps a logged-in customer logged in");
        String with = says(s5, ph, "1357*1*3*1*1*2.50");
        assertTrue(with.startsWith("CON Pay with:\n1. Payport wallet"), "the wallet is the first way to pay: " + with);

        String s6 = "AT-" + SEQ.incrementAndGet();
        assertTrue(says(s6, ph, "1357*8*5*1111").contains("Wrong PIN"));
        String s7 = "AT-" + SEQ.incrementAndGet();
        assertTrue(says(s7, ph, "1357*8*5*1357*2468*2468").startsWith("END Your PIN has been changed"));
        String s8 = "AT-" + SEQ.incrementAndGet();
        assertTrue(says(s8, ph, "2468").contains("8. More"), "the new PIN works");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM channel_session_secrets WHERE value IN ('1357','2468')", Integer.class), "no PIN is ever kept");
    }

    Res raw(String path, String body, String type) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> resp = HTTP.send(r, HttpResponse.BodyHandlers.ofString());
        return new Res(resp.statusCode(), JSON.createObjectNode().put("text", resp.body()));
    }

    static String econetXml(String tx, String msisdn, String message, String stage) {
        return "<messageRequest xmlns=\"http://econet.co.zw/intergration/messagingSchema\"><transactionTime>2026-10-23T10:00:00</transactionTime>"
                + "<transactionID>" + tx + "</transactionID><sourceNumber>" + msisdn + "</sourceNumber><destinationNumber>123</destinationNumber>"
                + "<message>" + message + "</message><stage>" + stage + "</stage><channel>USSD</channel></messageRequest>";
    }

    String econet(String key, String tx, String msisdn, String message, String stage) throws Exception {
        return raw("/api/v1/ussd/econet?key=" + key, econetXml(tx, msisdn, message, stage), "application/xml").body().path("text").asText();
    }

    @Test
    void ussdNetworkGateways_switchedAndKeyed_walletLoginWithPinNeverStored() throws Exception {
        String guest = String.format("263778%06d", 400000 + SEQ.incrementAndGet());
        String tx = "E" + SEQ.incrementAndGet();
        assertEquals(401, raw("/api/v1/ussd/econet", econetXml(tx, guest, "*123#", "FIRST"), "application/xml").status(), "networks ship switched off");
        String adam = staff("Adam Networks", "ADMIN");
        Res on = call("PUT", "/api/v1/admin/ussd/networks/ECONET", adam, Map.of("enabled", true, "shortCode", "*123#"));
        assertEquals(200, on.status(), on.body().toString());
        assertEquals(401, raw("/api/v1/ussd/econet", econetXml(tx, guest, "*123#", "FIRST"), "application/xml").status(), "no key and no allowed IP: refused");
        String key = call("POST", "/api/v1/admin/ussd/networks/ECONET/access-key", adam, null).data().path("accessKey").asText();
        assertFalse(key.isBlank());
        assertFalse(call("GET", "/api/v1/admin/ussd/networks", adam, null).body().toString().contains(key), "the key is shown once and never listed");

        String menu = econet(key, tx, guest, "*123#", "FIRST");
        assertTrue(menu.contains("<stage>PENDING</stage>") && menu.contains("1. Buy airtime") && menu.contains("<destinationNumber>" + guest), menu);
        assertTrue(econet(key, tx, guest, "1", "PENDING").contains("Airtime for:"));
        assertTrue(econet(key, tx, guest, "1", "PENDING").contains("Currency:"));
        String bad = econet(key, tx, guest, "x", "PENDING");
        assertTrue(bad.contains("<stage>PENDING</stage>") && bad.contains("Invalid choice"), "a wrong choice is asked again: " + bad);

        String holder = String.format("263778%06d", 400000 + SEQ.incrementAndGet());
        jdbc.update("INSERT INTO users (full_name, phone, pin_hash, role, is_active, is_verified) VALUES ('Tendai Moyo', ?, ?, 'CUSTOMER', TRUE, TRUE)",
                "+" + holder, new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("2468"));
        String w = "E" + SEQ.incrementAndGet();
        assertTrue(econet(key, w, holder, "*123#", "FIRST").contains("Welcome to Payport, Tendai"));
        String in = econet(key, w, holder, "2468", "PENDING");
        assertTrue(in.contains("Send money") && in.contains("Cash out at an agent"), in);
        assertEquals("#", jdbc.queryForObject("SELECT path FROM ussd_state WHERE session_id = ?", String.class, "ECONET:" + w), "the PIN is never stored");
        assertTrue(econet(key, w, holder, "1", "PENDING").contains("Airtime for:"));
        String withWallet = econet(key, w, holder, "1", "PENDING");
        assertTrue(withWallet.contains("Currency:"), withWallet);
        String wrong = "E" + SEQ.incrementAndGet();
        econet(key, wrong, holder, "*123#", "FIRST");
        String refused = econet(key, wrong, holder, "1111", "PENDING");
        assertTrue(refused.contains("<stage>COMPLETE</stage>") && refused.contains("Wrong PIN"), refused);
        String asGuest = "E" + SEQ.incrementAndGet();
        econet(key, asGuest, holder, "*123#", "FIRST");
        assertTrue(econet(key, asGuest, holder, "0", "PENDING").contains("5. Check a payment"), "0 continues as guest");

        assertEquals(200, call("PUT", "/api/v1/admin/ussd/networks/NETONE", adam, Map.of("enabled", true)).status());
        String nkey = call("POST", "/api/v1/admin/ussd/networks/NETONE/access-key", adam, null).data().path("accessKey").asText();
        String n1 = "<USSDDynMenuRequest><requestId>N" + tx + "</requestId><msisdn>" + guest + "</msisdn><timeStamp>20261023100000</timeStamp>"
                + "<starCode>*123#</starCode><keyword>123</keyword><userData>*123#</userData></USSDDynMenuRequest>";
        String r1 = raw("/api/v1/ussd/netone?key=" + nkey, n1, "text/xml").body().path("text").asText();
        assertTrue(r1.contains("<rspFlag>1</rspFlag>") && r1.contains("1. Buy airtime"), r1);
        String r2 = raw("/api/v1/ussd/netone?key=" + nkey, n1.replace("<userData>*123#</userData>", "<userData>5</userData>"), "text/xml").body().path("text").asText();
        assertTrue(r2.contains("Payment reference"), r2);

        assertEquals(200, call("PUT", "/api/v1/admin/ussd/networks/TELECEL", adam, Map.of("enabled", true, "shortCode", "*123#")).status());
        String tkey = call("POST", "/api/v1/admin/ussd/networks/TELECEL/access-key", adam, null).data().path("accessKey").asText();
        String t1 = JSON.writeValueAsString(Map.of("source_addr", guest, "destination_addr", "123", "short_message", Map.of("message", "80 T" + tx + " 0 *123#")));
        JsonNode j1 = JSON.readTree(raw("/api/v1/ussd/telecel?key=" + tkey, t1, "application/json").body().path("text").asText());
        assertTrue(j1.path("short_message").asText().startsWith("72 T" + tx + " 0 ") && j1.path("short_message").asText().contains("1. Buy airtime"), j1.toString());
        String t2 = JSON.writeValueAsString(Map.of("source_addr", guest, "destination_addr", "123", "short_message", Map.of("message", "74 T" + tx + " 0 7")));
        JsonNode j2 = JSON.readTree(raw("/api/v1/ussd/telecel?key=" + tkey, t2, "application/json").body().path("text").asText());
        assertTrue(j2.path("short_message").asText().startsWith("81 T" + tx + " 0 "), j2.toString());
    }
}
