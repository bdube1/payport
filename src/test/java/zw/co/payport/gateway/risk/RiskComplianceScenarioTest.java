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
import zw.co.payport.gateway.risk.UnSanctionsList;
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
        zw.co.payport.gateway.support.UnListStub.register(r);
    }

    @Autowired UnSanctionsList unList;

    @org.junit.jupiter.api.BeforeEach
    void currentSanctionsList() {
        zw.co.payport.gateway.support.UnListStub.STATUS.set(200);
        assertTrue(java.util.Set.of("NEW_VERSION", "UNCHANGED").contains(unList.refresh("test").outcome()));
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

        // 2. STRUCTURING: three payments just under the USD 5,000 reporting threshold within a day -> HIGH alert and a case.
        String structurer = phone();
        initiate(structurer, 420_000);
        initiate(structurer, 450_000);
        assertTrue(alerts("STRUCTURING_24H", structurer).isEmpty());
        initiate(structurer, 480_000);
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
        call("POST", "/api/v1/admin/compliance/cases/" + stId + "/notes", carol, Map.of("note", "Three payments of 4,200-4,800 USD within an hour, no business reason."));
        assertEquals(200, call("POST", "/api/v1/admin/compliance/cases/" + stId + "/propose", carol,
                Map.of("outcome", "CONFIRMED_SUSPICIOUS", "rationale", "Pattern consistent with structuring below the reporting threshold")).status());
        Res confirmed = call("POST", "/api/v1/admin/compliance/cases/" + stId + "/decide", dave, Map.of("accept", true));
        assertEquals("CLOSED_CONFIRMED", confirmed.data().path("status").asText(), confirmed.body().toString());
        long strId = confirmed.data().path("reports").get(0).path("id").asLong();
        Res str = call("GET", "/api/v1/admin/compliance/reports/" + strId, carol, null);
        assertEquals("STR", str.data().path("report_type").asText());
        assertEquals("DRAFT", str.data().path("status").asText(), "a report is a draft until approved");
        assertEquals(3, str.data().path("fields").path("transactions").size(), "the subject's payments are in the report");
        assertEquals("OPEN", str.data().path("deadline").asText(), "due 3 working days after the suspicion was formed");
        assertFalse(str.data().path("due_at").isNull());
        assertTrue(str.data().path("missing_fields").asText().contains("narrative"), "manual fields still to complete");
        assertTrue(str.data().path("rendered").asText().contains("<report_code>STR</report_code>"), "goAML-structured output");

        assertEquals(409, call("POST", "/api/v1/admin/compliance/reports/" + strId + "/approve", carol, null).status(), "the preparer cannot approve");
        Res notDesignated = call("POST", "/api/v1/admin/compliance/reports/" + strId + "/approve", dave, null);
        assertEquals(409, notDesignated.status());
        assertTrue(notDesignated.body().toString().contains("REPORT_APPROVER"), notDesignated.body().toString());

        // Dave is designated REPORT_APPROVER: requested by an admin, approved by a compliance officer other than Dave.
        String adam = staff("Adam Admin", "ADMIN");
        String davePhone = jdbc.queryForObject("SELECT phone FROM users WHERE full_name = 'Dave Compliance' ORDER BY id DESC LIMIT 1", String.class);
        Res grant = call("POST", "/api/v1/admin/compliance-authorities/requests", adam,
                Map.of("holder", "staff:" + davePhone, "authority", "REPORT_APPROVER", "reason", "Dave appointed MLRO"));
        assertEquals(200, grant.status(), grant.body().toString());
        long grantId = grant.data().path("approvalId").asLong();
        assertEquals(409, call("POST", "/api/v1/admin/compliance/authority-requests/" + grantId + "/decide", dave, Map.of("approve", true)).status(),
                "nobody approves their own designation");
        assertEquals(200, call("POST", "/api/v1/admin/compliance/authority-requests/" + grantId + "/decide", carol, Map.of("approve", true)).status());

        Res incomplete = call("POST", "/api/v1/admin/compliance/reports/" + strId + "/approve", dave, null);
        assertEquals(409, incomplete.status(), "missing required fields block approval");
        assertTrue(incomplete.body().toString().contains("Complete the report first"));
        Map<String, Object> manual = new java.util.LinkedHashMap<>();
        // The goAML reporting-entity ID is a compliance parameter (two officers set it once PayPort is registered).
        Res idReq = call("POST", "/api/v1/admin/compliance/parameters/FIU.REPORTING_ENTITY_ID/change-requests", carol,
                Map.of("value", "RE-TEST-0001", "confirm", true, "source", "goAML registration letter (test)", "reason", "registered on goAML"));
        assertEquals(200, idReq.status(), idReq.body().toString());
        assertEquals(200, call("POST", "/api/v1/admin/compliance/parameter-changes/" + idReq.data().path("approvalId").asLong() + "/decide", dave,
                Map.of("approve", true)).status());
        manual.put("officer_position", "Compliance Officer");
        manual.put("subject_name", "Unknown guest payer");
        manual.put("subject_id_number", "Unknown: guest wallet; MSISDN only");
        manual.put("relationship", "Guest payer since 2026-10-16; mobile wallet payments only");
        manual.put("attempted_only", "No: all three payments were initiated");
        manual.put("red_flags", "Repeated transactions just below the reporting threshold (structuring)");
        manual.put("narrative", "Between 10:00 and 11:00 on 16 October 2026 the same mobile number initiated three payments of USD 4,200, 4,500 and 4,800, "
                + "each just below the USD 5,000 reporting threshold, with no stated business purpose. The pattern is consistent with structuring to avoid threshold reporting.");
        manual.put("action_taken", "Customer placed under enhanced monitoring; no further action that could tip off the customer");
        assertEquals(400, call("PATCH", "/api/v1/admin/compliance/reports/" + strId + "/fields", carol, Map.of("case_reference", "X")).status(),
                "automatic fields cannot be overwritten");
        Res filled = call("PATCH", "/api/v1/admin/compliance/reports/" + strId + "/fields", carol, manual);
        assertEquals(200, filled.status(), filled.body().toString());
        assertTrue(filled.data().path("missing_fields").isNull() || filled.data().path("missing_fields").asText().isEmpty(), filled.data().path("missing_fields").toString());
        assertTrue(filled.data().path("rendered").asText().contains("structuring"));

        assertEquals(409, call("POST", "/api/v1/admin/compliance/reports/" + strId + "/submitted", dave, Map.of("fiuReference", "X")).status(), "approve first");
        assertEquals("APPROVED", call("POST", "/api/v1/admin/compliance/reports/" + strId + "/approve", dave, null).data().path("status").asText());
        assertEquals(409, call("PATCH", "/api/v1/admin/compliance/reports/" + strId + "/fields", carol, Map.of("narrative", "changed")).status(),
                "an approved report cannot be edited");
        assertEquals(400, call("POST", "/api/v1/admin/compliance/reports/" + strId + "/submitted", dave, Map.of("fiuReference", " ")).status());
        Res filed = call("POST", "/api/v1/admin/compliance/reports/" + strId + "/submitted", dave, Map.of("fiuReference", "FIU-ACK-2026-0042"));
        assertEquals("SUBMITTED", filed.data().path("status").asText());

        // The audit trail is intact and records the whole story.
        Res chain = call("GET", "/api/v1/admin/compliance/audit/verify", carol, null);
        assertTrue(chain.data().path("intact").asBoolean(), chain.body().toString());
        Res trail = call("GET", "/api/v1/admin/compliance/audit?subject=fiu_report:" + strId, carol, null);
        assertTrue(trail.body().toString().contains("FIU_REPORT_APPROVED") && trail.body().toString().contains("FIU_REPORT_SUBMITTED"));

        // 8. LARGE TRANSACTION -> LOW alert; CTR draft lists it once completed.
        String big = phone();
        String bigRef = initiate(big, 600_000);
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
    void disputeTimelines_fromSourcedParameters_workingDays_progressUpdatesAndOutcomeNotice() throws Exception {
        jdbc.update("INSERT INTO merchants (business_name, registration_number, contact_email, contact_phone, status, default_currency) "
                + "VALUES ('Dispute Co', ?, ?, '+263770000001', 'ACTIVE', 'USD')", "REG-D-" + SEQ.incrementAndGet(), "d" + SEQ.get() + "@x.test");
        long m = jdbc.queryForObject("SELECT MAX(id) FROM merchants", Long.class);
        jdbc.update("INSERT INTO checkout_sessions (merchant_id, reference, amount_cents, currency, status, expires_at, created_at, updated_at, livemode) "
                + "VALUES (?, ?, 5000, 'USD', 'PAID', NOW(), NOW(), NOW(), TRUE)", m, "sess_d_" + SEQ.get());
        long session = jdbc.queryForObject("SELECT MAX(id) FROM checkout_sessions", Long.class);
        long id = disputes.open(new DisputeService.OpenDisputeRequest(session, 5000L, "Customer says not delivered")).getId();
        java.sql.Timestamp ack = jdbc.queryForObject("SELECT ack_due_at FROM disputes WHERE id = ?", java.sql.Timestamp.class, id);
        java.sql.Timestamp created = jdbc.queryForObject("SELECT created_at FROM disputes WHERE id = ?", java.sql.Timestamp.class, id);
        assertEquals(48, java.time.Duration.between(created.toLocalDateTime(), ack.toLocalDateTime()).toHours(), "RBZ CPF s6.1.8(a): 48 hours");

        // 10 working days: Monday 2026-12-21 -> skips weekends and the 22, 25, 26 December holidays.
        jdbc.update("UPDATE disputes SET created_at = '2026-12-21 09:00:00', ack_due_at = NULL, resolve_due_at = NULL WHERE id = ?", id);
        String olivia = staff("Olivia Three", "OPS");
        JsonNode row = null;
        for (JsonNode d : call("GET", "/api/v1/admin/dispute-timelines", olivia, null).data()) if (d.path("id").asLong() == id) row = d;
        assertNotNull(row);
        assertEquals("COMPLAINT", row.path("type").asText());
        JsonNode resolve = null;
        for (JsonNode d : row.path("deadlines")) if ("RESOLVE".equals(d.path("kind").asText())) resolve = d;
        assertTrue(resolve.path("dueAt").asText().startsWith("2027-01-05") || resolve.path("dueAt").toString().contains("2027-01-05"), resolve.toString());
        assertEquals("SOURCED", resolve.path("ruleStatus").asText());
        assertTrue(resolve.path("ruleSource").asText().contains("6.1.8(c)"));

        // Late: progress updates every 7 days are now due; acknowledgement was late too.
        jdbc.update("UPDATE disputes SET created_at = NOW() - INTERVAL 20 DAY, ack_due_at = NOW() - INTERVAL 18 DAY, resolve_due_at = NOW() - INTERVAL 6 DAY WHERE id = ?", id);
        row = null;
        for (JsonNode d : call("GET", "/api/v1/admin/dispute-timelines", olivia, null).data()) if (d.path("id").asLong() == id) row = d;
        assertEquals("BREACHED", row.path("acknowledgement").asText());
        assertTrue(row.toString().contains("PROGRESS_UPDATE"), "progress updates required once the resolution date has passed");
        assertEquals("MET_LATE", call("POST", "/api/v1/admin/dispute-timelines/" + id + "/acknowledge", olivia, null).data().path("acknowledgement").asText());
        assertEquals(409, call("POST", "/api/v1/admin/dispute-timelines/" + id + "/acknowledge", olivia, null).status());
        assertEquals(400, call("POST", "/api/v1/admin/dispute-timelines/" + id + "/updates", olivia, Map.of("kind", "PROGRESS_UPDATE", "note", "")).status());
        assertEquals(200, call("POST", "/api/v1/admin/dispute-timelines/" + id + "/updates", olivia,
                Map.of("kind", "PROGRESS_UPDATE", "note", "Told the customer we are waiting for the merchant's delivery proof")).status());
        assertEquals(409, call("POST", "/api/v1/admin/dispute-timelines/" + id + "/updates", olivia, Map.of("kind", "OUTCOME_NOTICE", "note", "x")).status(),
                "outcome only after resolution");
        disputes.resolve(id, "WON", "Delivery proven");
        Res notice = call("POST", "/api/v1/admin/dispute-timelines/" + id + "/updates", olivia, Map.of("kind", "OUTCOME_NOTICE", "note", "SMS and email with the outcome"));
        assertTrue(notice.body().toString().contains("OUTCOME_NOTICE"));
        assertTrue(call("GET", "/api/v1/admin/dispute-timelines/settings", olivia, null).body().toString().contains("DISPUTE.ACK_HOURS"));
    }

    @Test
    void unConsolidatedList_verifiedUpdates_auditableScreening_neverClearWhenOutdated() throws Exception {
        String carol = staff("Carol UN", "COMPLIANCE");
        Res status = call("GET", "/api/v1/admin/compliance/screening/status", carol, null);
        assertTrue(status.data().path("complete").asBoolean(), status.body().toString());
        JsonNode un = null;
        for (JsonNode l : status.data().path("lists")) if ("UN-CONSOLIDATED".equals(l.path("code").asText())) un = l;
        assertNotNull(un);
        assertEquals(3, un.path("entry_count").asInt(), "2 individuals and 1 entity from the UN-format file");
        assertEquals(64, un.path("source_sha256").asText().length());
        assertEquals("2026-10-15T09:00:01.123-04:00", un.path("source_generated_at").asText());
        assertTrue(status.data().path("unFetches").get(0).path("final_url_host").asText().equals("127.0.0.1"), "followed the redirect to the file host");

        // Unchanged file: no new version, just "checked".
        int v = un.path("version").asInt();
        assertEquals("UNCHANGED", call("POST", "/api/v1/admin/compliance/screening/lists/UN-CONSOLIDATED/refresh", carol, null).data().path("outcome").asText());

        // A registered customer whose name is a listed alias -> potential match, recorded with the list version used.
        String temba = phone();
        user("Temba Testperson", "CUSTOMER", temba);
        initiate(temba, 1_000);
        assertEquals(1, alerts("SANCTIONS_CUSTOMER", temba).size());
        Res runs = call("GET", "/api/v1/admin/compliance/screening/runs?subjectKey=PHONE:" + NameMatcher.identifier(temba), carol, null);
        assertEquals("POTENTIAL_MATCH", runs.data().get(0).path("outcome").asText());
        assertTrue(runs.data().get(0).path("list_versions").asText().contains("UN-CONSOLIDATED"), "auditable: which list version was used");
        String clean = phone();
        initiate(clean, 1_000);
        assertEquals("CLEAR", jdbc.queryForObject("SELECT outcome FROM screening_runs WHERE subject_key = ? ORDER BY id DESC LIMIT 1", String.class,
                "PHONE:" + NameMatcher.identifier(clean)));

        // A truncated/tampered file (1 entry instead of 3) is quarantined, never activated.
        byte[] original = zw.co.payport.gateway.support.UnListStub.BODY.get();
        try {
            String one = new String(original, java.nio.charset.StandardCharsets.UTF_8)
                    .replaceAll("(?s)<INDIVIDUAL>\\s*<DATAID>9990002.*?</INDIVIDUAL>", "")
                    .replaceAll("(?s)<ENTITIES>.*</ENTITIES>", "<ENTITIES/>");
            zw.co.payport.gateway.support.UnListStub.BODY.set(one.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Res q = call("POST", "/api/v1/admin/compliance/screening/lists/UN-CONSOLIDATED/refresh", carol, null);
            assertEquals("QUARANTINED", q.data().path("outcome").asText(), q.body().toString());
            assertEquals(v, jdbc.queryForObject("SELECT version FROM screening_lists WHERE code = 'UN-CONSOLIDATED'", Integer.class), "the active version is kept");

            // Not XML at all -> FAILED.
            zw.co.payport.gateway.support.UnListStub.BODY.set("<html>maintenance</html>".getBytes());
            assertEquals("FAILED", call("POST", "/api/v1/admin/compliance/screening/lists/UN-CONSOLIDATED/refresh", carol, null).data().path("outcome").asText());
            // Source down -> FAILED.
            zw.co.payport.gateway.support.UnListStub.STATUS.set(503);
            assertEquals("FAILED", call("POST", "/api/v1/admin/compliance/screening/lists/UN-CONSOLIDATED/refresh", carol, null).data().path("outcome").asText());
        } finally {
            zw.co.payport.gateway.support.UnListStub.BODY.set(original);
            zw.co.payport.gateway.support.UnListStub.STATUS.set(200);
        }

        // The list has not been checked successfully within the allowed age -> screening is INCOMPLETE, never CLEAR.
        jdbc.update("UPDATE screening_lists SET last_checked_at = NOW() - INTERVAL 3 DAY WHERE code = 'UN-CONSOLIDATED'");
        Res stale = call("GET", "/api/v1/admin/compliance/screening/status", carol, null);
        assertFalse(stale.data().path("complete").asBoolean());
        assertTrue(stale.data().path("reason").asText().contains("UN-CONSOLIDATED"));
        String later = phone();
        initiate(later, 1_000);   // payments proceed (SCREENING.INCOMPLETE_PAYMENT_ACTION = ALERT) but are not cleared
        assertEquals("INCOMPLETE", jdbc.queryForObject("SELECT outcome FROM screening_runs WHERE subject_key = ? ORDER BY id DESC LIMIT 1", String.class,
                "PHONE:" + NameMatcher.identifier(later)));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM risk_alerts WHERE rule_code = 'SCREENING_UNAVAILABLE' AND status <> 'CLOSED'", Integer.class) >= 1);

        // A merchant cannot be approved while screening is incomplete.
        int n = SEQ.incrementAndGet();
        Res signup = call("POST", "/api/v1/merchant-portal/signup", null, Map.of("businessName", "Clean Traders " + n, "registrationNumber", "REG-CLEAN-" + n,
                "businessCategory", "RETAIL", "contactEmail", "clean" + n + "@example.test", "contactPhone", "+263773" + String.format("%06d", n % 1_000_000),
                "ownerFullName", "Mary Clean", "password", "Str0ng!Pass"));
        long merchantId = signup.data().path("id").asLong();
        Map<String, Boolean> checklist = new java.util.LinkedHashMap<>();
        for (String item : zw.co.payport.gateway.merchant.MerchantPlatformService.KYB_CHECKLIST) checklist.put(item, true);
        Res held = call("POST", "/api/v1/admin/platform/kyb/" + merchantId + "/recommend", staff("Olivia UN", "OPS"),
                Map.of("recommendation", "APPROVE", "checklist", checklist, "note", "docs ok"));
        assertEquals(409, held.status());
        assertTrue(held.body().toString().contains("screening could not be completed") || held.body().toString().contains("Sanctions screening could not be completed"), held.body().toString());

        // Fresh again -> the same merchant can be recommended.
        assertEquals("UNCHANGED", call("POST", "/api/v1/admin/compliance/screening/lists/UN-CONSOLIDATED/refresh", carol, null).data().path("outcome").asText());
        assertEquals(200, call("POST", "/api/v1/admin/platform/kyb/" + merchantId + "/recommend", staff("Olivia UN2", "OPS"),
                Map.of("recommendation", "APPROVE", "checklist", checklist, "note", "docs ok")).status());

        // Every fetch is on record, and the audit chain is intact.
        assertTrue(call("GET", "/api/v1/admin/compliance/screening/status", carol, null).data().path("unFetches").size() >= 5);
        assertTrue(call("GET", "/api/v1/admin/compliance/audit/verify", carol, null).data().path("intact").asBoolean());
    }

    @Test
    void newRulesStartInObserve_andOnlyApprovedRulesCanEverBlock() throws Exception {
        String carol = staff("Carol Rules", "COMPLIANCE"), dave = staff("Dave Rules", "COMPLIANCE");
        Res create = call("POST", "/api/v1/admin/compliance/rules", carol, Map.of("code", "TX_LIMIT_ZWG_TEST", "name", "Test limit", "type", "TX_LIMIT",
                "params", Map.of("maxMinor", Map.of("USD", 100)), "action", "BLOCK", "severity", "LOW", "source", "test", "reason", "scenario"));
        assertEquals(200, create.status(), create.body().toString());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM risk_rules WHERE code = 'TX_LIMIT_ZWG_TEST'", Integer.class), "nothing until approved");
        assertEquals(200, call("POST", "/api/v1/admin/compliance/rule-changes/" + create.data().path("approvalId").asLong() + "/decide", dave,
                Map.of("approve", true)).status());
        assertEquals("OBSERVE", jdbc.queryForObject("SELECT mode FROM risk_rules WHERE code = 'TX_LIMIT_ZWG_TEST'", String.class), "created in observe mode");
        String p = phone();
        assertDoesNotThrow(() -> initiate(p, 5_000), "observe never blocks");
        assertEquals(1, alerts("TX_LIMIT_ZWG_TEST", p).size());

        // The database itself refuses ENFORCE for a rule nobody approved.
        jdbc.update("UPDATE risk_rules SET approved_by = NULL WHERE code = 'TX_LIMIT_ZWG_TEST'");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE risk_rules SET mode = 'ENFORCE' WHERE code = 'TX_LIMIT_ZWG_TEST'"));
        jdbc.update("UPDATE risk_rules SET mode = 'OFF' WHERE code = 'TX_LIMIT_ZWG_TEST'");

        // Parameters show their source and status; confirming needs two officers.
        Res params = call("GET", "/api/v1/admin/compliance/parameters", carol, null);
        JsonNode str = null;
        for (JsonNode x : params.data()) if ("STR.DEADLINE_WORKING_DAYS".equals(x.path("param_key").asText())) str = x;
        assertEquals("SOURCED", str.path("status").asText());
        assertTrue(str.path("source").asText().contains("3.105"));
        Res conf = call("POST", "/api/v1/admin/compliance/parameters/STR.DEADLINE_WORKING_DAYS/change-requests", carol, Map.of("confirm", true, "reason", "checked against guideline"));
        assertEquals(409, call("POST", "/api/v1/admin/compliance/parameter-changes/" + conf.data().path("approvalId").asLong() + "/decide", carol,
                Map.of("approve", true)).status());
        assertEquals(200, call("POST", "/api/v1/admin/compliance/parameter-changes/" + conf.data().path("approvalId").asLong() + "/decide", dave,
                Map.of("approve", true)).status());
        assertEquals("CONFIRMED", jdbc.queryForObject("SELECT status FROM compliance_parameters WHERE param_key = 'STR.DEADLINE_WORKING_DAYS'", String.class));
    }
}
