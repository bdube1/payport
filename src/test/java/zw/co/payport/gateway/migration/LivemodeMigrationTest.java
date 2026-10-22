package zw.co.payport.gateway.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real MySQL 8 + real Flyway: upgrades a database at V31 that holds every historical
 * kind of checkout session to V32, and checks the R1 livemode backfill.
 * Runs in CI (needs Docker); skipped where Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class LivemodeMigrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0");

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    @Test
    void backfillClassifiesHistoricalSessionsByWhatActuallyHappened() throws SQLException {
        flyway("31").migrate();

        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO merchants (id,business_name,registration_number,contact_email,contact_phone,status) "
                    + "VALUES (1,'Acme','R1','a@x','+263771111111','ACTIVE')");
            st.execute("INSERT INTO api_keys (id,merchant_id,environment,public_key,secret_key_hash) "
                    + "VALUES (10,1,'LIVE','pk_live','h1'),(11,1,'TEST','pk_test','h2')");
            st.execute("SET @pt = (SELECT id FROM product_types WHERE code='MERCHANT_CHECKOUT')");
            st.execute("INSERT INTO transactions (reference,status,channel,product_type_id,recipient_identifier,amount_cents,provider_reference) VALUES "
                    + "('PP-API-20261001-000001','COMPLETED','API',@pt,'x',500,'ECO-REAL-1'),"
                    + "('PP-API-20261001-000002','COMPLETED','API',@pt,'x',600,'TEST_SIM_1'),"
                    + "('PP-API-20261001-000003','COMPLETED','API',@pt,'x',700,'TEST_SIM_2'),"
                    + "('PP-API-20261001-000004','COMPLETED','API',@pt,'x',800,'ECO-REAL-2')");
            st.execute("INSERT INTO checkout_sessions (id,merchant_id,reference,amount_cents,status,transaction_reference,api_key_id,expires_at) VALUES "
                    + "(1,1,'live-key-paid',500,'PAID','PP-API-20261001-000001',10,NOW()),"
                    + "(2,1,'test-key-paid',600,'PAID','PP-API-20261001-000002',11,NOW()),"
                    + "(3,1,'paylink-simulated',700,'PAID','PP-API-20261001-000003',NULL,NOW()),"
                    + "(4,1,'nokey-real-paid',800,'PAID','PP-API-20261001-000004',NULL,NOW()),"
                    + "(5,1,'nokey-created',900,'CREATED',NULL,NULL,NOW()),"
                    + "(6,1,'test-key-created',100,'CREATED',NULL,11,NOW())");
        }

        flyway("latest").migrate();

        Map<String, Boolean> live = new LinkedHashMap<>();
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT reference, livemode FROM checkout_sessions ORDER BY id")) {
            while (rs.next()) live.put(rs.getString(1), rs.getBoolean(2));

            assertEquals(true, live.get("live-key-paid"));
            assertEquals(false, live.get("test-key-paid"));
            assertEquals(false, live.get("paylink-simulated"), "simulated payment must not be live, so it is never settled");
            assertEquals(true, live.get("nokey-real-paid"), "a real provider payment stays live");
            assertEquals(true, live.get("nokey-created"), "an unpaid payment-link session is paid for real from now on");
            assertEquals(false, live.get("test-key-created"));

            SQLException e = assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO checkout_sessions (merchant_id,reference,amount_cents,expires_at) VALUES (1,'no-mode',100,NOW())"));
            assertTrue(e.getMessage().contains("livemode"), "a session without an explicit mode must be rejected");
        }
    }
}
