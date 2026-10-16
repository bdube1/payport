package zw.co.payport.gateway.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
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
import zw.co.payport.gateway.model.request.InitiateTransactionRequest;
import zw.co.payport.gateway.model.request.PayTransactionRequest;
import zw.co.payport.gateway.payments.CallbackInbox;
import zw.co.payport.gateway.payments.SimulatorPaymentAdaptor;
import zw.co.payport.gateway.service.SmsNotificationService;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Push notifications (owner decision: yes). The app registers its Expo token; every final outcome
 * of the customer's payment is pushed with the same words the app shows (the provider's own on a
 * failure); guests get none; dead tokens are retired; Expo outages are retried. Expo is a local stub.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(PushNotificationScenarioTest.Providers.class)
class PushNotificationScenarioTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0");

    static final HttpServer EXPO;
    static final List<JsonNode> PUSHED = new CopyOnWriteArrayList<>();
    static volatile int expoStatus = 200;
    static volatile String deadToken = null;

    static {
        try {
            EXPO = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            EXPO.createContext("/--/api/v2/push/send", ex -> {
                JsonNode batch = new ObjectMapper().readTree(ex.getRequestBody().readAllBytes());
                StringBuilder tickets = new StringBuilder("{\"data\":[");
                for (int i = 0; i < batch.size(); i++) {
                    if (expoStatus == 200) PUSHED.add(batch.get(i));
                    boolean dead = batch.get(i).path("to").asText().equals(deadToken);
                    tickets.append(i > 0 ? "," : "").append(dead
                            ? "{\"status\":\"error\",\"message\":\"not registered\",\"details\":{\"error\":\"DeviceNotRegistered\"}}"
                            : "{\"status\":\"ok\",\"id\":\"t" + i + "\"}");
                }
                byte[] out = (tickets + "]}").getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(expoStatus, expoStatus == 200 ? out.length : -1);
                if (expoStatus == 200) ex.getResponseBody().write(out);
                ex.close();
            });
            EXPO.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        EXPO.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("DB_URL", MYSQL::getJdbcUrl);
        r.add("DB_USERNAME", MYSQL::getUsername);
        r.add("DB_PASSWORD", MYSQL::getPassword);
        r.add("JWT_SECRET", () -> "push-test-secret-push-test-secret-push-test-secret-0123456789");
        r.add("payport.push.expo-url", () -> "http://127.0.0.1:" + EXPO.getAddress().getPort() + "/--/api/v2/push/send");
        r.add("payport.push.enabled", () -> "false");               // driven with sendDue
        r.add("payport.payments.resolver.enabled", () -> "false");
        r.add("payport.payments.watchdog.enabled", () -> "false");
        r.add("payport.fulfilment.worker.enabled", () -> "false");
    }

    @TestConfiguration
    static class Providers {
        @Bean SimulatorPaymentAdaptor simPush() { return new SimulatorPaymentAdaptor("SIMP"); }
    }

    @MockitoBean SmsNotificationService sms;

    @Autowired PushNotificationService push;
    @Autowired TransactionOrchestratorService orchestrator;
    @Autowired CallbackInbox inbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired UserDetailsService users;
    @Value("${local.server.port}") int port;

    static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000));

    @BeforeEach
    void setUp() {
        jdbc.update("INSERT IGNORE INTO payment_methods (code, name, is_active, display_order) VALUES ('SIMP', 'Simulator P', TRUE, 94)");
    }

    String customer(String phone) {
        jdbc.update("INSERT INTO users (full_name, phone, pin_hash, role, is_active, is_verified) VALUES ('Rudo Customer', ?, 'x', 'CUSTOMER', TRUE, TRUE)", phone);
        return jwt.generateToken(users.loadUserByUsername(phone));
    }

    int post(String method, String path, String bearer, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    String failedPayment(String phone, String providerMessage) throws Exception {
        String ref = String.format("PP-MOB-20261016-%06d", SEQ.incrementAndGet() % 1_000_000);
        InitiateTransactionRequest req = new InitiateTransactionRequest();
        req.setReference(ref);
        req.setProductTypeCode("MERCHANT_CHECKOUT");
        req.setRecipientIdentifier(phone);
        req.setAmountCents(2_500L);
        req.setCurrency("USD");
        req.setCustomerPhone(phone);
        req.setChannel("MOB");
        orchestrator.initiateTransaction(req, "127.0.0.1");
        PayTransactionRequest p = new PayTransactionRequest();
        p.setReference(ref);
        p.setPaymentMethodCode("SIMP");
        p.setCustomerPhone(phone);
        orchestrator.submitPayment(p);
        assertEquals(CallbackInbox.Status.APPLIED, inbox.receive("SIMP", SimulatorPaymentAdaptor.callbackBody(ref, "FAILED", 2_500, "x-" + ref, providerMessage),
                Map.of("x-sim-signature", "valid"), "10.0.0.1").status());
        return ref;
    }

    List<JsonNode> pushesFor(String ref) {
        return PUSHED.stream().filter(n -> ref.equals(n.path("data").path("reference").asText())).toList();
    }

    @Test
    void registeredCustomer_getsTheFinalOutcomeByPush_inTheProvidersWords() throws Exception {
        String phone = String.format("+26373%07d", SEQ.incrementAndGet());
        String token = customer(phone);
        String device = "ExponentPushToken[abcDEF123456789xyz" + SEQ.get() + "]";
        assertEquals(400, post("POST", "/api/v1/profile/push-token", token, "{\"token\":\"not-a-token\"}"));
        assertTrue(List.of(401, 403).contains(post("POST", "/api/v1/profile/push-token", null, "{\"token\":\"" + device + "\"}")), "login required");
        assertEquals(200, post("POST", "/api/v1/profile/push-token", token, "{\"token\":\"" + device + "\",\"platform\":\"android\"}"));

        String ref = failedPayment(phone, "Insufficient funds in your EcoCash wallet");
        push.sendDue(LocalDateTime.now());

        List<JsonNode> got = pushesFor(ref);
        assertEquals(1, got.size());
        assertEquals(device, got.get(0).path("to").asText());
        assertEquals("Payment failed", got.get(0).path("title").asText());
        assertTrue(got.get(0).path("body").asText().startsWith("Insufficient funds in your EcoCash wallet"), got.get(0).toString());
        assertEquals("FAILED", got.get(0).path("data").path("outcome").asText());

        push.sendDue(LocalDateTime.now().plusMinutes(5));
        assertEquals(1, pushesFor(ref).size(), "sent once");
        verifyNoSmsForFailures();
    }

    void verifyNoSmsForFailures() {
        org.mockito.Mockito.verify(sms, org.mockito.Mockito.never()).sendDeliverySuccess(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void guests_getNoPush_deadTokensAreRetired_andOutagesAreRetried() throws Exception {
        String guest = String.format("+26374%07d", SEQ.incrementAndGet());
        String g = failedPayment(guest, "Declined");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM push_outbox WHERE transaction_reference = ?", Integer.class, g), "no account, nothing queued");

        String phone = String.format("+26375%07d", SEQ.incrementAndGet());
        String token = customer(phone);
        String good = "ExpoPushToken[good" + SEQ.get() + "abcdefghij]", dead = "ExpoPushToken[dead" + SEQ.get() + "abcdefghij]";
        post("POST", "/api/v1/profile/push-token", token, "{\"token\":\"" + good + "\"}");
        post("POST", "/api/v1/profile/push-token", token, "{\"token\":\"" + dead + "\"}");

        expoStatus = 503;
        String ref = failedPayment(phone, "Declined by provider");
        push.sendDue(LocalDateTime.now());
        assertEquals("PENDING", jdbc.queryForObject("SELECT state FROM push_outbox WHERE transaction_reference = ?", String.class, ref), "Expo down: retried later");

        expoStatus = 200;
        deadToken = dead;
        push.sendDue(LocalDateTime.now().plusMinutes(2));
        assertEquals("SENT", jdbc.queryForObject("SELECT state FROM push_outbox WHERE transaction_reference = ?", String.class, ref));
        assertEquals("DeviceNotRegistered", jdbc.queryForObject("SELECT disabled_reason FROM push_tokens WHERE token = ?", String.class, dead));
        assertNull(jdbc.queryForObject("SELECT disabled_at FROM push_tokens WHERE token = ?", java.sql.Timestamp.class, good));
        deadToken = null;

        // Logout removes the device.
        assertEquals(200, post("DELETE", "/api/v1/profile/push-token", token, "{\"token\":\"" + good + "\"}"));
        assertEquals("UNREGISTERED", jdbc.queryForObject("SELECT disabled_reason FROM push_tokens WHERE token = ?", String.class, good));
    }
}
