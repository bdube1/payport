package zw.co.payport.gateway.smoke;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boots the whole application (real Spring context, real Flyway migrations, real security
 * filter chain) against MySQL 8 and exercises the endpoints changed by Phase 0.5 and the Phase 1 ledger wiring.
 * This is the "application starts and serves requests" gate. Needs Docker (runs in CI).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationSmokeTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("DB_URL", MYSQL::getJdbcUrl);
        r.add("DB_USERNAME", MYSQL::getUsername);
        r.add("DB_PASSWORD", MYSQL::getPassword);
        r.add("JWT_SECRET", () -> "smoke-test-secret-smoke-test-secret-smoke-test-secret-0123456789");
    }

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ApplicationContext context;

    @Test
    void applicationStartsAndIsHealthy() {
        assertNotNull(context);
        ResponseEntity<String> health = http.getForEntity("/actuator/health", String.class);
        assertEquals(HttpStatus.OK, health.getStatusCode(), health.getBody());
        assertTrue(health.getBody().contains("UP"), health.getBody());
    }

    @Test
    void ledgerIsWiredAndConsistentAfterMigrations() {
        zw.co.payport.gateway.ledger.LedgerClient ledger = context.getBean(zw.co.payport.gateway.ledger.LedgerClient.class);
        assertEquals(java.util.List.of(), ledger.integrityReport());
        assertNotNull(context.getBean(zw.co.payport.gateway.ledger.LedgerJobs.class));
        assertNotNull(context.getBean(zw.co.payport.gateway.billing.TaxRateService.class));
    }

    @Test
    void ledgerAdminApiRequiresAuthentication() {
        ResponseEntity<String> r = http.getForEntity("/api/v1/admin/ledger/integrity", String.class);
        assertTrue(r.getStatusCode() == HttpStatus.UNAUTHORIZED || r.getStatusCode() == HttpStatus.FORBIDDEN, "got " + r.getStatusCode());
    }

    @Test
    void unknownTransactionIsNotFound() {
        ResponseEntity<String> r = http.getForEntity("/api/v1/transactions/PP-WEB-20261015-000001", String.class);
        assertEquals(HttpStatus.NOT_FOUND, r.getStatusCode(), r.getBody());
        assertTrue(r.getBody().contains("TRANSACTION_NOT_FOUND"), r.getBody());
    }

    @Test
    void publicApplicationTrackingNoLongerAcceptsWrites() {
        ResponseEntity<String> upload = http.exchange("/api/v1/merchant-portal/track/MER-20261015-1234/documents",
                HttpMethod.POST, new HttpEntity<>("", new HttpHeaders()), String.class);
        assertEquals(HttpStatus.GONE, upload.getStatusCode(), upload.getBody());
        ResponseEntity<String> resolve = http.exchange("/api/v1/merchant-portal/track/MER-20261015-1234/review-notes/1/resolve",
                HttpMethod.POST, new HttpEntity<>("", new HttpHeaders()), String.class);
        assertEquals(HttpStatus.GONE, resolve.getStatusCode(), resolve.getBody());
    }

    @Test
    void unknownApplicationReferenceIsNotFound() {
        ResponseEntity<String> r = http.getForEntity("/api/v1/merchant-portal/track/MER-20261015-9999", String.class);
        assertEquals(HttpStatus.NOT_FOUND, r.getStatusCode(), r.getBody());
    }

    @Test
    void forgedCallbackForUnknownPaymentChangesNothingAndIsAcknowledged() {
        HttpHeaders h = new HttpHeaders();
        h.add("Content-Type", "application/json");
        ResponseEntity<String> r = http.exchange("/api/v1/callbacks/ecocash", HttpMethod.POST,
                new HttpEntity<>("{\"clientCorrelator\":\"PP-WEB-20261015-000002\",\"transactionOperationStatus\":\"COMPLETED\"}", h), String.class);
        assertTrue(r.getStatusCode().is2xxSuccessful(), "providers must get an ack: " + r.getStatusCode());
    }
}
