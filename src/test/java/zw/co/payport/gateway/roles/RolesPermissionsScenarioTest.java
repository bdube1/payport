package zw.co.payport.gateway.roles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
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

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Owner requests 2026-10-25, over HTTP on MySQL 8:
 *  - the ID number given at wallet registration is stored and shows on the customer record;
 *  - every permission is listed; a new role gets exactly the permissions ticked; view reads, manage changes;
 *  - built-in roles behave as before until edited, then follow their permissions; reset restores them;
 *  - the administrator role never loses users and roles; a role in use cannot be deleted;
 *  - the staging enable-everything script switches every service, channel, currency and network on.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RolesPermissionsScenarioTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("DB_URL", MYSQL::getJdbcUrl);
        r.add("DB_USERNAME", MYSQL::getUsername);
        r.add("DB_PASSWORD", MYSQL::getPassword);
        r.add("JWT_SECRET", () -> "roles-test-secret-roles-test-secret-0123456789abcdef");
        r.add("payport.payments.resolver.enabled", () -> "false");
        r.add("payport.payments.watchdog.enabled", () -> "false");
        r.add("payport.fulfilment.worker.enabled", () -> "false");
        r.add("payport.push.enabled", () -> "false");
        r.add("payport.environment", () -> "staging");
        zw.co.payport.gateway.support.UnListStub.register(r);
    }

    @MockitoBean SmsNotificationService sms;
    @MockitoBean EmailService email;

    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired UserDetailsService users;
    @Autowired DataSource dataSource;
    @Value("${local.server.port}") int port;

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000));

    record Res(int status, JsonNode body) {
        JsonNode data() { return body.path("data"); }
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

    String staff(String role, String customRole) {
        String phone = phone();
        jdbc.update("INSERT INTO users (full_name, phone, pin_hash, role, custom_role, is_active, is_verified, email, must_change_password) "
                        + "VALUES (?, ?, 'x', ?, ?, TRUE, TRUE, ?, FALSE)",
                "Staff " + role, phone, role, customRole, "staff" + SEQ.incrementAndGet() + "@payport.test");
        return jwt.generateToken(users.loadUserByUsername(phone));
    }

    static List<String> texts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(x -> out.add(x.asText()));
        return out;
    }

    @Test
    void idNumber_storedAtRegistration_andShownOnTheCustomerRecord() throws Exception {
        String admin = staff("ADMIN", null);
        String phone = phone();
        Res reg = call("POST", "/api/v1/auth/register", null, Map.of("fullName", "Rudo Moyo", "phone", phone, "pin", "4821", "idNumber", "63-123456 a 42"));
        assertEquals(201, reg.status(), reg.body().toString());
        assertEquals("63-123456A42", jdbc.queryForObject("SELECT id_number FROM users WHERE phone = ?", String.class, phone));
        assertEquals("63-123456A42", reg.data().path("user").path("idNumber").asText());

        Res list = call("GET", "/api/v1/admin/users", admin, null);
        assertEquals(200, list.status());
        boolean found = false;
        for (JsonNode u : list.data()) if (phone.equals(u.path("phone").asText())) { found = true; assertEquals("63-123456A42", u.path("idNumber").asText()); }
        assertTrue(found, "customer listed with ID number");

        Res st = call("GET", "/api/v1/admin/customer-statement?phone=" + java.net.URLEncoder.encode(phone, "UTF-8"), admin, null);
        assertEquals(200, st.status(), st.body().toString());
        assertEquals("63-123456A42", st.data().path("idNumber").asText());
        assertEquals("Rudo Moyo", st.data().path("customerName").asText());

        assertEquals(400, call("POST", "/api/v1/auth/register", null, Map.of("fullName", "Bad Id", "phone", phone(), "pin", "4821", "idNumber", "!!")).status());
    }

    @Test
    void customRole_getsExactlyItsPermissions_viewReads_manageChanges() throws Exception {
        String admin = staff("ADMIN", null);
        Res catalog = call("GET", "/api/v1/admin/roles/catalog", admin, null);
        assertEquals(200, catalog.status(), catalog.body().toString());
        int features = 0;
        for (JsonNode g : catalog.data()) features += g.path("features").size();
        assertEquals(PermissionCatalog.FEATURES.size(), features);

        Res roles = call("GET", "/api/v1/admin/roles", admin, null);
        List<String> codes = new ArrayList<>();
        roles.data().forEach(r -> codes.add(r.path("code").asText()));
        assertTrue(codes.containsAll(List.of("ADMIN", "OPS", "FINANCE", "COMPLIANCE")), codes.toString());

        Res created = call("POST", "/api/v1/admin/roles", admin, Map.of("name", "Recon Clerk " + SEQ.incrementAndGet(),
                "permissions", List.of("reconciliation.view", "ledger.manage")));
        assertEquals(200, created.status(), created.body().toString());
        String code = created.data().path("code").asText();
        assertTrue(texts(created.data().path("permissions")).contains("ledger.view"), "manage includes view");

        // assigned through the users API by its code
        Res user = call("POST", "/api/v1/admin/users", admin, Map.of("fullName", "Clerk One", "phone", phone(), "email", "clerk" + SEQ.incrementAndGet() + "@payport.test",
                "pin", "Clerk#Pass2026!", "role", code));
        assertEquals(201, user.status(), user.body().toString());
        assertEquals(code, user.data().path("customRole").asText());

        String clerk = staff("OPS", code);
        Res me = call("GET", "/api/v1/admin/roles/me", clerk, null);
        assertEquals(200, me.status(), me.body().toString());
        List<String> routes = texts(me.data().path("routes"));
        assertTrue(routes.containsAll(List.of("/reconciliation", "/ledger")), routes.toString());
        assertFalse(routes.contains("/users"));
        assertFalse(routes.contains("/transactions"));

        assertEquals(200, call("GET", "/api/v1/admin/reconciliation/statements/runs", clerk, null).status(), "view reads");
        assertEquals(403, call("POST", "/api/v1/admin/reconciliation/statements/upload", clerk, Map.of()).status(), "view cannot change");
        assertEquals(200, call("GET", "/api/v1/admin/ledger/accounts", clerk, null).status());
        assertEquals(403, call("GET", "/api/v1/admin/users", clerk, null).status());
        assertEquals(403, call("GET", "/api/v1/admin/transactions-search", clerk, null).status());
        assertEquals(403, call("GET", "/api/v1/admin/roles", clerk, null).status());

        // in use → cannot be deleted; once moved, it can
        assertEquals(409, call("DELETE", "/api/v1/admin/roles/" + code, admin, null).status());
        jdbc.update("UPDATE users SET custom_role = NULL WHERE custom_role = ?", code);
        assertEquals(200, call("DELETE", "/api/v1/admin/roles/" + code, admin, null).status());
        assertEquals(403, call("GET", "/api/v1/admin/ledger/accounts", clerk, null).status(), "back to plain Operations: no ledger");
    }

    @Test
    void builtInRoles_unchangedUntilEdited_resetRestores_adminKeepsUsersAndRoles() throws Exception {
        String admin = staff("ADMIN", null);
        String ops = staff("OPS", null);
        assertEquals(200, call("GET", "/api/v1/admin/transactions-search", ops, null).status());
        assertEquals(403, call("GET", "/api/v1/admin/roles/catalog", ops, null).status());
        int anon = call("GET", "/api/v1/admin/roles/me", null, null).status();
        assertTrue(anon == 401 || anon == 403, "anonymous: " + anon);

        List<String> perms = new ArrayList<>(PermissionCatalog.defaults("OPS"));
        perms.removeIf(p -> p.startsWith("transactions."));
        Res edited = call("PUT", "/api/v1/admin/roles/OPS", admin, Map.of("name", "Operations", "permissions", perms));
        assertEquals(200, edited.status(), edited.body().toString());
        assertEquals(403, call("GET", "/api/v1/admin/transactions-search", ops, null).status(), "edited role follows its permissions");
        assertFalse(texts(call("GET", "/api/v1/admin/roles/me", ops, null).data().path("routes")).contains("/transactions"));

        assertEquals(200, call("POST", "/api/v1/admin/roles/OPS/reset", admin, null).status());
        assertEquals(200, call("GET", "/api/v1/admin/transactions-search", ops, null).status());

        Res a = call("PUT", "/api/v1/admin/roles/ADMIN", admin, Map.of("name", "Administrator", "permissions", List.of("dashboard.view")));
        assertEquals(200, a.status());
        assertTrue(texts(a.data().path("permissions")).containsAll(List.of("users.manage", "roles.manage")));
        assertEquals(200, call("GET", "/api/v1/admin/users", admin, null).status());
        assertEquals(200, call("POST", "/api/v1/admin/roles/ADMIN/reset", admin, null).status());
        assertEquals(409, call("DELETE", "/api/v1/admin/roles/ADMIN", admin, null).status(), "built-in roles stay");

        String customer = phone();
        call("POST", "/api/v1/auth/register", null, Map.of("fullName", "Cust Omer", "phone", customer, "pin", "4821"));
        String ct = jwt.generateToken(users.loadUserByUsername(customer));
        assertEquals(403, call("GET", "/api/v1/admin/roles/me", ct, null).status());
    }

    @Test
    void testPhone_drivesTheRealWhatsAppAndUssdMenus() throws Exception {
        String ops = staff("OPS", null);
        Res u = call("POST", "/api/v1/admin/ussd/simulate", ops, Map.of("phone", "263771234567", "path", ""));
        assertEquals(200, u.status(), u.body().toString());
        assertFalse(u.data().path("text").asText().isBlank());
        String session = u.data().path("session").asText();
        assertEquals(200, call("POST", "/api/v1/admin/ussd/simulate", ops, Map.of("phone", "263771234567", "session", session, "path", "1")).status());
        Res w = call("POST", "/api/v1/admin/whatsapp/simulate", ops, Map.of("phone", "263771234567", "text", "hi"));
        assertEquals(200, w.status(), w.body().toString());
        assertTrue(w.data().path("reply").asText().contains("MENU"), w.body().toString());
    }

    @Test
    void stagingEnableEverythingScript_switchesEverythingOn() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new FileSystemResource("scripts/staging/enable-everything-for-testing.sql"));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM platform_channels WHERE NOT enabled", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM service_lines WHERE NOT enabled", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM market_currencies WHERE NOT live", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM payment_methods WHERE NOT is_active", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ussd_networks WHERE NOT enabled OR short_code <> '*699#'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM product_types p WHERE NOT EXISTS "
                + "(SELECT 1 FROM product_currencies x WHERE x.product_type_id = p.id AND x.currency = 'ZWG')", Integer.class));
    }
}
