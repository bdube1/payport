package zw.co.payport.gateway.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import zw.co.payport.gateway.model.request.InitiateTransactionRequest;
import zw.co.payport.gateway.service.DisputeService;
import zw.co.payport.gateway.service.EmailService;
import zw.co.payport.gateway.service.SmsNotificationService;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6 acceptance: the scenario library (velocity, structuring, sanctions hit, false positive)
 * produces the expected alerts and cases; observe-then-enforce takes two compliance officers;
 * cases close four-eyes; a confirmed case yields an STR draft that is approved four-eyes; dispute
 * timelines; and access controls (only COMPLIANCE sees compliance data). Whole app, MySQL 8, HTTP.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RiskComplianceScenarioTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("DB_URL", MYSQL::getJdbcUrl);
        r.add("DB_USERNAME", MYSQL::getUsername);
        r.add("DB_PASSWORD", MYSQL::getPassword);
        r.add("JWT_SECRET", () -> "compliance-test-secret-compliance-test-secret-0123456789abcdef");
        r.add("payport.payments.resolver.enabled", () -> "false");
        r.add("payport.payments.watchdog.enabled", () -> "false");
        r.add("payport.fulfilment.worker.enabled", () -> "false");
        r.add("payport.push.enabled", () -> "false");
    }

    @MockitoBean SmsNotificationService sms;
    @MockitoBean EmailService email;

    @Autowired TransactionOrchestratorService orchestrator;
    @Autowired DisputeService disputes;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired UserDetailsService users;
    @Value("${local.server.port}") int port;

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000));

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
        return new Res(r.statusCode(), r.body() == null || r.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(r.body()));
    }

    String phone() {
        return String.format("+26371%07d", SEQ.incrementAndGet());
    }

    String user(String name, String role, String phone) {
        jdbc.update("INSERT INTO users (full_name, phone, pin_hash, role, is_active, is_verified, email, must_change_password) VALUES (?, ?, 'x', ?, TRUE, TRUE, ?, FALSE)",
                name, phone, role, "CUSTOMER".equals(role) ? null : name.replace(' ', '.').toLowerCase() + SEQ.incrementAndGet() + "@payport.test");
        return jwt.generateToken(users.loadUserByUsername(phone));
    }

    String staff(String name, String role) {
        return user(name, role, phone());
    }

    String initiate(String customerPhone, long cents) {
        String ref = String.format("PP-API-20261016-%06d", SEQ.incrementAndGet() % 1_000_000);
        InitiateTransactionRequest req = new InitiateTransactionRequest();
        req.setReference(ref);
        req.setProductTypeCode("MERCHANT_CHECKOUT");
        req.setRecipientIdentifier(customerPhone);
        req.setAmountCents(cents);
        req.setCurrency("USD");
        req.setCustomerPhone(customerPhone);
        req.setChannel("API");
        orchestrator.initiateTransaction(req, "127.0.0.1");
        return ref;
    }

    List<Map<String, Object>> alerts(String rule, String phone) {
        return jdbc.queryForList("SELECT * FROM risk_alerts WHERE rule_code = ? AND subject_key = ?", rule, "PHONE:" + NameMatcher.identifier(phone));
    }

    Map<String, Object> openCase(String subjectKey) {
        List<Map<String, Object>> c = jdbc.queryForList("SELECT * FROM risk_cases WHERE subject_key = ? ORDER BY id DESC", subjectKey);
        return c.isEmpty() ? null : c.get(0);
    }

    /** One officer requests, another approves. */
    void changeRule(String carol, String dave, String code, Map<String, Object> change) throws Exception {
        Res req = call("POST", "/api/v1/admin/compliance/rules/" + code + "/change-requests", carol, change);
        assertEquals(200, req.status(), req.body().toString());
        long id = req.data().path("approvalId").asLong();
        Res self = call("POST", "/api/v1/admin/compliance/rule-changes/" + id + "/decide", carol, Map.of("approve", true));
        assertEquals(409, self.status(), "nobody approves their own rule change: " + self.body());
        Res ok = call("POST", "/api/v1/admin/compliance/rule-changes/" + id + "/decide", dave, Map.of("approve", true, "note", "agreed"));
        assertEquals(200, ok.status(), ok.body().toString());
        assertEquals("APPROVED", ok.data().path("status").asText());
    }

    @Test
    void scenarioLibrary_velocityStructuringSanctionsFalsePositive_observeThenEnforce_casesAndReports() throws Exception {
        String carol = staff("Carol Compliance", "COMPLIANCE"), dave = staff("Dave Compliance", "COMPLIANCE");

        // Lists: a sanctions list with a person (name + phone) and a company (name + registration number).
        assertEquals(200, call("POST", "/api/v1/admin/compliance/screening/lists", carol,
                Map.of("code", "TEST-SANCTIONS", "name", "Test sanctions list", "source", "scenario fixture")).status());
        Res load = call("POST", "/api/v1/admin/compliance/screening/lists/TEST-SANCTIONS/load", carol, Map.of("entries", List.of(
                Map.of("entryRef", "S-1", "entityType", "PERSON", "name", "John Smith", "identifiers", List.of("+263 77 999 0001")),
                Map.of("entryRef", "S-2", "entityType", "ORG", "name", "Acme Smuggling Limited", "identifiers", List.of("REG-ACME-9")))));
        assertEquals(200, load.status(), load.body().toString());
        assertEquals(1, load.data().path("version").asInt());

        // Every rule starts in OBSERVE.
        for (JsonNode r : call("GET", "/api/v1/admin/compliance/rules", carol, null).data()) assertEquals("OBSERVE", r.path("mode").asText(), r.toString());

        // 1. VELOCITY: threshold lowered to 3 per hour (two officers); the 4th payment alerts, all 4 go ahead (observe).
        changeRule(carol, dave, "VELOCITY_COUNT_1H", Map.of("params", Map.of("windowMinutes", 60, "maxCount", 3), "reason", "scenario: tighten for test"));
        String fast = phone();
        for (int i = 0; i < 3; i++) initiate(fast, 500);
        assertTrue(alerts("VELOCITY_COUNT_1H", fast).isEmpty(), "3 in an hour is within the limit");
        initiate(fast, 500);
        List<Map<String, Object>> v = alerts("VELOCITY_COUNT_1H", fast);
        assertEquals(1, v.size());
        assertEquals("OBSERVE", v.get(0).get("mode"));
        assertEquals("ALERT", v.get(0).get("action_taken"), "observe mode never blocks");
        assertEquals("MEDIUM", v.get(0).get("severity"));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE customer_phone = ?", Integer.class, fast));
        initiate(fast, 500);
        assertEquals(1, alerts("VELOCITY_COUNT_1H", fast).size(), "one alert per subject and day; repeats are counted");
        assertEquals(2, ((Number) alerts("VELOCITY_COUNT_1H", fast).get(0).get("hits")).intValue());

        // 2. STRUCTURING: three payments just under the USD 1,000 threshold within a day -> HIGH alert and a case.
        String structurer = phone();
        initiate(structurer, 85_000);
        initiate(structurer, 90_000);
        assertTrue(alerts("STRUCTURING_24H", structurer).isEmpty());
        initiate(structurer, 95_000);
        List<Map<String, Object>> st = alerts("STRUCTURING_24H", structurer);
        assertEquals(1, st.size());
        assertEquals("HIGH", st.get(0).get("severity"));
        Map<String, Object> stCase = openCase("PHONE:" + NameMatcher.identifier(structurer));
        assertNotNull(stCase, "HIGH alerts open a case");
        assertEquals("IN_CASE", alerts("STRUCTURING_24H", structurer).get(0).get("status"));
        assertTrue(alerts("LARGE_TRANSACTION", structurer).isEmpty(), "under the threshold: not a large transaction");

        // 3. SANCTIONS HIT (identifier): the listed person's phone pays -> HIGH alert, score 1.0, case.
        String listed = "+263779990001";
        initiate(listed, 1_000);
        List<Map<String, Object>> s = alerts("SANCTIONS_CUSTOMER", listed);
        assertEquals(1, s.size());
        assertEquals(0, new java.math.BigDecimal("1.000").compareTo((java.math.BigDecimal) s.get(0).get("score")));
        assertNotNull(openCase("PHONE:" + NameMatcher.identifier(listed)));

        // 4. FALSE POSITIVE (name): a registered customer "Jon Smyth" -> alert and case; cleared by two officers; not alerted again.
        String jon = phone();
        user("Jon Smyth", "CUSTOMER", jon);
        initiate(jon, 1_000);
        List<Map<String, Object>> fp = alerts("SANCTIONS_CUSTOMER", jon);
        assertEquals(1, fp.size(), "a similar name is flagged for a person to check");
        long fpCase = ((Number) openCase("PHONE:" + NameMatcher.identifier(jon)).get("id")).longValue();
        assertEquals(200, call("POST", "/api/v1/admin/compliance/cases/" + fpCase + "/assign", carol, Map.of()).status());
        assertEquals(400, call("POST", "/api/v1/admin/compliance/cases/" + fpCase + "/propose", carol, Map.of("outcome", "FALSE_POSITIVE", "rationale", "no")).status(),
                "a rationale is required");
        assertEquals(200, call("POST", "/api/v1/admin/compliance/cases/" + fpCase + "/propose", carol,
                Map.of("outcome", "FALSE_POSITIVE", "rationale", "Different person: national ID and date of birth differ from the listed John Smith")).status());
        Res selfDecide = call("POST", "/api/v1/admin/compliance/cases/" + fpCase + "/decide", carol, Map.of("accept", true));
        assertEquals(409, selfDecide.status(), "four eyes: " + selfDecide.body());
        Res closed = call("POST", "/api/v1/admin/compliance/cases/" + fpCase + "/decide", dave, Map.of("accept", true, "note", "agree"));
        assertEquals(200, closed.status(), closed.body().toString());
        assertEquals("CLOSED_FALSE_POSITIVE", closed.data().path("status").asText());
        assertEquals("CLOSED", alerts("SANCTIONS_CUSTOMER", jon).get(0).get("status"));
        initiate(jon, 1_000);
        assertEquals(1, alerts("SANCTIONS_CUSTOMER", jon).size(), "cleared for that entry: no new alert");
        assertNull(jdbc.queryForList("SELECT id FROM risk_cases WHERE subject_key = ? AND status NOT LIKE 'CLOSED%'", Long.class,
                "PHONE:" + NameMatcher.identifier(jon)).stream().findFirst().orElse(null), "and no new case");

        // 5. MERCHANT SCREENING: a listed company applies -> case; KYB approval is held.
        int n = SEQ.incrementAndGet();
        Res signup = call("POST", "/api/v1/merchant-portal/signup", null, Map.of("businessName", "Acme Smuggling (Private) Limited",
                "registrationNumber", "REG-ACME-9", "businessCategory", "RETAIL", "contactEmail", "acme" + n + "@example.test",
                "contactPhone", "+263772" + String.format("%06d", n % 1_000_000), "ownerFullName", "Peter Banda", "password", "Str0ng!Pass"));
        assertEquals(201, signup.status(), signup.body().toString());
        long merchantId = signup.data().path("id").asLong();
        assertNotNull(openCase("MERCHANT:" + merchantId), "screened at signup");
        String olivia = staff("Olivia Ops", "OPS");
        Map<String, Boolean> checklist = new java.util.LinkedHashMap<>();
        for (String item : zw.co.payport.gateway.merchant.MerchantPlatformService.KYB_CHECKLIST) checklist.put(item, true);
        Res held = call("POST", "/api/v1/admin/platform/kyb/" + merchantId + "/recommend", olivia,
                Map.of("recommendation", "APPROVE", "checklist", checklist, "note", "docs ok"));
        assertEquals(409, held.status(), held.body().toString());
        assertTrue(held.body().toString().contains("Compliance review pending"));
        assertFalse(held.body().toString().toLowerCase().contains("sanction"), "no tipping off");

        // 6. OBSERVE -> ENFORCE (two officers): the listed customer is now refused before paying, neutrally.
        changeRule(carol, dave, "SANCTIONS_CUSTOMER", Map.of("mode", "ENFORCE", "reason", "screening validated in observe mode"));
        String ref = String.format("PP-WEB-20261016-%06d", SEQ.incrementAndGet() % 1_000_000);
        Res refused = call("POST", "/api/v1/transactions/initiate", null, Map.of("reference", ref, "productTypeCode", "MERCHANT_CHECKOUT",
                "recipientIdentifier", listed, "amountCents", 1_000, "currency", "USD", "customerPhone", listed, "channel", "WEB"));
        assertEquals(403, refused.status(), refused.body().toString());
        assertEquals("PAYMENT_NOT_ALLOWED", refused.code());
        assertTrue(refused.body().path("message").asText().contains(ref), "quotes the reference for support");
        assertFalse(refused.body().toString().toLowerCase().matches(".*(sanction|watch|list|screen).*"), "no tipping off: " + refused.body());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE reference = ?", Integer.class, ref), "nothing was created");
        assertEquals("BLOCKED", alerts("SANCTIONS_CUSTOMER", listed).get(0).get("action_taken"));
        assertDoesNotThrow(() -> initiate(phone(), 1_000), "everyone else still pays");

        // 7. CONFIRMED case -> STR draft -> approved by someone other than the preparer -> marked submitted.
        long stId = ((Number) stCase.get("id")).longValue();
        call("POST", "/api/v1/admin/compliance/cases/" + stId + "/notes", carol, Map.of("note", "Three payments of 850-950 USD within an hour, no business reason."));
        assertEquals(200, call("POST", "/api/v1/admin/compliance/cases/" + stId + "/propose", carol,
                Map.of("outcome", "CONFIRMED_SUSPICIOUS", "rationale", "Pattern consistent with structuring below the reporting threshold")).status());
        Res confirmed = call("POST", "/api/v1/admin/compliance/cases/" + stId + "/decide", dave, Map.of("accept", true));
        assertEquals("CLOSED_CONFIRMED", confirmed.data().path("status").asText(), confirmed.body().toString());
        long strId = confirmed.data().path("reports").get(0).path("id").asLong();
        Res str = call("GET", "/api/v1/admin/compliance/reports/" + strId, carol, null);
        assertEquals("STR", str.data().path("report_type").asText());
        JsonNode payload = JSON.readTree(str.data().path("payload").asText());
        assertEquals(3, payload.path("transactions").size(), "the subject's payments are in the report");
        assertEquals(ComplianceService.FIU_FORMAT, payload.path("format").asText());
        assertEquals(409, call("POST", "/api/v1/admin/compliance/reports/" + strId + "/approve", carol, null).status(), "the preparer cannot approve");
        assertEquals(409, call("POST", "/api/v1/admin/compliance/reports/" + strId + "/submitted", dave, Map.of("fiuReference", "X")).status(), "approve first");
        assertEquals("APPROVED", call("POST", "/api/v1/admin/compliance/reports/" + strId + "/approve", dave, null).data().path("status").asText());
        assertEquals(400, call("POST", "/api/v1/admin/compliance/reports/" + strId + "/submitted", dave, Map.of("fiuReference", " ")).status());
        Res filed = call("POST", "/api/v1/admin/compliance/reports/" + strId + "/submitted", dave, Map.of("fiuReference", "FIU-ACK-2026-0042"));
        assertEquals("SUBMITTED", filed.data().path("status").asText());

        // 8. LARGE TRANSACTION -> LOW alert; CTR draft lists it once completed.
        String big = phone();
        String bigRef = initiate(big, 150_000);
        assertEquals("LOW", jdbc.queryForObject("SELECT severity FROM risk_alerts WHERE rule_code = 'LARGE_TRANSACTION' AND transaction_reference = ?",
                String.class, bigRef), "a CTR candidate, never blocked");
        jdbc.update("UPDATE transactions SET status = 'COMPLETED', completed_at = NOW() WHERE reference = ?", bigRef); // delivery is not under test here
        Res ctr = call("POST", "/api/v1/admin/compliance/reports/ctr?from=" + LocalDate.now() + "&to=" + LocalDate.now(), carol, null);
        assertEquals(200, ctr.status(), ctr.body().toString());
        assertTrue(ctr.data().path("payload").asText().contains(bigRef));
    }

    @Test
    void accessControls_onlyComplianceSeesComplianceData() throws Exception {
        String carol = staff("Carol Two", "COMPLIANCE"), olivia = staff("Olivia Two", "OPS"), adam = staff("Adam Admin", "ADMIN"),
                fiona = staff("Fiona Finance", "FINANCE"), customer = user("Chipo Customer", "CUSTOMER", phone());
        for (String path : List.of("/api/v1/admin/compliance/alerts", "/api/v1/admin/compliance/cases", "/api/v1/admin/compliance/reports",
                "/api/v1/admin/compliance/rules", "/api/v1/admin/compliance/screening/lists")) {
            assertEquals(200, call("GET", path, carol, null).status(), path);
            for (String other : List.of(olivia, adam, fiona, customer)) assertEquals(403, call("GET", path, other, null).status(), path);
            assertTrue(List.of(401, 403).contains(call("GET", path, null, null).status()), path + " anonymous");
        }
        assertEquals(403, call("POST", "/api/v1/admin/compliance/rules/VELOCITY_COUNT_1H/change-requests", adam,
                Map.of("mode", "OFF", "reason", "admin tries to switch monitoring off")).status(), "even ADMIN cannot change rules");
        assertEquals(403, call("POST", "/api/v1/admin/compliance/screening/lists", olivia, Map.of("code", "X", "name", "x", "source", "x")).status());

        // Dispute timelines: for the people who work disputes, not finance or customers.
        assertEquals(200, call("GET", "/api/v1/admin/dispute-timelines", olivia, null).status());
        assertEquals(200, call("GET", "/api/v1/admin/dispute-timelines", carol, null).status());
        assertEquals(403, call("GET", "/api/v1/admin/dispute-timelines", fiona, null).status());
        assertEquals(403, call("GET", "/api/v1/admin/dispute-timelines", customer, null).status());
    }

    @Test
    void disputeTimelines_deadlinesSetOnOpen_breachVisible_acknowledgementRecorded() throws Exception {
        jdbc.update("INSERT INTO merchants (business_name, registration_number, contact_email, contact_phone, status, default_currency) "
                + "VALUES ('Dispute Co', ?, ?, '+263770000001', 'ACTIVE', 'USD')", "REG-D-" + SEQ.incrementAndGet(), "d" + SEQ.get() + "@x.test");
        long m = jdbc.queryForObject("SELECT MAX(id) FROM merchants", Long.class);
        jdbc.update("INSERT INTO checkout_sessions (merchant_id, reference, amount_cents, currency, status, expires_at, created_at, updated_at, livemode) "
                + "VALUES (?, ?, 5000, 'USD', 'PAID', NOW(), NOW(), NOW(), TRUE)", m, "sess_d_" + SEQ.get());
        long session = jdbc.queryForObject("SELECT MAX(id) FROM checkout_sessions", Long.class);
        long id = disputes.open(new DisputeService.OpenDisputeRequest(session, 5000L, "Customer says not delivered")).getId();
        assertNotNull(jdbc.queryForObject("SELECT ack_due_at FROM disputes WHERE id = ?", java.sql.Timestamp.class, id), "deadlines set on open");

        jdbc.update("UPDATE disputes SET created_at = NOW() - INTERVAL 3 DAY, ack_due_at = NOW() - INTERVAL 1 DAY, resolve_due_at = NOW() + INTERVAL 27 DAY WHERE id = ?", id);
        String olivia = staff("Olivia Three", "OPS");
        JsonNode row = null;
        for (JsonNode d : call("GET", "/api/v1/admin/dispute-timelines", olivia, null).data()) if (d.path("id").asLong() == id) row = d;
        assertNotNull(row);
        assertEquals("BREACHED", row.path("acknowledgement").asText());
        assertEquals("ON_TRACK", row.path("resolution").asText());
        Res ack = call("POST", "/api/v1/admin/dispute-timelines/" + id + "/acknowledge", olivia, null);
        assertEquals("MET_LATE", ack.data().path("acknowledgement").asText(), ack.body().toString());
        assertEquals(409, call("POST", "/api/v1/admin/dispute-timelines/" + id + "/acknowledge", olivia, null).status());
    }
}
