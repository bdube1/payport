package zw.co.payport.gateway.ledger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static zw.co.payport.gateway.ledger.LedgerDb.*;

/**
 * Phase 1 exit criterion E4: the application's database user cannot change ledger history
 * except through the procedures. Requires the grants from scripts/db/grant-least-privilege.sh.
 */
@Tag("ledger-db")
class LedgerPrivilegeTest {

    private static SQLException attempt(String sql) {
        try (Connection c = app().getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
            return null;
        } catch (SQLException e) {
            return e;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO ledger_entries (entry_ref) VALUES ('x')",
            "INSERT INTO ledger_lines (entry_id) VALUES (1)",
            "UPDATE ledger_balances SET balance = balance + 1000000",
            "UPDATE ledger_lines SET amount_minor = 1",
            "UPDATE ledger_entries SET description = 'x'",
            "DELETE FROM ledger_lines",
            "DELETE FROM ledger_entries",
            "DELETE FROM ledger_seals",
            "UPDATE ledger_periods SET status = 'OPEN'",
            "UPDATE ledger_categories SET allow_negative = TRUE",
            "INSERT INTO ref_currencies (code, name, minor_units, enabled) VALUES ('XXX','x',2,TRUE)",
            "TRUNCATE TABLE ledger_lines",
            "TRUNCATE TABLE ledger_entries",
            "DROP TABLE ledger_seals",
            "ALTER TABLE ledger_lines DROP CHECK chk_lline_amount",
            "ALTER TABLE ledger_entries ADD COLUMN x INT",
            "CREATE TRIGGER t BEFORE INSERT ON ledger_lines FOR EACH ROW SET NEW.amount_minor = 1",
            "DROP PROCEDURE ledger_post",
            "CALL ledger_post_internal(NULL, NULL, @a, @b)"
    })
    void appUserCannotBypassTheProcedures(String sql) {
        SQLException e = attempt(sql);
        assertNotNull(e, "should have been denied: " + sql);
        // 1419: with binlog on, MySQL refuses CREATE TRIGGER for non-SUPER users before even checking TRIGGER privilege.
        assertTrue(e.getErrorCode() == 1142 || e.getErrorCode() == 1370 || e.getErrorCode() == 1044 || e.getErrorCode() == 1227
                        || e.getErrorCode() == 1419,
                "expected a privilege error for [" + sql + "] but got " + e.getErrorCode() + " " + e.getMessage());
    }

    @Test
    void appUserHasNoTriggerOrDdlPrivilegeAtAll() throws Exception {
        try (Connection c = app().getConnection(); Statement s = c.createStatement(); var rs = s.executeQuery("SHOW GRANTS")) {
            while (rs.next()) {
                String g = rs.getString(1).toUpperCase();
                for (String forbidden : new String[]{"TRIGGER", "DROP", "ALTER", "CREATE ROUTINE", "ALTER ROUTINE", "SUPER", "ALL PRIVILEGES"}) {
                    assertFalse(g.startsWith("GRANT " + forbidden) || g.contains(", " + forbidden) || g.contains("GRANT " + forbidden + ","),
                            "app user must not hold " + forbidden + ": " + g);
                }
            }
        }
    }

    @Test
    void appUserCanReadLedgerTables() {
        assertNull(attempt("SELECT COUNT(*) FROM ledger_entries"));
        assertNull(attempt("SELECT COUNT(*) FROM ledger_balances"));
    }

    @Test
    void appUserCanStillWriteGatewayTables() {
        // Gateway tables keep normal DML (example: harmless no-op update).
        assertNull(attempt("UPDATE checkout_sessions SET updated_at = updated_at WHERE id = -1"));
    }
}
