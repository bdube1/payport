package zw.co.payport.gateway.platform;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import zw.co.payport.gateway.billing.TaxRateService;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Platform services against the real database, as the least-privilege app user. */
@Tag("ledger-db")
class PlatformDbTest {

    private static DataSource ds(String prop) {
        String url = System.getProperty(prop);
        Assumptions.assumeTrue(url != null && !url.isBlank(), prop + " not set");
        return new DataSource() {
            public Connection getConnection() throws SQLException { return DriverManager.getConnection(url); }
            public Connection getConnection(String u, String p) throws SQLException { return DriverManager.getConnection(url, u, p); }
            public PrintWriter getLogWriter() { return null; }
            public void setLogWriter(PrintWriter out) { }
            public void setLoginTimeout(int s) { }
            public int getLoginTimeout() { return 0; }
            public Logger getParentLogger() { return Logger.getGlobal(); }
            public <T> T unwrap(Class<T> i) throws SQLException { throw new SQLException(); }
            public boolean isWrapperFor(Class<?> i) { return false; }
        };
    }

    private final DataSource app = ds("ledger.test.appUrl");
    private final DataSource admin = ds("ledger.test.adminUrl");

    private static String uid() { return UUID.randomUUID().toString().substring(0, 8); }

    private void exec(DataSource d, String sql) throws SQLException {
        try (Connection c = d.getConnection(); Statement s = c.createStatement()) { s.execute(sql); }
    }

    // ── Job leases ───────────────────────────────────────────────────────

    @Test
    void onlyOneInstanceRunsAJobAtATime() throws Exception {
        String job = "test-job-" + uid();
        JobRunner a = new JobRunner(app, "instance-A"), b = new JobRunner(app, "instance-B");
        CountDownLatch insideA = new CountDownLatch(1), releaseA = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<JobRunner.RunRecord> fa = pool.submit(() -> a.runExclusive(job, 60, () -> {
            insideA.countDown();
            try { releaseA.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "A did the work";
        }));
        assertTrue(insideA.await(10, TimeUnit.SECONDS));
        assertEquals(JobRunner.Outcome.SKIPPED_LEASE_HELD, b.runExclusive(job, 60, () -> "B must not run").outcome());
        releaseA.countDown();
        assertEquals(JobRunner.Outcome.SUCCEEDED, fa.get(10, TimeUnit.SECONDS).outcome());
        assertEquals(JobRunner.Outcome.SUCCEEDED, b.runExclusive(job, 60, () -> "B after A").outcome(), "lease released after A finished");
        pool.shutdown();
        assertTrue(a.lastRunSummary(job).orElse("").contains("B after A"));
    }

    @Test
    void anExpiredLeaseFromACrashedInstanceIsTakenOver() throws Exception {
        String job = "crash-job-" + uid();
        JobRunner crashed = new JobRunner(app, "crashed"), survivor = new JobRunner(app, "survivor");
        assertTrue(crashed.acquire(job, 1), "crashed instance took the lease and never released it");
        assertEquals(JobRunner.Outcome.SKIPPED_LEASE_HELD, survivor.runExclusive(job, 60, () -> "x").outcome());
        Thread.sleep(1_500);
        assertEquals(JobRunner.Outcome.SUCCEEDED, survivor.runExclusive(job, 60, () -> "took over").outcome());
    }

    @Test
    void aFailingJobIsRecordedAsFailedAndReleasesTheLease() {
        String job = "fail-job-" + uid();
        JobRunner r = new JobRunner(app, "x");
        JobRunner.RunRecord rec = r.runExclusive(job, 60, () -> { throw new IllegalStateException("boom"); });
        assertEquals(JobRunner.Outcome.FAILED, rec.outcome());
        assertTrue(r.lastRunSummary(job).orElse("").startsWith("FAILED"));
        assertEquals(JobRunner.Outcome.SUCCEEDED, new JobRunner(app, "y").runExclusive(job, 60, () -> "ok").outcome());
    }

    // ── Approvals ────────────────────────────────────────────────────────

    private long newApproval(String requester) throws SQLException {
        try (Connection c = app.getConnection()) {
            return ApprovalStore.request(c, "TEST_ACTION", "subject-" + uid(), "{}", "because", "FINANCE", requester, 1).id();
        }
    }

    private PlatformException decide(long id, String who, Set<String> roles) {
        try (Connection c = app.getConnection()) {
            ApprovalStore.decide(c, id, true, who, roles, "ok");
            return null;
        } catch (PlatformException e) {
            return e;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void nobodyApprovesTheirOwnRequest() throws Exception {
        long id = newApproval("alice");
        assertEquals("APPROVAL_SELF", decide(id, "alice", Set.of("FINANCE", "ADMIN")).code());
    }

    @Test
    void theDatabaseItselfRefusesSelfApproval() throws Exception {
        long id = newApproval("alice");
        SQLException e = assertThrows(SQLException.class, () -> exec(app,
                "UPDATE platform_approvals SET status = 'APPROVED', decided_by = 'alice' WHERE id = " + id));
        assertEquals(3819, e.getErrorCode(), "CHECK chk_papp_four_eyes");
    }

    @Test
    void deciderNeedsTheRequiredRole() throws Exception {
        long id = newApproval("alice");
        assertEquals("APPROVAL_ROLE", decide(id, "bob", Set.of("OPS")).code());
        assertNull(decide(id, "bob", Set.of("FINANCE")));
        assertEquals("APPROVAL_NOT_PENDING", decide(id, "carol", Set.of("FINANCE")).code(), "decided only once");
    }

    @Test
    void expiredRequestsCannotBeApproved() throws Exception {
        long id = newApproval("alice");
        exec(admin, "UPDATE platform_approvals SET expires_at = NOW(6) - INTERVAL 1 MINUTE WHERE id = " + id);
        assertEquals("APPROVAL_EXPIRED", decide(id, "bob", Set.of("FINANCE")).code());
    }

    @Test
    void onlyTheRequesterCanCancel() throws Exception {
        long id = newApproval("alice");
        try (Connection c = app.getConnection()) {
            assertEquals("APPROVAL_NOT_OWNER", assertThrows(PlatformException.class, () -> ApprovalStore.cancel(c, id, "bob")).code());
            ApprovalStore.cancel(c, id, "alice");
        }
        assertEquals("APPROVAL_NOT_PENDING", decide(id, "bob", Set.of("FINANCE")).code());
    }

    // ── Tax rates ────────────────────────────────────────────────────────

    @Test
    void taxRatesAreFourEyesEffectiveDatedAndNeverRewriteHistory() {
        TaxRateService tax = new TaxRateService(app);
        int y = 2100 + new Random().nextInt(5_000); // a year no other run has used
        String ccy = "USD";

        TaxRateService.TaxRate vat = tax.propose("PAYPORT_ZW", "VAT", ccy, 1550, null, false, LocalDate.of(y, 1, 1), "VAT 15.5% per Finance Act", "alice");
        assertEquals("PROPOSED", vat.status());
        assertTrue(tax.rateOn("PAYPORT_ZW", "VAT", ccy, LocalDate.of(y, 6, 1)).stream().noneMatch(r -> r.id() == vat.id()),
                "a proposed rate is not in force");
        assertEquals("APPROVAL_SELF", assertThrows(PlatformException.class, () -> tax.approve(vat.id(), "alice", Set.of("FINANCE"), "")).code());
        assertEquals("APPROVAL_ROLE", assertThrows(PlatformException.class, () -> tax.approve(vat.id(), "bob", Set.of("OPS"), "")).code());
        assertEquals("ACTIVE", tax.approve(vat.id(), "bob", Set.of("FINANCE"), "checked against the Act").status());
        assertEquals(1550, tax.rateOn("PAYPORT_ZW", "VAT", ccy, LocalDate.of(y, 6, 1)).orElseThrow().rateBps());

        TaxRateService.TaxRate next = tax.propose("PAYPORT_ZW", "VAT", ccy, 1600, null, false, LocalDate.of(y + 1, 1, 1), "rate change", "alice");
        tax.approve(next.id(), "carol", Set.of("FINANCE"), "ok");
        assertEquals(1550, tax.rateOn("PAYPORT_ZW", "VAT", ccy, LocalDate.of(y, 12, 31)).orElseThrow().rateBps(), "old dates keep the old rate");
        assertEquals(1600, tax.rateOn("PAYPORT_ZW", "VAT", ccy, LocalDate.of(y + 1, 1, 1)).orElseThrow().rateBps());

        TaxRateService.TaxRate backdated = tax.propose("PAYPORT_ZW", "VAT", ccy, 1400, null, false, LocalDate.of(y, 6, 1), "backdate", "alice");
        assertEquals("TAX_OVERLAP", assertThrows(PlatformException.class, () -> tax.approve(backdated.id(), "bob", Set.of("FINANCE"), "")).code());
        assertEquals("PROPOSED", tax.all("PAYPORT_ZW").stream().filter(r -> r.id() == backdated.id()).findFirst().orElseThrow().status(),
                "the failed approval rolled back entirely (approval request still pending)");

        TaxRateService.TaxRate rejected = tax.propose("PAYPORT_ZW", "IMTT", ccy, 200, 1_000_000L, true, LocalDate.of(y, 1, 1), "IMTT", "alice");
        assertEquals("REJECTED", tax.reject(rejected.id(), "bob", Set.of("FINANCE"), "needs tax advice first").status());
        assertTrue(tax.rateOn("PAYPORT_ZW", "IMTT", ccy, LocalDate.of(y, 6, 1)).isEmpty());
    }

    @Test
    void theDatabaseRefusesActivatingARateWithoutASecondPerson() throws Exception {
        TaxRateService tax = new TaxRateService(app);
        TaxRateService.TaxRate r = tax.propose("PAYPORT_ZW", "VAT", "ZWG", 1550, null, false, LocalDate.of(7000 + new Random().nextInt(900), 1, 1), "x", "alice");
        SQLException e = assertThrows(SQLException.class, () -> exec(app,
                "UPDATE fin_tax_rates SET status = 'ACTIVE', approved_by = 'alice' WHERE id = " + r.id()));
        assertEquals(3819, e.getErrorCode(), "CHECK chk_ftr_four_eyes");
    }
}
