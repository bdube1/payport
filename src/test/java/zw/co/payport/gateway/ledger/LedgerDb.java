package zw.co.payport.gateway.ledger;

import org.junit.jupiter.api.Assumptions;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Real-database test support for the ledger. Tests run only when a migrated MySQL 8
 * database is supplied (CI job "ledger-db", or locally):
 *   -Dledger.test.appUrl=jdbc:mysql://host:port/db?user=<app user>&password=...   (least-privilege app user)
 *   -Dledger.test.adminUrl=jdbc:mysql://host:port/db?user=<dba>&password=...      (used only to inject faults / kill)
 * Each test uses its own account owners, so tests do not share balances.
 */
final class LedgerDb {

    static final String ENTITY = "PAYPORT_ZW";

    private LedgerDb() {}

    static String appUrl() {
        String url = System.getProperty("ledger.test.appUrl", System.getenv("LEDGER_TEST_APP_URL"));
        Assumptions.assumeTrue(url != null && !url.isBlank(), "ledger.test.appUrl not set: real-database ledger tests skipped");
        return url;
    }

    static String adminUrl() {
        String url = System.getProperty("ledger.test.adminUrl", System.getenv("LEDGER_TEST_ADMIN_URL"));
        Assumptions.assumeTrue(url != null && !url.isBlank(), "ledger.test.adminUrl not set");
        return url;
    }

    static DataSource app() {
        return new Simple(appUrl());
    }

    static DataSource admin() {
        return new Simple(adminUrl());
    }

    static String uid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    static AccountKey live(LedgerCategory c, String owner, String ccy) {
        return AccountKey.of(ENTITY, Book.LIVE, c, owner, ccy);
    }

    /** Calls ledger_post with a raw JSON document (to test what the database itself rejects). */
    static String rawPost(DataSource ds, String json) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (CallableStatement cs = c.prepareCall("{call ledger_post(?, ?, ?)}")) {
                cs.setString(1, json);
                cs.registerOutParameter(2, Types.BIGINT);
                cs.registerOutParameter(3, Types.VARCHAR);
                cs.execute();
                c.commit();
                return cs.getString(3);
            } catch (SQLException e) {
                c.rollback();
                throw LedgerException.fromDatabaseMessage(e.getMessage(), e);
            }
        } catch (SQLException e) {
            throw LedgerException.fromDatabaseMessage(e.getMessage(), e);
        }
    }

    static void exec(DataSource ds, String sql) {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException(e.getErrorCode() + " " + e.getMessage(), e);
        }
    }

    static long queryLong(DataSource ds, String sql) {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement(); var rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Minimal DriverManager-backed DataSource for tests. */
    static final class Simple implements DataSource {
        private final String url;
        Simple(String url) { this.url = url; }
        public Connection getConnection() throws SQLException { return DriverManager.getConnection(url); }
        public Connection getConnection(String u, String p) throws SQLException { return DriverManager.getConnection(url, u, p); }
        public PrintWriter getLogWriter() { return null; }
        public void setLogWriter(PrintWriter out) { }
        public void setLoginTimeout(int seconds) { }
        public int getLoginTimeout() { return 0; }
        public Logger getParentLogger() { return Logger.getGlobal(); }
        public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
        public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
