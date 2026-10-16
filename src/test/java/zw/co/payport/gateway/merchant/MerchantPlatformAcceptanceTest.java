package zw.co.payport.gateway.merchant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import zw.co.payport.gateway.service.EmailService;
import zw.co.payport.gateway.service.SmsNotificationService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 acceptance, over HTTP only (no database access by staff or merchant):
 * a new merchant signs up, passes KYB (two different staff members), receives keys, integrates
 * with scoped keys, takes sandbox payments (success and a magic-number decline), receives signed
 * v2 webhooks and replays one, sees balances and its statement, rotates a key, and becomes
 * live-ready once a verified settlement destination and a currency enablement exist.
 *
 * The webhook receiver runs on 127.0.0.1 over http, so this test (only) allows private http targets.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MerchantPlatformAcceptanceTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("DB_URL", MYSQL::getJdbcUrl);
        r.add("DB_USERNAME", MYSQL::getUsername);
        r.add("DB_PASSWORD", MYSQL::getPassword);
        r.add("JWT_SECRET", () -> "merchant-platform-test-secret-0123456789-merchant-platform-test");
        r.add("payport.webhooks.allow-private-targets", () -> "true");
        r.add("payport.webhooks.allow-http", () -> "true");
        zw.co.payport.gateway.support.UnListStub.register(r);   // KYB approval needs a current sanctions list
    }

    @Autowired zw.co.payport.gateway.risk.UnSanctionsList unList;

    @org.junit.jupiter.api.BeforeEach
    void currentSanctionsList() {
        assertTrue(java.util.Set.of("NEW_VERSION", "UNCHANGED").contains(unList.refresh("test").outcome()));
    }

    @MockitoBean SmsNotificationService sms;
    @MockitoBean EmailService email;

    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired UserDetailsService staffUsers;

    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 90_000));

    record Received(Map<String, List<String>> headers, String body) {}
    static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    static HttpServer receiver;

    @BeforeAll
    static void startReceiver() throws Exception {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/hook", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            RECEIVED.add(new Received(Map.copyOf(ex.getRequestHeaders()), new String(body, StandardCharsets.UTF_8)));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        receiver.start();
    }

    @AfterAll
    static void stopReceiver() {
        if (receiver != null) receiver.stop(0);
    }

    // ─── http helpers ─────────────────────────────────────────────────────────

    record Res(int status, JsonNode body) {
        JsonNode data() { return body.path("data"); }
        String code() { return body.path("error").path("code").asText(); }
    }

    Res call(String method, String path, String bearer, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode n = r.body() == null || r.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(r.body());
        return new Res(r.statusCode(), n);
    }

    String staff(String name, String role) {
        String phone = String.format("+26377%07d", SEQ.incrementAndGet());
        jdbc.update("INSERT INTO users (full_name, phone, pin_hash, role, is_active, is_verified, email, must_change_password) "
                + "VALUES (?, ?, 'x', ?, TRUE, TRUE, ?, FALSE)", name, phone, role, name.replace(' ', '.') + SEQ.get() + "@payport.test");
        return jwt.generateToken(staffUsers.loadUserByUsername(phone));
    }

    static String hmacHex(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    static Received awaitEvent(String type, int index) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            List<Received> matching = new ArrayList<>();
            for (Received r : RECEIVED) if (type.equals(JSON.readTree(r.body()).path("type").asText())) matching.add(r);
            if (matching.size() > index) return matching.get(index);
            Thread.sleep(250);
        }
        fail("no " + type + " webhook #" + (index + 1) + " received; got " + RECEIVED.size() + " deliveries");
        return null;
    }

    static String header(Received r, String name) {
        for (Map.Entry<String, List<String>> e : r.headers().entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue().get(0);
        }
        return null;
    }

    /** Verifies a v2 signature the way the API reference tells merchants to. */
    static void assertSignedV2(Received r, String secret) throws Exception {
        String sig = header(r, "PayPort-Signature");
        assertNotNull(sig, "PayPort-Signature header present");
        String t = null, v1 = null;
        for (String part : sig.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv[0].equals("t")) t = kv[1];
            if (kv[0].equals("v1")) v1 = kv[1];
        }
        assertNotNull(t);
        assertEquals(hmacHex(secret, t + "." + r.body()), v1, "signature verifies with the webhook secret");
        assertTrue(Math.abs(System.currentTimeMillis() / 1000 - Long.parseLong(t)) < 300, "fresh timestamp");
        assertEquals(JSON.readTree(r.body()).path("id").asText(), header(r, "PayPort-Event-Id"));
    }

    JsonNode awaitSession(String key, long id, String status) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        JsonNode s = null;
        while (System.currentTimeMillis() < deadline) {
            s = call("GET", "/api/v1/merchant/checkout-sessions/" + id, key, null).data();
            if (status.equals(s.path("status").asText())) return s;
            Thread.sleep(300);
        }
        fail("session " + id + " did not reach " + status + ": " + s);
        return null;
    }

    // ─── the journey ──────────────────────────────────────────────────────────

    @Test
    void newMerchantOnboardsIntegratesTakesSandboxPaymentsSeesMoneyAndReceivesWebhooks_withoutDatabaseAccess() throws Exception {
        int n = SEQ.incrementAndGet();
        String email = "owner" + n + "@acme.test", password = "Str0ng!Pass";

        // 1. Signup (public).
        Res signup = call("POST", "/api/v1/merchant-portal/signup", null, Map.of("businessName", "Acme Stores " + n, "registrationNumber", "REG-" + n,
                "businessCategory", "RETAIL", "contactEmail", email, "contactPhone", "+263772" + String.format("%06d", n % 1_000_000),
                "ownerFullName", "Ada Owner", "password", password));
        assertEquals(201, signup.status(), signup.body().toString());
        long merchantId = signup.data().path("id").asLong();

        // 2. KYB: four eyes.
        String alice = staff("Alice Ops", "OPS"), bob = staff("Bob Ops", "OPS");
        Res early = call("POST", "/api/v1/admin/merchants/" + merchantId + "/approve", alice, null);
        assertEquals(409, early.status(), "one person cannot approve: " + early.body());
        assertEquals("KYB_RECOMMENDATION_REQUIRED", early.code());

        Res incomplete = call("POST", "/api/v1/admin/platform/kyb/" + merchantId + "/recommend", alice,
                Map.of("recommendation", "APPROVE", "checklist", Map.of("registration_documents_verified", true)));
        assertEquals(400, incomplete.status(), "the checklist must be complete");

        Map<String, Boolean> checklist = new java.util.LinkedHashMap<>();
        for (String item : MerchantPlatformService.KYB_CHECKLIST) checklist.put(item, true);
        Res rec = call("POST", "/api/v1/admin/platform/kyb/" + merchantId + "/recommend", alice,
                Map.of("recommendation", "APPROVE", "checklist", checklist, "note", "Documents and directors checked"));
        assertEquals(200, rec.status(), rec.body().toString());
        assertEquals("AWAITING_DECISION", rec.data().path("status").asText());

        Res queues = call("GET", "/api/v1/admin/platform/work-queues", bob, null);
        assertEquals(200, queues.status(), queues.body().toString());
        assertTrue(queues.data().path("counts").path("kybAwaitingDecision").asInt() >= 1, "the case shows in the decision queue");

        Res self = call("POST", "/api/v1/admin/platform/kyb/" + merchantId + "/decide", alice, Map.of("accept", true));
        assertEquals(409, self.status(), "the recommender cannot also decide");
        Res decided = call("POST", "/api/v1/admin/platform/kyb/" + merchantId + "/decide", bob, Map.of("accept", true, "note", "Agree"));
        assertEquals(200, decided.status(), decided.body().toString());
        assertEquals("ACTIVE", decided.data().path("merchantStatus").asText());
        assertEquals("APPROVED", decided.data().path("kybCase").path("status").asText());

        // 3. The merchant logs in and creates its own scoped test key.
        Res login = call("POST", "/api/v1/merchant-portal/login", null, Map.of("email", email, "password", password));
        assertEquals(200, login.status(), login.body().toString());
        String dashboard = login.data().path("token").asText();

        Res key = call("POST", "/api/v1/merchant/api-keys", dashboard,
                Map.of("environment", "TEST", "label", "server", "scopes", List.of("checkout:read", "checkout:write", "balances:read")));
        assertEquals(201, key.status(), key.body().toString());
        String sk = key.data().path("secretKey").asText();
        String pk = key.data().path("publicKey").asText();
        long keyId = -1;
        for (JsonNode k : call("GET", "/api/v1/merchant/api-keys", dashboard, null).data()) {
            if (pk.equals(k.path("publicKey").asText())) {
                keyId = k.path("id").asLong();
                assertEquals("checkout:read,checkout:write,balances:read", k.path("scopes").asText());
            }
        }
        assertTrue(keyId > 0, "the new key is listed");

        Res liveTooSoon = call("POST", "/api/v1/merchant/api-keys", dashboard, Map.of("environment", "LIVE"));
        assertEquals(409, liveTooSoon.status(), "no live keys before live readiness");
        assertEquals("NOT_LIVE_READY", liveTooSoon.code());

        // Scopes are enforced; key management stays in the dashboard.
        assertEquals(403, call("GET", "/api/v1/merchant/webhooks", sk, null).status(), "no webhooks:manage scope");
        assertEquals(403, call("GET", "/api/v1/merchant/api-keys", sk, null).status(), "keys are dashboard-only");

        // 4. Webhook endpoint (v2) registered from the dashboard.
        Res hook = call("POST", "/api/v1/merchant/webhooks", dashboard, Map.of("url", "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook"));
        assertEquals(201, hook.status(), hook.body().toString());
        String whSecret = hook.data().path("secret").asText();
        long webhookId = hook.data().path("id").asLong();
        assertEquals(400, call("POST", "/api/v1/merchant/webhooks", dashboard, Map.of("url", "http://169.254.169.254/latest/meta-data")).status(),
                "cloud metadata address refused even here (http allowed, but link-local is never a merchant)");

        // 5. Sandbox payment that succeeds.
        Res s1 = call("POST", "/api/v1/merchant/checkout-sessions", sk, Map.of("amountCents", 2_500, "currency", "USD", "description", "Order 1"));
        assertEquals(201, s1.status(), s1.body().toString());
        assertFalse(s1.data().path("livemode").asBoolean());
        long sid1 = s1.data().path("id").asLong();
        Res pay1 = call("POST", "/api/v1/checkout/" + sid1 + "/pay", null, Map.of("customerPhone", "+263771234567", "paymentMethodCode", "ECOCASH"));
        assertEquals(200, pay1.status(), pay1.body().toString());
        awaitSession(sk, sid1, "PAID");

        Received paid = awaitEvent("checkout_session.paid", 0);
        assertSignedV2(paid, whSecret);
        JsonNode paidBody = JSON.readTree(paid.body());
        assertFalse(paidBody.path("livemode").asBoolean());
        assertEquals(sid1, paidBody.path("data").path("checkoutSessionId").asLong());

        // 6. Sandbox magic number: a decline with the provider's own words.
        long sid2 = call("POST", "/api/v1/merchant/checkout-sessions", sk, Map.of("amountCents", 900, "currency", "USD")).data().path("id").asLong();
        call("POST", "/api/v1/checkout/" + sid2 + "/pay", null, Map.of("customerPhone", "+263770000001", "paymentMethodCode", "ECOCASH"));
        awaitSession(sk, sid2, "FAILED");
        assertSignedV2(awaitEvent("checkout_session.failed", 0), whSecret);

        // 7. Balances and statement with the test key (sandbox money only).
        Res bal = call("GET", "/api/v1/merchant/balances", sk, null);
        assertEquals(200, bal.status(), bal.body().toString());
        assertFalse(bal.data().path("livemode").asBoolean());
        JsonNode usd = bal.data().path("balances").get(0);
        assertEquals("USD", usd.path("currency").asText());
        assertEquals(2_500, usd.path("grossMinor").asLong());
        assertEquals(usd.path("grossMinor").asLong() - usd.path("feesMinor").asLong(), usd.path("pendingMinor").asLong(), "inside the hold");
        Res stmt = call("GET", "/api/v1/merchant/statement?currency=USD", sk, null);
        assertEquals(200, stmt.status(), stmt.body().toString());
        assertEquals("PAYMENT", stmt.data().path("lines").get(0).path("type").asText());
        assertEquals(200, call("GET", "/api/v1/merchant/payouts", dashboard, null).status());

        // 8. Webhook console: replay keeps the event id.
        JsonNode deliveries = call("GET", "/api/v1/merchant/webhooks/" + webhookId + "/deliveries", dashboard, null).data();
        long paidDelivery = -1;
        for (JsonNode d : deliveries) if ("checkout_session.paid".equals(d.path("eventType").asText())) paidDelivery = d.path("id").asLong();
        assertTrue(paidDelivery > 0, deliveries.toString());
        assertEquals(200, call("POST", "/api/v1/merchant/webhooks/deliveries/" + paidDelivery + "/replay", dashboard, null).status());
        Received replayed = awaitEvent("checkout_session.paid", 1);
        assertSignedV2(replayed, whSecret);
        assertEquals(header(paid, "PayPort-Event-Id"), header(replayed, "PayPort-Event-Id"), "a replay is the same event (merchants dedupe on the id)");

        // 9. Rotation without overlap: the old key stops, the new one works.
        Res rotated = call("POST", "/api/v1/merchant/api-keys/" + keyId + "/rotate?overlapHours=0", dashboard, null);
        assertEquals(200, rotated.status(), rotated.body().toString());
        String sk2 = rotated.data().path("secretKey").asText();
        Thread.sleep(1_100);
        Res old = call("GET", "/api/v1/merchant/checkout-sessions/" + sid1, sk, null);
        assertEquals(401, old.status(), old.body().toString());
        assertEquals("API_KEY_EXPIRED", old.code());
        assertEquals(200, call("GET", "/api/v1/merchant/checkout-sessions/" + sid1, sk2, null).status(), "the rotated key keeps the scopes");

        // 10. Live readiness: verified destination (merchant adds, staff verify) and an enablement.
        Res dest = call("POST", "/api/v1/merchant/settlement-destinations", dashboard, Map.of("currency", "USD", "type", "BANK",
                "accountName", "Acme Stores", "accountNumber", "1002003004", "institution", "CBZ Bank", "branch", "Harare"));
        assertEquals(201, dest.status(), dest.body().toString());
        assertFalse(dest.data().has("account_number"), "the account number is not echoed back in full");
        assertTrue(call("GET", "/api/v1/admin/platform/work-queues", alice, null).data().path("counts").path("destinationsToVerify").asInt() >= 1);
        Res verified = call("POST", "/api/v1/admin/platform/settlement-destinations/" + dest.data().path("id").asLong() + "/verify", alice,
                Map.of("approve", true, "note", "Bank letter matches"));
        assertEquals(200, verified.status(), verified.body().toString());
        assertEquals("VERIFIED", verified.data().path("status").asText());
        assertEquals(200, call("POST", "/api/v1/admin/platform/merchants/" + merchantId + "/enablements", bob,
                Map.of("country", "ZW", "currency", "USD", "enabled", true)).status());

        Res readiness = call("GET", "/api/v1/merchant/readiness", dashboard, null);
        assertTrue(readiness.data().path("liveReady").asBoolean(), readiness.body().toString());
        Res live = call("POST", "/api/v1/merchant/api-keys", dashboard, Map.of("environment", "LIVE", "label", "production"));
        assertEquals(201, live.status(), live.body().toString());
        String liveKey = live.data().path("secretKey").asText();
        Res zwg = call("POST", "/api/v1/merchant/checkout-sessions", liveKey, Map.of("amountCents", 1_000, "currency", "ZWG"));
        assertEquals(400, zwg.status(), "ZWG is not enabled for live payments: " + zwg.body());
        Res liveUsd = call("POST", "/api/v1/merchant/checkout-sessions", liveKey, Map.of("amountCents", 1_000, "currency", "USD"));
        assertEquals(201, liveUsd.status(), liveUsd.body().toString());
        assertTrue(liveUsd.data().path("livemode").asBoolean());

        // 11. Currency enablement holds on every live path, not only API keys.
        Res zwgLink = call("POST", "/api/v1/merchant/payment-links", dashboard, Map.of("amountCents", 500, "currency", "ZWG", "description", "Tip"));
        assertEquals(400, zwgLink.status(), "a ZWG payment link cannot be created: " + zwgLink.body());
        Res usdLink = call("POST", "/api/v1/merchant/payment-links", dashboard, Map.of("amountCents", 500, "currency", "USD", "description", "Tip"));
        assertEquals(201, usdLink.status(), usdLink.body().toString());
        String slug = usdLink.data().path("slug").asText();
        Res visit = call("POST", "/api/v1/pay/" + slug + "/start", null, Map.of());
        assertEquals(200, visit.status(), visit.body().toString());
        long linkSession = visit.data().path("checkoutSessionId").asLong();

        // PayPort withdraws USD: existing links, open sessions and API sessions all stop taking live money.
        assertEquals(200, call("POST", "/api/v1/admin/platform/merchants/" + merchantId + "/enablements", bob,
                Map.of("country", "ZW", "currency", "USD", "enabled", false)).status());
        assertEquals(400, call("POST", "/api/v1/pay/" + slug + "/start", null, Map.of()).status(), "the link no longer starts a checkout");
        Res payDisabled = call("POST", "/api/v1/checkout/" + linkSession + "/pay", null, Map.of("customerPhone", "+263771234567", "paymentMethodCode", "ECOCASH"));
        assertEquals(400, payDisabled.status(), "an open live session cannot be paid once the currency is withdrawn: " + payDisabled.body());
        assertTrue(payDisabled.body().toString().contains("not enabled"), payDisabled.body().toString());
        assertEquals(400, call("POST", "/api/v1/merchant/checkout-sessions", liveKey, Map.of("amountCents", 1_000, "currency", "USD")).status());
        assertEquals(201, call("POST", "/api/v1/merchant/checkout-sessions", sk2, Map.of("amountCents", 1_000, "currency", "ZWG")).status(),
                "sandbox is never restricted by enablements");
    }
}
