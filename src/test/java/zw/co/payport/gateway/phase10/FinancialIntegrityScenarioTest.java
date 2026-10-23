package zw.co.payport.gateway.phase10;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import zw.co.payport.gateway.agents.AgentService;
import zw.co.payport.gateway.channels.ChannelService;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.ledger.LedgerClient;
import zw.co.payport.gateway.markets.MarketRegistry;
import zw.co.payport.gateway.model.request.InitiateTransactionRequest;
import zw.co.payport.gateway.platform.Web;
import zw.co.payport.gateway.risk.UnSanctionsList;
import zw.co.payport.gateway.service.EmailService;
import zw.co.payport.gateway.service.SmsNotificationService;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;
import zw.co.payport.gateway.wallets.WalletPaymentService;
import zw.co.payport.gateway.wallets.WalletService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 10: every money-moving operation stays balanced, atomic and safe under concurrency, retries
 * and failures, on MySQL 8 with the real ledger procedures. After each scenario the books are checked:
 * each wallet's ledger balance equals its recorded operations, no wallet or agent float is negative,
 * every journal balances per currency, and ledger_integrity_report() is empty.
 *
 * Product lines are switched on directly in the test database (the portal path, with two compliance
 * officers and an administrator, is proven in Phase8CompletionScenarioTest).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FinancialIntegrityScenarioTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("DB_URL", MYSQL::getJdbcUrl);
        r.add("DB_USERNAME", MYSQL::getUsername);
        r.add("DB_PASSWORD", MYSQL::getPassword);
        r.add("JWT_SECRET", () -> "phase10-test-secret-phase10-test-secret-0123456789abcdef");
        r.add("payport.payments.resolver.enabled", () -> "false");
        r.add("payport.payments.watchdog.enabled", () -> "false");
        r.add("payport.fulfilment.worker.enabled", () -> "false");
        r.add("payport.push.enabled", () -> "false");
        r.add("payport.ussd.secret", () -> "ussd-test-secret");
        zw.co.payport.gateway.support.UnListStub.register(r);
    }

    @MockitoBean SmsNotificationService sms;
    @MockitoBean EmailService email;

    @Autowired JdbcTemplate jdbc;
    @Autowired WalletService wallets;
    @Autowired WalletPaymentService walletPayments;
    @Autowired AgentService agents;
    @Autowired LedgerClient ledger;
    @Autowired ChannelService channels;
    @Autowired MarketRegistry markets;
    @Autowired UnSanctionsList unList;
    @Autowired TransactionOrchestratorService orchestrator;

    static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000));
    static final String PIN = "4826";
    static final String PIN_HASH = new BCryptPasswordEncoder().encode(PIN);

    @BeforeEach
    void on() {
        zw.co.payport.gateway.support.UnListStub.STATUS.set(200);
        unList.refresh("test");
        markets.reload();
        jdbc.update("UPDATE service_lines SET licence_status = 'NOT_REQUIRED', decided_by = 'test / test', decided_at = NOW(6), enabled = TRUE "
                + "WHERE code IN ('WALLETS','AGENTS','FX_CONVERSION')");
        jdbc.update("UPDATE platform_channels SET enabled = TRUE WHERE code = 'AGENT'");
        jdbc.update("UPDATE payment_methods SET is_active = TRUE WHERE code = 'WALLET'");
        jdbc.update("UPDATE product_types SET is_active = TRUE WHERE code = 'WALLET_TOPUP'");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM fx_rates WHERE status = 'ACTIVE' AND base_ccy = 'USD' AND quote_ccy = 'ZWG'", Integer.class) == 0) {
            jdbc.update("INSERT INTO fx_rates (base_ccy, quote_ccy, rate, spread_bps, source, status, created_by, created_at, approved_by, approved_at) "
                    + "VALUES ('USD','ZWG',26.5,100,'test','ACTIVE','fin.a',NOW(6),'fin.b',NOW(6))");
        }
        channels.refresh();
    }

    @AfterEach
    void booksBalance() {
        for (Map<String, Object> w : jdbc.queryForList("SELECT id FROM wallets")) {
            WalletService.Wallet wallet = wallets.get(((Number) w.get("id")).longValue());
            Long ops = jdbc.queryForObject("SELECT COALESCE(SUM(IF(direction = 'CR', amount_minor, -amount_minor)), 0) FROM wallet_operations WHERE wallet_id = ?",
                    Long.class, wallet.id());
            long ledgerBalance = wallets.balance(wallet);
            assertEquals(ops.longValue(), ledgerBalance, "wallet " + wallet.ref() + ": ledger balance equals its operations");
            assertTrue(ledgerBalance >= 0, "wallet " + wallet.ref() + " never negative");
        }
        Long negative = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_accounts a JOIN ledger_balances b ON b.account_id = a.id "
                + "WHERE a.category IN ('CUSTOMER_WALLET','AGENT_FLOAT') AND b.balance < 0", Long.class);
        assertEquals(0L, negative, "no wallet or agent float below zero");
        Long unbalanced = jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT entry_id, currency FROM ledger_lines GROUP BY entry_id, currency "
                + "HAVING SUM(IF(direction = 'DEBIT', amount_minor, -amount_minor)) <> 0) x", Long.class);
        assertEquals(0L, unbalanced, "every journal balances per currency");
        assertTrue(ledger.integrityReport().isEmpty(), ledger.integrityReport().toString());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    record Holder(long id, String phone, WalletService.Wallet usd) {}

    Holder holder(long fundMinor) {
        String phone = String.format("+26377%07d", SEQ.incrementAndGet() % 10_000_000);
        jdbc.update("INSERT INTO users (full_name, phone, pin_hash, role, is_active, is_verified) VALUES ('Holder', ?, ?, 'CUSTOMER', TRUE, TRUE)", phone, PIN_HASH);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE phone = ?", Long.class, phone);
        WalletService.Wallet w = wallets.open(id, phone, "USD", "test");
        if (fundMinor > 0) wallets.creditTopUp(ref(), w.ref(), "USD", fundMinor, "ECOCASH", "test");
        return new Holder(id, phone, w);
    }

    static String ref() {
        return String.format("PP-WEB-20261022-%06d", SEQ.incrementAndGet() % 1_000_000);
    }

    long balance(WalletService.Wallet w) {
        return wallets.balance(w);
    }

    /** Runs the task on n threads released at the same instant; returns each result or the exception thrown. */
    static List<Object> race(int n, Callable<Object> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                try { return task.call(); } catch (Exception e) { return e; }
            }));
        }
        go.countDown();
        List<Object> out = new ArrayList<>();
        for (Future<Object> f : fs) out.add(f.get(120, TimeUnit.SECONDS));
        pool.shutdown();
        return out;
    }

    static long ok(List<Object> results) {
        return results.stream().filter(r -> !(r instanceof Throwable)).count();
    }

    /** Only business refusals are acceptable failures; a deadlock, pool timeout or SQL error is a defect. */
    static void onlyBusinessRefusals(List<Object> results) {
        for (Object r : results) {
            if (r instanceof Throwable t) {
                boolean business = t instanceof Web.InsufficientFundsException || t instanceof IllegalStateException
                        || t instanceof IllegalArgumentException || t instanceof SecurityException;
                assertTrue(business, "unexpected failure: " + t);
            }
        }
    }

    long merchant() {
        jdbc.update("INSERT INTO merchants (business_name, registration_number, contact_email, contact_phone, status, default_currency) "
                + "VALUES ('Agent shop', ?, ?, '+263770000000', 'ACTIVE', 'USD')", "REG-P10-" + SEQ.incrementAndGet(), "a" + SEQ.get() + "@p10.test");
        return jdbc.queryForObject("SELECT MAX(id) FROM merchants", Long.class);
    }

    /** An onboarded agent with the given float (four-eyes deposit). */
    long agent(long floatMinor) {
        String phone = String.format("+26378%07d", SEQ.incrementAndGet() % 10_000_000);
        long id = ((Number) agents.onboard("Agent " + phone, null, phone, null, "Harare", null, null, "staff:ops").get("id")).longValue();
        long dep = ((Number) agents.recordDeposit(id, floatMinor, "USD", "DEP-" + SEQ.incrementAndGet(), "agent:" + id).get("id")).longValue();
        agents.decideDeposit(dep, true, "on statement", "staff:fin");
        return id;
    }

    // ─── scenarios ───────────────────────────────────────────────────────────

    @Test
    void overspendingAcrossOperationTypesAtOnce_isImpossible() throws Exception {
        Holder carl = holder(1_000), dana = holder(0);
        wallets.open(carl.id(), carl.phone(), "ZWG", "test");
        List<String> quotes = new ArrayList<>();
        for (int i = 0; i < 10; i++) quotes.add((String) wallets.quoteConversion(carl.id(), "USD", "ZWG", 100).get("quoteRef"));
        AtomicInteger next = new AtomicInteger();
        // 10 transfers and 10 conversions of 1.00 each race for 10.00.
        List<Object> results = race(20, () -> {
            int i = next.getAndIncrement();
            return i % 2 == 0 ? wallets.transfer(carl.id(), dana.phone(), "USD", 100, null, "test")
                    : wallets.convert(carl.id(), quotes.get(i / 2), "test");
        });
        onlyBusinessRefusals(results);
        assertEquals(10, ok(results), "money for exactly ten 1.00 operations");
        assertEquals(0, balance(carl.usd()));
    }

    @Test
    void opposingTransfers_conserveMoney_withoutDeadlocks() throws Exception {
        Holder a = holder(1_000), b = holder(1_000);
        AtomicInteger next = new AtomicInteger();
        List<Object> results = race(30, () -> next.getAndIncrement() % 2 == 0
                ? wallets.transfer(a.id(), b.phone(), "USD", 100, null, "test")
                : wallets.transfer(b.id(), a.phone(), "USD", 100, null, "test"));
        onlyBusinessRefusals(results);
        assertEquals(2_000, balance(a.usd()) + balance(b.usd()), "money is conserved");
    }

    @Test
    void retriedTransfer_sameClientRef_movesMoneyOnce() throws Exception {
        Holder a = holder(1_000), b = holder(0);
        List<Object> results = race(10, () -> wallets.transfer(a.id(), b.phone(), "USD", 300, "retry-1", "test"));
        onlyBusinessRefusals(results);
        assertEquals(700, balance(a.usd()), "debited once");
        assertEquals(300, balance(b.usd()), "credited once");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM wallet_operations WHERE op_ref = ? AND kind = 'P2P_OUT'", Integer.class, "P2P:" + a.id() + ":retry-1"));
    }

    @Test
    void topUpDeliveredConcurrently_creditsOnce() throws Exception {
        Holder a = holder(0);
        String r = ref();
        List<Object> results = race(10, () -> wallets.creditTopUp(r, a.usd().ref(), "USD", 700, "ONEMONEY", "test"));
        onlyBusinessRefusals(results);
        assertEquals(10, ok(results), "every delivery attempt gets the same answer");
        assertEquals(700, balance(a.usd()));
        assertEquals(1, results.stream().distinct().count(), "one ledger entry");
    }

    @Test
    void cashOutCode_redeemedByManyAgentsAtOnce_paysOutOnce() throws Exception {
        Holder c = holder(5_000);
        long agentId = agent(10_000);
        String code = (String) agents.issueCashOutCode(c.phone(), PIN, "USD", 2_000).get("code");
        List<Object> results = race(10, () -> agents.cashOut(agentId, c.phone(), code, "agent:" + agentId));
        onlyBusinessRefusals(results);
        assertEquals(1, ok(results), "a code is single-use");
        assertEquals(3_000, balance(c.usd()));
        long expectedFloat = 10_000 + 2_000 + 2_000 * 100 / 10_000;
        assertEquals(expectedFloat, ((Map<?, ?>) agents.view(agents.get(agentId)).get("float")).get("USD"));
    }

    @Test
    void agentFloat_cannotBeOverspentByConcurrentCashIns() throws Exception {
        long agentId = agent(100_000);
        Holder c = holder(0);
        wallets.setTier(c.usd().ref(), "TIER3", "KYC evidence on file", "staff:comp");   // TIER1 holds at most 500.00
        List<Object> results = race(20, () -> agents.cashIn(agentId, c.phone(), "USD", 10_000, null, "agent:" + agentId));
        onlyBusinessRefusals(results);
        // Each cash-in costs the float 10,000 less a 50 commission (0.50%): 100,000 funds exactly ten.
        assertEquals(10, ok(results));
        assertEquals(500L, ((Map<?, ?>) agents.view(agents.get(agentId)).get("float")).get("USD"));
        assertEquals(100_000, balance(c.usd()));
    }

    String walletPaidPayment(Holder h, long amount) throws Exception {
        String r = ref();
        InitiateTransactionRequest init = new InitiateTransactionRequest();
        init.setReference(r);
        init.setProductTypeCode("AIRTIME_ECONET");
        init.setRecipientIdentifier("0771234567");
        init.setAmountCents(amount);
        init.setCurrency("USD");
        init.setCustomerPhone(h.phone());
        init.setChannel("WEB");
        orchestrator.initiateTransaction(init, "127.0.0.1");
        Map<String, Object> paid = walletPayments.payTransaction(h.phone(), r, PIN);
        assertEquals("PAYMENT_CONFIRMED", paid.get("status"), paid.toString());
        return r;
    }

    @Test
    void refundRacedTenTimes_creditsOnce_andClosesThePaymentOnce() throws Exception {
        Holder h = holder(2_000);
        String r = walletPaidPayment(h, 500);
        assertEquals(1_500, balance(h.usd()));
        jdbc.update("UPDATE transactions SET status = 'FULFILMENT_FAILED' WHERE reference = ?", r);
        AtomicInteger n = new AtomicInteger();
        List<Object> results = race(10, () -> walletPayments.refundToWallet(r, "vendor failed", "staff:fin" + n.incrementAndGet()));
        onlyBusinessRefusals(results);
        assertEquals(1, ok(results), "exactly one refund");
        assertEquals(2_000, balance(h.usd()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM wallet_operations WHERE reference = ? AND kind = 'REFUND'", Integer.class, r));
        assertEquals("REFUNDED", jdbc.queryForObject("SELECT status FROM transactions WHERE reference = ?", String.class, r));
    }

    @Test
    void sameConversionQuote_executedOnce() throws Exception {
        Holder h = holder(1_000);
        WalletService.Wallet zwg = wallets.open(h.id(), h.phone(), "ZWG", "test");
        String q = (String) wallets.quoteConversion(h.id(), "USD", "ZWG", 400).get("quoteRef");
        List<Object> results = race(10, () -> wallets.convert(h.id(), q, "test"));
        onlyBusinessRefusals(results);
        assertEquals(600, balance(h.usd()), "debited once");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM wallet_operations WHERE kind = 'FX_IN' AND reference = ?", Integer.class, q));
        assertEquals(10_494, balance(zwg), "4.00 USD × 26.5 less 1% = 104.94 ZWG, once");
    }

    @Test
    void freeze_stopsSpendingImmediately_andRacingSpendsLeaveConsistentBooks() throws Exception {
        Holder a = holder(2_000), b = holder(0);
        wallets.setStatus(a.usd().ref(), "FROZEN", "fraud report", "staff:ops");
        List<Object> frozen = race(10, () -> wallets.transfer(a.id(), b.phone(), "USD", 100, null, "test"));
        onlyBusinessRefusals(frozen);
        assertEquals(0, ok(frozen), "a frozen wallet sends nothing");
        assertEquals(2_000, balance(a.usd()));
        wallets.setStatus(a.usd().ref(), "ACTIVE", "cleared", "staff:comp");

        // Freeze while transfers are in flight: whatever committed before the freeze stands, nothing after it.
        AtomicInteger next = new AtomicInteger();
        List<Object> mixed = race(21, () -> next.getAndIncrement() == 10
                ? wallets.setStatus(a.usd().ref(), "FROZEN", "second report", "staff:ops")
                : wallets.transfer(a.id(), b.phone(), "USD", 100, null, "test"));
        onlyBusinessRefusals(mixed);
        long sent = jdbc.queryForObject("SELECT COUNT(*) FROM wallet_operations WHERE wallet_id = ? AND kind = 'P2P_OUT'", Long.class, a.usd().id());
        assertEquals(2_000 - 100 * sent, balance(a.usd()));
        assertThrows(IllegalStateException.class, () -> wallets.transfer(a.id(), b.phone(), "USD", 100, null, "test"), "frozen afterwards");
    }

    @Test
    void failureAfterTheJournalIsPosted_rollsBackEverything() throws Exception {
        Holder a = holder(1_000), b = holder(0);
        // Fault injection: the database refuses the operation row AFTER ledger_post has run in the same transaction.
        // (Created as root: with binary logging on, MySQL refuses CREATE TRIGGER to the application user.)
        asRoot("DROP TRIGGER IF EXISTS p10_fail_wallet_op");
        asRoot("CREATE TRIGGER p10_fail_wallet_op BEFORE INSERT ON wallet_operations FOR EACH ROW "
                + "BEGIN IF NEW.counterparty = '" + b.usd().ref() + "' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected failure'; END IF; END");
        try {
            assertThrows(Exception.class, () -> wallets.transfer(a.id(), b.phone(), "USD", 400, "boom-1", "test"));
        } finally {
            asRoot("DROP TRIGGER IF EXISTS p10_fail_wallet_op");
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries WHERE idempotency_key = ?", Integer.class, "P2P:" + a.id() + ":boom-1"),
                "the journal was rolled back with the operation");
        assertEquals(1_000, balance(a.usd()));
        assertEquals(0, balance(b.usd()));
        // The same request succeeds afterwards and only once.
        wallets.transfer(a.id(), b.phone(), "USD", 400, "boom-1", "test");
        wallets.transfer(a.id(), b.phone(), "USD", 400, "boom-1", "test");
        assertEquals(600, balance(a.usd()));
        assertEquals(400, balance(b.usd()));
    }

    @Test
    void topUpWhoseDeliveryOutcomeWasLost_isResolvedByQuery_neverCreditedTwice() throws Exception {
        Holder h = holder(0);
        // Case 1: the wallet was credited, then the instance died before recording the delivery.
        String r1 = topUpConfirmed(h, 800);
        jdbc.update("UPDATE transactions SET status = 'FULFILMENT_PENDING' WHERE reference = ?", r1);
        String rail = jdbc.queryForObject("SELECT payment_provider_code FROM transactions WHERE reference = ?", String.class, r1);
        wallets.creditTopUp(r1, h.usd().ref(), "USD", 800, rail, "SYSTEM:wallet-topup");
        assertEquals(TransactionOrchestratorService.FulfilmentOutcome.Kind.DELIVERED, orchestrator.resolveUnknownFulfilment(r1).kind());
        assertEquals(800, balance(h.usd()));
        assertEquals(TransactionStatus.COMPLETED.name(), jdbc.queryForObject("SELECT status FROM transactions WHERE reference = ?", String.class, r1));

        // Case 2: the instance died before crediting: the query says NOT_FOUND, so delivering again is safe, once.
        String r2 = topUpConfirmed(h, 300);
        jdbc.update("UPDATE transactions SET status = 'FULFILMENT_PENDING' WHERE reference = ?", r2);
        assertEquals(TransactionOrchestratorService.FulfilmentOutcome.Kind.FAILED, orchestrator.resolveUnknownFulfilment(r2).kind(), "NOT_FOUND is a definite non-delivery");
        assertEquals(800, balance(h.usd()), "not credited by the query itself");
        // Ops retry (the HTTP route is covered in FulfilmentJobScenarioTest); two workers pick it up.
        jdbc.update("UPDATE transactions SET status = 'FULFILMENT_RETRY' WHERE reference = ? AND status IN ('FULFILMENT_FAILED','FULFILMENT_RETRY')", r2);
        orchestrator.executeFulfilment(r2);
        orchestrator.executeFulfilment(r2);
        assertEquals(1_100, balance(h.usd()), "credited exactly once");
        assertEquals(TransactionStatus.COMPLETED.name(), jdbc.queryForObject("SELECT status FROM transactions WHERE reference = ?", String.class, r2));
    }

    String topUpConfirmed(Holder h, long amount) throws Exception {
        String r = ref();
        InitiateTransactionRequest init = new InitiateTransactionRequest();
        init.setReference(r);
        init.setProductTypeCode("WALLET_TOPUP");
        init.setRecipientIdentifier(h.usd().ref());
        init.setAmountCents(amount);
        init.setCurrency("USD");
        init.setCustomerPhone(h.phone());
        init.setChannel("WEB");
        orchestrator.initiateTransaction(init, "127.0.0.1");
        orchestrator.simulateTestPayment(r, true);
        return r;
    }

    static void asRoot(String sql) throws Exception {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             java.sql.Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
