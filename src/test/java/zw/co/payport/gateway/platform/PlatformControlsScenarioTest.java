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
        String ref = String.format("PP-CTL-%s-%06d", channel, SEQ.incrementAndGet() % 1_000_000);
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

        // Placeholders block going live: the request itself is refused, with the reasons.
        Res blocked = call("POST", "/api/v1/admin/markets/ZM/change-requests", adam, Map.of("changes", Map.of("status", "LIVE"), "reason", "launch"));
        assertEquals(400, blocked.status());
        assertTrue(blocked.message().contains("placeholder"), blocked.message());

        // A complete request: legal name, real bank account, ZMW live.
        Res req = call("POST", "/api/v1/admin/markets/ZM/change-requests", adam, Map.of(
                "changes", Map.of("status", "LIVE", "legalEntityName", "PayPort Zambia Limited", "bankName", "Example Bank Zambia",
                        "bankBic", "EXBKZMLU", "bankFormatStatus", "CONFIRMED by the bank 2026-10-16", "statusNote", "Licensed"),
                "currencies", List.of(Map.of("currency", "ZMW", "live", true, "bankAccountNumber", "0012345678901", "bankAccountName", "PayPort Zambia settlement")),
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
        assertTrue(jdbc.queryForObject("SELECT enabled FROM ref_countries WHERE code = 'ZM'", Boolean.class));
        assertTrue(jdbc.queryForObject("SELECT enabled FROM ref_currencies WHERE code = 'ZMW'", Boolean.class), "the ledger accepts ZMW");
        assertEquals("PayPort Zambia Limited", jdbc.queryForObject("SELECT legal_name FROM ref_legal_entities WHERE code = 'PAYPORT_ZM_SANDBOX'", String.class));
        assertEquals(List.of(), markets.problems().getOrDefault("ZM", List.of()));

        // A merchant can now be enabled for live ZMW, and live checkout in ZMW is allowed.
        long m = activeMerchant("Lusaka Traders");
        assertEquals(200, call("POST", "/api/v1/admin/platform/merchants/" + m + "/enablements", olivia,
                Map.of("country", "ZM", "currency", "ZMW", "enabled", true)).status());
        assertDoesNotThrow(() -> checkout.requireLiveCurrency(m, "ZMW"));

        // Switching off is immediate, by one person, with a reason.
        assertEquals(400, call("POST", "/api/v1/admin/markets/ZM/suspend", olivia, Map.of("reason", "")).status());
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

        Res qr = call("POST", "/api/v1/admin/channels/QR", adam, Map.of("enabled", true, "reason", "try"));
        assertEquals(409, qr.status());
        assertEquals("LICENCE_REQUIRED", qr.code());

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
    void phase8_licenceGatedLines_qr_fx() throws Exception {
        String carol = staff("Carol Lines", "COMPLIANCE"), dave = staff("Dave Lines", "COMPLIANCE"), adam = staff("Adam Lines", "ADMIN"),
                fiona = staff("Fiona Fx", "FINANCE"), frank = staff("Frank Fx", "FINANCE");
        assertEquals(6, call("GET", "/api/v1/admin/service-lines", adam, null).data().size());
        assertEquals(409, call("POST", "/api/v1/admin/service-lines/QR_PAYMENTS", adam, Map.of("enabled", true, "reason", "go")).status(), "licence first");
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("UPDATE service_lines SET enabled = TRUE WHERE code = 'QR_PAYMENTS'"), "the database refuses it too");

        // Licence decision: evidence required, two compliance officers.
        assertEquals(400, call("POST", "/api/v1/admin/service-lines/QR_PAYMENTS/licence-requests", carol, Map.of("status", "GRANTED", "reason", "x")).status());
        Res lic = call("POST", "/api/v1/admin/service-lines/QR_PAYMENTS/licence-requests", carol,
                Map.of("status", "GRANTED", "reference", "RBZ/NPS/QR/2026/001", "regulator", "RBZ", "reason", "authorisation letter on file"));
        long lid = lic.data().path("approvalId").asLong();
        assertEquals(409, call("POST", "/api/v1/admin/service-lines/licence-requests/" + lid + "/decide", carol, Map.of("approve", true)).status());
        assertEquals(200, call("POST", "/api/v1/admin/service-lines/licence-requests/" + lid + "/decide", dave, Map.of("approve", true)).status());
        assertEquals(200, call("POST", "/api/v1/admin/service-lines/QR_PAYMENTS", adam, Map.of("enabled", true, "reason", "launch QR")).status());
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

        // Licence withdrawn: the line and its channel go off at once.
        Res refuse = call("POST", "/api/v1/admin/service-lines/QR_PAYMENTS/licence-requests", carol, Map.of("status", "REFUSED", "reason", "scheme not joined"));
        assertEquals(200, call("POST", "/api/v1/admin/service-lines/licence-requests/" + refuse.data().path("approvalId").asLong() + "/decide", dave,
                Map.of("approve", true)).status());
        assertEquals(503, call("GET", "/api/v1/checkout/" + sid + "/qr", null, null).status());

        // FX: maker-checker rates; quotes only when the line is on.
        Res rate = call("POST", "/api/v1/admin/fx/rates", fiona, Map.of("base", "USD", "quote", "ZWG", "rate", 26.5, "spreadBps", 100, "source", "RBZ interbank 2026-10-16"));
        long rid = rate.data().path("id").asLong();
        assertEquals(409, call("POST", "/api/v1/admin/fx/rates/" + rid + "/decide", fiona, Map.of("approve", true)).status());
        assertEquals(200, call("POST", "/api/v1/admin/fx/rates/" + rid + "/decide", frank, Map.of("approve", true)).status());
        assertEquals(503, call("GET", "/api/v1/fx/quote?from=USD&to=ZWG&amountMinor=1000", null, null).status(), "FX line is off");
        Res fxLic = call("POST", "/api/v1/admin/service-lines/FX_CONVERSION/licence-requests", carol,
                Map.of("status", "NOT_REQUIRED", "regulator", "RBZ Exchange Control", "reason", "indicative quotes only (legal opinion on file)"));
        call("POST", "/api/v1/admin/service-lines/licence-requests/" + fxLic.data().path("approvalId").asLong() + "/decide", dave, Map.of("approve", true));
        assertEquals(200, call("POST", "/api/v1/admin/service-lines/FX_CONVERSION", adam, Map.of("enabled", true, "reason", "show quotes")).status());
        Res q = call("GET", "/api/v1/fx/quote?from=USD&to=ZWG&amountMinor=1000", null, null);
        assertEquals(26_235, q.data().path("convertedMinor").asLong(), "10.00 USD × 26.5 less 1% = 262.35 ZWG, rounded down");
        Res inv = call("GET", "/api/v1/fx/quote?from=ZWG&to=USD&amountMinor=26500", null, null);
        assertEquals(990, inv.data().path("convertedMinor").asLong(), "inverse rate, spread applied, rounded down");

        // Parked lines cannot be switched on even with a licence answer.
        Res wl = call("POST", "/api/v1/admin/service-lines/WALLETS/licence-requests", carol, Map.of("status", "NOT_REQUIRED", "reason", "test"));
        call("POST", "/api/v1/admin/service-lines/licence-requests/" + wl.data().path("approvalId").asLong() + "/decide", dave, Map.of("approve", true));
        Res parked = call("POST", "/api/v1/admin/service-lines/WALLETS", adam, Map.of("enabled", true, "reason", "try"));
        assertEquals(409, parked.status());
        assertTrue(parked.message().contains("parked"));
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

    @Test
    void ussdMenus_signed_channelSwitch_andStatusOnlyForThePayer() throws Exception {
        String s = "AT-" + SEQ.incrementAndGet(), ph = "+263771234567";
        assertEquals(401, ussd(s, ph, "", false).status(), "unsigned hops are refused");
        String menu = ussd(s, ph, "", true).body().path("text").asText();
        assertTrue(menu.startsWith("CON PayPort"), menu);
        String ccy = ussd(s, ph, "1", true).body().path("text").asText();
        assertTrue(ccy.startsWith("CON Currency:") && ccy.contains("USD"), ccy);
        assertTrue(ussd(s, ph, "1*1", true).body().path("text").asText().startsWith("CON Amount (USD)"));
        assertTrue(ussd(s, ph, "1*1*abc*1", true).body().path("text").asText().startsWith("END Invalid amount"));
        String confirm = ussd(s, ph, "1*1*2.50*1", true).body().path("text").asText();
        assertTrue(confirm.contains("Buy USD 2.50 airtime") && confirm.contains("1. Confirm"), confirm);
        assertTrue(ussd(s, ph, "1*1*2.50*1*2", true).body().path("text").asText().startsWith("END Cancelled"));
        assertTrue(ussd(s, ph, "3*PP-NOT-MINE", true).body().path("text").asText().contains("No payment with that reference"));
        assertTrue(ussd(s, "+1555000111", "", true).body().path("text").asText().contains("not supported"), "numbers outside configured markets");

        String olivia = staff("Olivia Ussd", "OPS");
        call("POST", "/api/v1/admin/channels/USSD", olivia, Map.of("enabled", false, "reason", "aggregator outage"));
        assertTrue(ussd(s, ph, "", true).body().path("text").asText().contains("not available"));
        call("POST", "/api/v1/admin/channels/USSD", olivia, Map.of("enabled", true, "reason", "back"));
    }
}
