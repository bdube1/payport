package zw.co.payport.gateway.recon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import zw.co.payport.gateway.model.request.InitiateTransactionRequest;
import zw.co.payport.gateway.model.request.PayTransactionRequest;
import zw.co.payport.gateway.payments.CallbackInbox;
import zw.co.payport.gateway.payments.SimulatorPaymentAdaptor;
import zw.co.payport.gateway.service.FulfilmentQueueService;
import zw.co.payport.gateway.service.SmsNotificationService;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;
import zw.co.payport.gateway.settlement.SettlementBatchService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 acceptance on real MySQL:
 *  - injected statement differences (missing, duplicate, amount mismatch, status, timing) are all
 *    detected and categorised, and nothing is auto-adjusted;
 *  - settlement batches need approvals (two above the threshold, never the creator);
 *  - re-running a payout instruction after a crash creates no duplicate instruction;
 *  - an item is PAID only with the bank's reference and proof.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Import(ReconSettlementScenarioTest.Providers.class)
class ReconSettlementScenarioTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("DB_URL", MYSQL::getJdbcUrl);
        r.add("DB_USERNAME", MYSQL::getUsername);
        r.add("DB_PASSWORD", MYSQL::getPassword);
        r.add("JWT_SECRET", () -> "phase3-test-secret-phase3-test-secret-phase3-test-0123456789");
        r.add("payport.payments.resolver.enabled", () -> "false");
        r.add("payport.payments.watchdog.enabled", () -> "false");
        r.add("payport.fulfilment.worker.enabled", () -> "false");
        r.add("payport.settlement.mode", () -> "LIVE");
        r.add("payport.settlement.dual-approval-threshold-minor.USD", () -> "1000");
        r.add("payport.settlement.automation.currencies", () -> "ZWG");
        r.add("payport.settlement.automation.auto-approve-limit-minor.ZWG", () -> "100000000");
    }

    @TestConfiguration
    static class Providers {
        @Bean SimulatorPaymentAdaptor simAdaptor() { return new SimulatorPaymentAdaptor("SIM"); }
    }

    @MockitoBean SmsNotificationService sms;
    @MockitoBean FulfilmentQueueService fulfilment; // keep confirmed payments at PAYMENT_CONFIRMED

    @Autowired TransactionOrchestratorService orchestrator;
    @Autowired CallbackInbox inbox;
    @Autowired ReconciliationService recon;
    @Autowired SettlementBatchService settlement;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;
    @Autowired zw.co.payport.gateway.settlement.SettlementAutomation automation;

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000));

    @BeforeEach
    void setUp() {
        jdbc.update("INSERT IGNORE INTO payment_methods (code, name, is_active, display_order) VALUES ('SIM', 'Simulator', TRUE, 90)");
    }

    private String payment(long cents, boolean succeed) throws Exception {
        return payment(cents, succeed, "USD");
    }

    private String payment(long cents, boolean succeed, String currency) throws Exception {
        String ref = String.format("PP-API-20261016-%06d", SEQ.incrementAndGet() % 1_000_000);
        InitiateTransactionRequest req = new InitiateTransactionRequest();
        req.setReference(ref);
        req.setProductTypeCode("ZWG".equals(currency) ? "MERCHANT_CHECKOUT_ZWG" : "MERCHANT_CHECKOUT");
        req.setRecipientIdentifier("+263771234567");
        req.setAmountCents(cents);
        req.setCurrency(currency);
        req.setCustomerPhone("+263771234567");
        req.setChannel("API");
        orchestrator.initiateTransaction(req, "127.0.0.1");
        if (!succeed) SimulatorPaymentAdaptor.script(ref).submit = SimulatorPaymentAdaptor.Submit.REJECT_4XX;
        PayTransactionRequest p = new PayTransactionRequest();
        p.setReference(ref);
        p.setPaymentMethodCode("SIM");
        p.setCustomerPhone("+263771234567");
        orchestrator.submitPayment(p);
        if (succeed) {
            assertEquals(CallbackInbox.Status.APPLIED, inbox.receive("SIM", SimulatorPaymentAdaptor.callbackBody(ref, "SUCCESS", cents, "SIMTX-" + ref),
                    Map.of("x-sim-signature", "valid"), "10.0.0.1").status());
        }
        return ref;
    }

    private static String csvLine(String ref, String providerRef, String amount, String ccy, LocalDateTime at) {
        return ref + "," + providerRef + "," + amount + "," + ccy + "," + at.withNano(0) + ",PAYMENT\n";
    }

    private Map<String, String> statuses(List<String> refs) {
        Map<String, String> m = new java.util.TreeMap<>();
        for (String r : refs) m.put(r, jdbc.queryForObject("SELECT CONCAT(status, '/', COALESCE(failure_reason,'')) FROM transactions WHERE reference = ?", String.class, r));
        return m;
    }

    private int exceptions(String ref, String category) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM recon_exceptions WHERE transaction_reference = ? AND category = ? AND source = 'STATEMENT'",
                Integer.class, ref, category);
    }

    record Statement(long id, List<String> refs) {}

    /** Payments plus a statement with every kind of difference injected. */
    private Statement reconcileInjectedStatement() throws Exception {
        String matched = payment(500, true);
        String amountDiff = payment(700, true);
        String missingInStatement = payment(900, true);
        String failed = payment(300, false);
        String timing = payment(400, true);
        jdbc.update("UPDATE transactions SET payment_confirmed_at = ? WHERE reference = ?", LocalDateTime.now().minusDays(2), timing);
        LocalDateTime now = LocalDateTime.now();

        String csv = "reference,provider_reference,amount,currency,timestamp,type\n"
                + csvLine("", "OPENING", "0.00", "USD", now.minusHours(3)).replace(",PAYMENT", ",FEE")
                + csvLine(matched, "", "5.00", "USD", now.minusHours(2))
                + csvLine(matched, "", "5.00", "USD", now.minusHours(2))            // duplicate
                + csvLine(amountDiff, "", "7.50", "USD", now.minusHours(1))         // amount mismatch
                + csvLine(failed, "", "3.00", "USD", now.minusMinutes(50))          // provider paid, PayPort failed
                + csvLine(timing, "", "4.00", "USD", now.minusMinutes(40))          // PayPort recorded it two days ago
                + csvLine("", "ECO-UNKNOWN-1", "12.00", "USD", now.plusHours(2));  // no such payment at PayPort (also closes the window)
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        var lines = recon.parse("SIM", "CSV", bytes);
        var imported = recon.importStatement("SIM", "sim-statement.csv", bytes, lines, "fin.carol");
        assertFalse(imported.alreadyImported());

        List<String> all = List.of(matched, amountDiff, missingInStatement, failed, timing);
        Map<String, String> before = statuses(all);
        ReconciliationService.RunSummary run = recon.run(imported.statementId(), "fin.carol");

        assertEquals(1, run.counts().get(ReconEngine.Result.MATCHED));
        assertEquals(1, exceptions(matched, "DUPLICATE"));
        assertEquals(1, exceptions(amountDiff, "AMOUNT_MISMATCH"));
        assertEquals(1, exceptions(failed, "STATUS_MISMATCH"));
        assertEquals(1, exceptions(timing, "TIMING"));
        assertEquals(1, exceptions(missingInStatement, "MISSING_IN_STATEMENT"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM recon_exceptions WHERE provider_reference = 'ECO-UNKNOWN-1' AND category = 'MISSING_IN_PAYPORT'", Integer.class));
        assertEquals(before, statuses(all), "nothing auto-adjusts: payments are unchanged");

        // Re-running and re-importing change nothing.
        int total = jdbc.queryForObject("SELECT COUNT(*) FROM recon_exceptions", Integer.class);
        recon.run(imported.statementId(), "fin.dave");
        assertEquals(total, jdbc.queryForObject("SELECT COUNT(*) FROM recon_exceptions", Integer.class));
        assertTrue(recon.importStatement("SIM", "same-file-renamed.csv", bytes, lines, "fin.dave").alreadyImported());
        return new Statement(imported.statementId(), all);
    }

    @Test
    void injectedDifferencesAreDetectedCategorisedAndNothingIsAdjusted() throws Exception {
        reconcileInjectedStatement();
    }

    // ─── settlement ────────────────────────────────────────────────────────────

    private long merchant(String name) {
        jdbc.update("INSERT INTO merchants (business_name, registration_number, contact_email, contact_phone, status, payout_account_type, "
                + "payout_account_details, default_currency) VALUES (?, ?, ?, '+263770000000', 'ACTIVE', 'BANK', ?, 'USD')",
                name, "REG-" + name, name.toLowerCase().replace(' ', '.') + "@example.test", "CBZ 1234567 " + name);
        return jdbc.queryForObject("SELECT MAX(id) FROM merchants", Long.class);
    }

    private long session(long merchantId, String txRef, long amount, long fee, boolean live) {
        return session(merchantId, txRef, amount, fee, live, "USD");
    }

    private long session(long merchantId, String txRef, long amount, long fee, boolean live, String currency) {
        jdbc.update("INSERT INTO checkout_sessions (merchant_id, reference, amount_cents, currency, status, transaction_reference, expires_at, "
                        + "created_at, updated_at, livemode, commission_amount_cents, net_amount_cents) VALUES (?, ?, ?, ?, 'PAID', ?, ?, ?, ?, ?, ?, ?)",
                merchantId, "ORD-" + SEQ.incrementAndGet(), amount, currency, txRef, LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(2),
                LocalDateTime.now().minusDays(2), live, fee, amount - fee);
        return jdbc.queryForObject("SELECT MAX(id) FROM checkout_sessions", Long.class);
    }

    @Test
    void settlementBatchesApprovalsIdempotentInstructionsAndProofOfPayment() throws Exception {
        // Reconciled collections for two merchants.
        String a1 = payment(10_000, true), a2 = payment(5_000, true), b1 = payment(20_000, true), unreconciled = payment(3_000, true);
        LocalDateTime now = LocalDateTime.now();
        String csv = "reference,provider_reference,amount,currency,timestamp\n"
                + csvLine(a1, "", "100.00", "USD", now.minusHours(1)).replace(",PAYMENT\n", "\n")
                + csvLine(a2, "", "50.00", "USD", now.minusMinutes(50)).replace(",PAYMENT\n", "\n")
                + csvLine(b1, "", "200.00", "USD", now.plusHours(2)).replace(",PAYMENT\n", "\n");
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        recon.run(recon.importStatement("SIM", "settle.csv", bytes, recon.parse("SIM", "CSV", bytes), "fin.carol").statementId(), "fin.carol");

        long ma = merchant("Alpha Store " + SEQ.get()), mb = merchant("Beta Shop " + SEQ.get());
        long sa1 = session(ma, a1, 10_000, 300, true);
        long sa2 = session(ma, a2, 5_000, 150, true);
        long sb1 = session(mb, b1, 20_000, 600, true);
        session(ma, unreconciled, 3_000, 90, true);                 // not on a statement: not eligible
        session(mb, b1, 7_000, 210, false);                          // test mode: never eligible
        jdbc.update("INSERT INTO refunds (checkout_session_id, amount_cents, reason, status, initiated_by_type, initiated_by_id) "
                + "VALUES (?, 2500, 'partial', 'COMPLETED', 'STAFF', 1)", sa2);   // half of a2 refunded

        SettlementBatchService.Batch batch = settlement.draft("USD", "fin.maker");
        assertNotNull(batch);
        assertEquals(2, batch.itemCount());
        // Alpha: 9,700 + (4,850 * 2,500 / 5,000 = 2,425) = 12,125; Beta: 19,400.
        Map<Long, Long> net = new java.util.HashMap<>();
        for (Map<String, Object> i : settlement.items(batch.id())) net.put(((Number) i.get("merchant_id")).longValue(), ((Number) i.get("net_minor")).longValue());
        assertEquals(12_125L, net.get(ma));
        assertEquals(19_400L, net.get(mb));
        assertEquals(2, batch.approvalsRequired(), "above the threshold: two approvers");
        assertNull(settlement.draft("USD", "fin.maker"), "sessions are claimed by one batch only");

        // Approvals: not the creator, not twice, two different people.
        assertThrows(IllegalStateException.class, () -> settlement.approve(batch.id(), "fin.maker"));
        settlement.approve(batch.id(), "fin.checker1");
        assertThrows(IllegalStateException.class, () -> settlement.approve(batch.id(), "fin.checker1"));
        assertThrows(IllegalStateException.class, () -> settlement.instruct(batch.id(), "fin.ops"), "not approved yet");
        assertEquals("APPROVED", settlement.approve(batch.id(), "fin.checker2").status());

        // A crash after generating the instructions but before commit leaves nothing behind...
        TransactionTemplate t = new TransactionTemplate(txm);
        assertThrows(RuntimeException.class, () -> t.executeWithoutResult(s -> {
            settlement.instruct(batch.id(), "fin.ops");
            throw new RuntimeException("simulated crash before commit");
        }));
        assertEquals("APPROVED", settlement.get(batch.id()).status());
        // ...and re-running produces one instruction set; repeating returns the same file.
        SettlementBatchService.PayoutFile f1 = settlement.instruct(batch.id(), "fin.ops");
        SettlementBatchService.PayoutFile f2 = settlement.instruct(batch.id(), "fin.ops");
        assertTrue(f1.regenerated());
        assertFalse(f2.regenerated());
        assertEquals(f1.sha256(), f2.sha256());
        assertEquals(f1.content(), f2.content());
        assertEquals(3, f1.content().split("\n").length, "header + one line per merchant");
        List<String> instructionRefs = new ArrayList<>();
        for (Map<String, Object> i : settlement.items(batch.id())) {
            assertEquals("INSTRUCTED", i.get("status"));
            instructionRefs.add((String) i.get("instruction_ref"));
        }

        // PAID only with the bank's reference and proof.
        Map<String, Integer> r1 = settlement.applyPayoutResults(List.of(
                new SettlementBatchService.PayoutResult(instructionRefs.get(0), "PAID", null, null)), "bank-confirm-1.csv", "a".repeat(64), "fin.ops");
        assertEquals(1, r1.get("REJECTED_NO_BANK_REFERENCE"));
        assertThrows(IllegalArgumentException.class, () -> settlement.applyPayoutResults(List.of(
                new SettlementBatchService.PayoutResult(instructionRefs.get(0), "PAID", "CBZ-TT-1", null)), "no-proof.csv", null, "fin.ops"));
        assertThrows(org.springframework.dao.DataAccessException.class, () ->
                jdbc.update("UPDATE stl_items SET status = 'PAID' WHERE instruction_ref = ?", instructionRefs.get(0)), "the database refuses PAID without proof");

        Map<String, Integer> r2 = settlement.applyPayoutResults(List.of(
                new SettlementBatchService.PayoutResult(instructionRefs.get(0), "PAID", "CBZ-TT-1", null),
                new SettlementBatchService.PayoutResult(instructionRefs.get(1), "RETURNED", null, "Account closed")), "bank-confirm-2.csv", "b".repeat(64), "fin.ops");
        assertEquals(1, r2.get("PAID"));
        assertEquals(1, r2.get("RETURNED"));
        assertEquals("COMPLETED", settlement.get(batch.id()).status());
        // Re-applying the same file is a no-op.
        assertEquals(1, settlement.applyPayoutResults(List.of(
                new SettlementBatchService.PayoutResult(instructionRefs.get(0), "PAID", "CBZ-TT-1", null)), "bank-confirm-2.csv", "b".repeat(64), "fin.ops").get("ALREADY_PAID"));

        // The returned merchant's sessions are eligible again; the paid one's are not.
        List<Long> eligibleAgain = settlement.eligible("USD", LocalDateTime.now()).stream().map(SettlementBatchService.EligibleSession::sessionId).toList();
        long returnedMerchant = ((Number) jdbc.queryForObject("SELECT merchant_id FROM stl_items WHERE instruction_ref = ?", Long.class, instructionRefs.get(1))).longValue();
        if (returnedMerchant == ma) {
            assertTrue(eligibleAgain.containsAll(List.of(sa1, sa2)));
            assertFalse(eligibleAgain.contains(sb1));
        } else {
            assertTrue(eligibleAgain.contains(sb1));
            assertFalse(eligibleAgain.contains(sa1));
        }
    }

    @Test
    void parallelModeBatchesAreNeverInstructed() {
        jdbc.update("INSERT INTO stl_batches (batch_ref, currency, mode, cutoff_at, status, item_count, total_net_minor, approvals_required, "
                + "created_by, created_at) VALUES ('STL-PARALLEL-TEST', 'USD', 'PARALLEL', ?, 'APPROVED', 0, 0, 1, 'fin.maker', ?)", LocalDateTime.now(), LocalDateTime.now());
        long id = jdbc.queryForObject("SELECT id FROM stl_batches WHERE batch_ref = 'STL-PARALLEL-TEST'", Long.class);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> settlement.instruct(id, "fin.ops"));
        assertTrue(e.getMessage().contains("PARALLEL"));
        assertThrows(org.springframework.dao.DataAccessException.class, () ->
                jdbc.update("UPDATE stl_batches SET status = 'INSTRUCTED', file_sha256 = ? WHERE id = ?", "c".repeat(64), id),
                "the database refuses an instructed PARALLEL batch");
    }

    @Test
    void automatedSettlement_isOneSwitch_draftsApprovesByPolicyAndInstructs_withoutPeople() throws Exception {
        String z1 = payment(40_000, true, "ZWG"), z2 = payment(60_000, true, "ZWG");
        LocalDateTime now = LocalDateTime.now();
        String csv = "reference,provider_reference,amount,currency,timestamp\n"
                + z1 + ",,400.00,ZWG," + now.minusHours(1).withNano(0) + "\n"
                + z2 + ",,600.00,ZWG," + now.plusHours(2).withNano(0) + "\n";
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        recon.run(recon.importStatement("SIM", "zwg.csv", bytes, recon.parse("SIM", "CSV", bytes), "fin.carol").statementId(), "fin.carol");
        long m = merchant("Zig Traders " + SEQ.get());
        session(m, z1, 40_000, 1_200, true, "ZWG");
        session(m, z2, 60_000, 1_800, true, "ZWG");
        // Automatic approval only pays to a destination PayPort staff verified (merchant adds, staff verifies).
        jdbc.update("INSERT INTO merchant_settlement_destinations (merchant_id, currency, type, account_name, account_number, institution, status, "
                + "requested_by, requested_at, verified_by, verified_at) VALUES (?, 'ZWG', 'BANK', 'Zig Traders', '9988776655', 'CBZ', 'VERIFIED', "
                + "'merchant:owner', NOW(), 'ops.verifier', NOW())", m);

        var r = automation.runOnce();
        assertEquals(1, r.drafted().size(), r.toString());
        assertEquals(r.drafted(), r.policyApproved(), "within the automatic approval limit and all checks pass");
        assertEquals(r.drafted(), r.instructed(), "LIVE mode: instructed automatically");
        long batchId = jdbc.queryForObject("SELECT id FROM stl_batches WHERE batch_ref = ?", Long.class, r.drafted().get(0));
        assertEquals("INSTRUCTED", settlement.get(batchId).status());
        assertEquals(97_000L, settlement.get(batchId).totalNetMinor());
        assertTrue(jdbc.queryForObject("SELECT destination FROM stl_items WHERE batch_id = ?", String.class, batchId).contains("9988776655"),
                "paid to the verified destination, not the signup details");
        assertEquals(List.of("SYSTEM:auto-approval-policy"), jdbc.queryForList("SELECT approver FROM stl_batch_approvals WHERE batch_id = ?", String.class, batchId));
        assertEquals("SYSTEM:settlement-automation", jdbc.queryForObject("SELECT created_by FROM stl_batches WHERE id = ?", String.class, batchId));

        var again = automation.runOnce();
        assertTrue(again.drafted().isEmpty() && again.instructed().isEmpty(), "nothing is paid twice: " + again);
    }
}
