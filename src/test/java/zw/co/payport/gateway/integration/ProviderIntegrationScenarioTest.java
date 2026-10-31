package zw.co.payport.gateway.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import zw.co.payport.gateway.config.JwtService;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.model.request.InitiateTransactionRequest;
import zw.co.payport.gateway.model.request.PayTransactionRequest;
import zw.co.payport.gateway.payments.CallbackInbox;
import zw.co.payport.gateway.payments.SimulatorPaymentAdaptor;
import zw.co.payport.gateway.repository.TransactionRepository;
import zw.co.payport.gateway.scheduler.FulfilmentWorker;
import zw.co.payport.gateway.service.AdaptorRegistry;
import zw.co.payport.gateway.service.SmsNotificationService;
import zw.co.payport.gateway.service.adaptor.FulfilmentProviderAdaptor;
import zw.co.payport.gateway.service.adaptor.PaymentProviderAdaptor;
import zw.co.payport.gateway.service.adaptor.esolutions.VendorReferences;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5: provider and biller integrations, whole application on MySQL 8. eSolutions is a local
 * spec-shaped stub (no sandbox access); payments use the scriptable simulator.
 *
 *  - the integration register covers every loaded adaptor;
 *  - the kill switch and circuit breakers refuse BEFORE anything is sent, so they never create an
 *    unknown outcome, and a refused payment is untouched and can be paid later;
 *  - biller rules are enforced before the customer pays;
 *  - a ZESA delivery whose outcome is unknown is looked up by the exact reference eSolutions
 *    received and completed from that lookup, with exactly one vend (the Phase 5 money fix).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(ProviderIntegrationScenarioTest.Providers.class)
class ProviderIntegrationScenarioTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0");

    static final EsolutionsStub ESOLUTIONS;
    static volatile Object vendAnswer, resendAnswer;

    static {
        try {
            ESOLUTIONS = new EsolutionsStub();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        ESOLUTIONS.responder = b -> {
            if ("310000".equals(b.get("processingCode"))) {
                return Map.of("mti", "0210", "responseCode", "00", "currencyCode", "USD", "customerData", "T MOYO\n12 Fife Street", "utilityAccount", b.get("utilityAccount"));
            }
            if ("0201".equals(b.get("mti"))) return resendAnswer;
            return vendAnswer;
        };
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("DB_URL", MYSQL::getJdbcUrl);
        r.add("DB_USERNAME", MYSQL::getUsername);
        r.add("DB_PASSWORD", MYSQL::getPassword);
        r.add("JWT_SECRET", () -> "integrations-test-secret-integrations-test-secret-0123456789abc");
        r.add("payport.esolutions.base-url", ESOLUTIONS::url);
        r.add("payport.esolutions.username", () -> "payport");
        r.add("payport.esolutions.password", () -> "secret");
        r.add("payport.esolutions.vendor-number", () -> "VE19257147501");
        r.add("payport.esolutions.timeout-seconds", () -> "2");
        r.add("payport.integrations.breaker.failure-threshold", () -> "3");
        r.add("payport.integrations.breaker.open-seconds", () -> "60");
        r.add("payport.payments.resolver.enabled", () -> "false");
        r.add("payport.payments.watchdog.enabled", () -> "false");
        r.add("payport.fulfilment.worker.enabled", () -> "false");
        r.add("payport.fulfilment.unknown-manual-after-minutes", () -> "120");
    }

    @AfterAll
    static void stop() {
        ESOLUTIONS.close();
    }

    @TestConfiguration
    static class Providers {
        @Bean SimulatorPaymentAdaptor simKillSwitch() { return new SimulatorPaymentAdaptor("SIMK"); }
        @Bean SimulatorPaymentAdaptor simBreaker() { return new SimulatorPaymentAdaptor("SIMB"); }
    }

    @MockitoBean SmsNotificationService sms;

    @Autowired TransactionOrchestratorService orchestrator;
    @Autowired CallbackInbox inbox;
    @Autowired FulfilmentWorker worker;
    @Autowired TransactionRepository transactions;
    @Autowired ProviderHealthService health;
    @Autowired IntegrationRegister register;
    @Autowired AdaptorRegistry adaptors;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired UserDetailsService staffUsers;
    @Value("${local.server.port}") int port;

    static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 300_000));
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeEach
    void methods() {
        jdbc.update("INSERT IGNORE INTO payment_methods (code, name, is_active, display_order) VALUES "
                + "('SIMK', 'Simulator K', TRUE, 92), ('SIMB', 'Simulator B', TRUE, 93)");
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    record Res(int status, JsonNode body) {}

    Res call(String method, String path, String bearer, Map<String, String> headers, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        if (headers != null) headers.forEach(b::header);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body() == null || r.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(r.body()));
    }

    String ops() {
        String phone = String.format("+26378%07d", SEQ.incrementAndGet());
        jdbc.update("INSERT INTO users (full_name, phone, pin_hash, role, is_active, is_verified, email, must_change_password) "
                + "VALUES ('Ops Person', ?, 'x', 'OPS', TRUE, TRUE, ?, FALSE)", phone, "ops" + SEQ.get() + "@payport.test");
        return jwt.generateToken(staffUsers.loadUserByUsername(phone));
    }

    String ref(String channel) {
        return String.format("PP-%s-20261016-%06d", channel, SEQ.incrementAndGet() % 1_000_000);
    }

    String initiate(String product, String recipient, long cents) {
        String ref = ref("API");
        InitiateTransactionRequest req = new InitiateTransactionRequest();
        req.setReference(ref);
        req.setProductTypeCode(product);
        req.setRecipientIdentifier(recipient);
        req.setAmountCents(cents);
        req.setCurrency("USD");
        req.setCustomerPhone("+263771234567");
        req.setChannel("API");
        orchestrator.initiateTransaction(req, "127.0.0.1");
        return ref;
    }

    void pay(String ref, String provider) throws Exception {
        PayTransactionRequest p = new PayTransactionRequest();
        p.setReference(ref);
        p.setPaymentMethodCode(provider);
        p.setCustomerPhone("+263771234567");
        orchestrator.submitPayment(p);
    }

    TransactionStatus status(String ref) {
        return transactions.findByReference(ref).orElseThrow().getStatus();
    }

    // ─── tests ────────────────────────────────────────────────────────────────

    @Test
    void theRegisterCoversEveryLoadedAdaptor_andEveryEntryHasOne() {
        for (IntegrationRegister.Provider p : register.all().values()) {
            assertTrue(adaptors.hasPaymentAdaptor(p.code()) || adaptors.hasFulfilmentAdaptor(p.code()), p.code() + " is registered but has no adaptor");
        }
        for (PaymentProviderAdaptor a : context.getBeansOfType(PaymentProviderAdaptor.class).values()) {
            if (a.getProviderCode().startsWith("SIM")) continue;   // test simulators
            assertNotNull(register.get(a.getProviderCode()), a.getProviderCode() + " has an adaptor but no register entry");
        }
        for (FulfilmentProviderAdaptor a : context.getBeansOfType(FulfilmentProviderAdaptor.class).values()) {
            assertNotNull(register.get(a.getProviderCode()), a.getProviderCode() + " has an adaptor but no register entry");
        }
    }

    @Test
    void killSwitch_refusesBeforeSending_paymentUntouched_thenPaysAfterResume() throws Exception {
        // Over HTTP, as a channel would see it.
        String ref = ref("WEB");
        Res init = call("POST", "/api/v1/transactions/initiate", null, null, Map.of("reference", ref, "productTypeCode", "MERCHANT_CHECKOUT",
                "recipientIdentifier", "+263771234567", "amountCents", 1500, "currency", "USD", "customerPhone", "+263771234567", "channel", "WEB"));
        assertEquals(201, init.status(), init.body().toString());
        Map<String, String> token = Map.of("X-Transaction-Token", init.body().path("data").path("accessToken").asText());

        health.suspend("SIMK", "Provider announced maintenance 10:00-11:00", "staff:test");
        Res refused = call("POST", "/api/v1/transactions/pay", null, token, Map.of("reference", ref, "paymentMethodCode", "SIMK", "customerPhone", "+263771234567"));
        assertEquals(503, refused.status(), refused.body().toString());
        assertEquals("PROVIDER_UNAVAILABLE", refused.body().path("error").path("code").asText());
        assertTrue(refused.body().path("error").path("message").asText().contains("No money was taken"));
        assertEquals(0, SimulatorPaymentAdaptor.script(ref).submits.get(), "nothing was sent");
        assertEquals(TransactionStatus.INITIATED, status(ref), "the payment is untouched");

        health.resume("SIMK", "maintenance over", "staff:test");
        Res ok = call("POST", "/api/v1/transactions/pay", null, token, Map.of("reference", ref, "paymentMethodCode", "SIMK", "customerPhone", "+263771234567"));
        assertEquals(200, ok.status(), ok.body().toString());
        assertEquals(1, SimulatorPaymentAdaptor.script(ref).submits.get());
        assertEquals(TransactionStatus.PAYMENT_PENDING, status(ref));
    }

    @Test
    void circuitBreaker_opensAfterTransportFailures_neverFailsAPaymentThatMayHaveBeenCharged() throws Exception {
        String[] refs = new String[3];
        for (int i = 0; i < 3; i++) {
            refs[i] = initiate("MERCHANT_CHECKOUT", "+263771234567", 800);
            SimulatorPaymentAdaptor.script(refs[i]).submit = SimulatorPaymentAdaptor.Submit.READ_TIMEOUT;
            pay(refs[i], "SIMB");
            assertEquals(TransactionStatus.PAYMENT_PENDING, status(refs[i]), "unknown outcome: may have been charged, so never failed");
        }
        assertEquals("DOWN", health.health("SIMB").get("status"));

        String next = initiate("MERCHANT_CHECKOUT", "+263771234567", 800);
        ProviderUnavailableException e = assertThrows(ProviderUnavailableException.class, () -> pay(next, "SIMB"));
        assertTrue(e.getMessage().contains("No money was taken"));
        assertEquals(0, SimulatorPaymentAdaptor.script(next).submits.get(), "refused before sending");
        assertEquals(TransactionStatus.INITIATED, status(next));
        assertEquals(1L, health.health("SIMB").get("refused"));
    }

    @Test
    void billerRules_applyBeforeTheCustomerPays() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> initiate("ZESA_TOKEN", "1234", 1000));
        assertTrue(e.getMessage().contains("11 digits"), e.getMessage());
        assertDoesNotThrow(() -> initiate("ZESA_TOKEN", "37132587712", 1000));
    }

    @Test
    void zesaUnknownOutcome_isLookedUpByTheReferenceEsolutionsReceived_andCompletedWithOneVend() throws Exception {
        String ref = initiate("ZESA_TOKEN", "37132587712", 1000);
        pay(ref, "SIMK");
        assertEquals(CallbackInbox.Status.APPLIED, inbox.receive("SIMK", SimulatorPaymentAdaptor.callbackBody(ref, "SUCCESS", 1000, "x-" + ref),
                Map.of("x-sim-signature", "valid"), "10.0.0.1").status());

        // eSolutions says "in progress" to the vend and to the immediate resend: the outcome is unknown.
        vendAnswer = Map.of("mti", "0210", "responseCode", "09");
        resendAnswer = Map.of("mti", "0210", "responseCode", "09");
        int before = ESOLUTIONS.bodies("U50000", "0200").size();
        worker.runOnce(LocalDateTime.now());

        String expected = VendorReferences.forDelivery(ref, 1);
        var tx = transactions.findByReference(ref).orElseThrow();
        assertEquals(TransactionStatus.FULFILMENT_PENDING, tx.getStatus(), "unknown: neither delivered nor failed");
        assertEquals(expected, tx.getFulfilmentReference(), "recorded before sending");
        var vends = ESOLUTIONS.bodies("U50000", "0200");
        assertEquals(before + 1, vends.size());
        assertEquals(expected, vends.get(vends.size() - 1).get("vendorReference"), "the recorded reference is the one eSolutions received");

        // Later eSolutions has the answer: the token was issued.
        resendAnswer = EsolutionsStub.zesaSuccess(expected, "USD");
        worker.runOnce(LocalDateTime.now().plusMinutes(6));

        assertEquals(TransactionStatus.COMPLETED, status(ref));
        var lookups = ESOLUTIONS.bodies(null, "0201");
        assertEquals(expected, lookups.get(lookups.size() - 1).get("originalReference"),
                "looked up by the vend's reference (it used to be the Payport reference, which eSolutions never saw)");
        assertEquals(before + 1, ESOLUTIONS.bodies("U50000", "0200").size(), "exactly one vend: never re-vended");
    }

    @Test
    void opsConsole_registerHealthKillSwitchAndBillerLookup_overHttp() throws Exception {
        String ops = ops();
        Res list = call("GET", "/api/v1/admin/integrations", ops, null, null);
        assertEquals(200, list.status(), list.body().toString());
        JsonNode zesa = null;
        for (JsonNode p : list.body().path("data")) if ("ZESA".equals(p.path("code").asText())) zesa = p;
        assertNotNull(zesa);
        assertTrue(zesa.path("adaptorLoaded").asBoolean());
        assertEquals("SPEC_ONLY", zesa.path("verification").asText());
        assertEquals("BILLER", zesa.path("kind").asText());

        Res catalogue = call("GET", "/api/v1/billers", null, null, null);
        assertEquals(200, catalogue.status());
        assertTrue(catalogue.body().toString().contains("\"accountLabel\":\"Meter number\""));

        assertEquals(400, call("POST", "/api/v1/billers/ZESA/lookup", null, null, Map.of("accountNumber", "12")).status());
        Res lookup = call("POST", "/api/v1/billers/ZESA/lookup", null, null, Map.of("accountNumber", "3713 2587 712", "currency", "USD"));
        assertEquals(200, lookup.status(), lookup.body().toString());
        assertEquals("T MOYO", lookup.body().path("data").path("holderName").asText());
        assertTrue(lookup.body().path("data").path("payable").asBoolean());

        // Switch off and on at will: no reason needed, repeating a switch is harmless.
        assertEquals(200, call("POST", "/api/v1/admin/integrations/ZESA/suspend", ops, null, Map.of("reason", "")).status());
        assertEquals(200, call("POST", "/api/v1/admin/integrations/ZESA/suspend", ops, null, Map.of("reason", "eSolutions float exhausted")).status());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM provider_suspensions WHERE provider_code = 'ZESA' AND resumed_at IS NULL", Integer.class));
        Res suspended = call("POST", "/api/v1/billers/ZESA/lookup", null, null, Map.of("accountNumber", "37132587712", "currency", "USD"));
        assertEquals(503, suspended.status());
        assertEquals("SUSPENDED", call("GET", "/api/v1/admin/integrations/ZESA", ops, null, null).body().path("data").path("health").path("status").asText());
        assertEquals(200, call("POST", "/api/v1/admin/integrations/ZESA/resume", ops, null, Map.of("note", "float topped up")).status());
        assertEquals(200, call("POST", "/api/v1/admin/integrations/ZESA/resume", ops, null, Map.of()).status(), "already on: harmless");

        assertEquals(400, call("POST", "/api/v1/admin/integrations/ZESA/verifications", ops, null,
                Map.of("environment", "SANDBOX", "flow", "deliver", "pass", true)).status(), "evidence required");
        assertEquals(400, call("POST", "/api/v1/admin/integrations/ZESA/verifications", ops, null,
                Map.of("environment", "SANDBOX", "flow", "refund", "pass", true, "evidence", "x")).status(), "ZESA does not declare refunds");
        Res recorded = call("POST", "/api/v1/admin/integrations/ZESA/verifications", ops, null,
                Map.of("environment", "SANDBOX", "flow", "lookup", "pass", true, "evidence", "ticket ES-123: meter 37132587712 looked up in eSolutions test"));
        assertEquals(200, recorded.status(), recorded.body().toString());
        assertEquals("PASS", recorded.body().path("data").get(0).path("result").asText());
    }
}
