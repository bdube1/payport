package zw.co.payport.gateway.payments;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import zw.co.payport.gateway.entity.Transaction;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.exception.InvalidStateTransitionException;
import zw.co.payport.gateway.model.request.InitiateTransactionRequest;
import zw.co.payport.gateway.model.request.PayTransactionRequest;
import zw.co.payport.gateway.model.response.TransactionResponse;
import zw.co.payport.gateway.payments.SimulatorPaymentAdaptor.Query;
import zw.co.payport.gateway.payments.SimulatorPaymentAdaptor.Submit;
import zw.co.payport.gateway.repository.TransactionRepository;
import zw.co.payport.gateway.scheduler.PaymentReconciliationScheduler;
import zw.co.payport.gateway.scheduler.PaymentTimeoutWatchdog;
import zw.co.payport.gateway.service.FulfilmentQueueService;
import zw.co.payport.gateway.service.SmsNotificationService;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;

import java.time.LocalDateTime;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase 2 acceptance: the payment lifecycle under failure. Whole application, real MySQL 8,
 * real Flyway migrations; the provider is a scriptable simulator.
 *
 * For every scenario the bar is: exactly one financial effect (one confirmation, one
 * fulfilment enqueue, one confirmed SMS), the correct final state, and no blind retry
 * (the provider receives exactly one payment request).
 *
 * SIM is signature-trusted (callbacks need x-sim-signature: valid). SIMQ is listed in
 * payport.payments.query-providers, so its callbacks are confirmed by status query.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@org.springframework.context.annotation.Import(PaymentLifecycleScenarioTest.Providers.class)
class PaymentLifecycleScenarioTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("DB_URL", MYSQL::getJdbcUrl);
        r.add("DB_USERNAME", MYSQL::getUsername);
        r.add("DB_PASSWORD", MYSQL::getPassword);
        r.add("JWT_SECRET", () -> "scenario-test-secret-scenario-test-secret-scenario-0123456789");
        r.add("payport.payments.query-providers", () -> "ECOCASH,ONEMONEY,ZIMSWITCH,SIMQ");
        r.add("payport.payments.resolver.enabled", () -> "false"); // driven explicitly with runOnce
        r.add("payport.payments.watchdog.enabled", () -> "false");
        r.add("payport.fulfilment.worker.enabled", () -> "false");
    }

    @TestConfiguration
    static class Providers {
        @Bean SimulatorPaymentAdaptor simAdaptor() { return new SimulatorPaymentAdaptor("SIM"); }
        @Bean SimulatorPaymentAdaptor simqAdaptor() { return new SimulatorPaymentAdaptor("SIMQ"); }
    }

    @MockitoBean SmsNotificationService sms;
    @MockitoBean FulfilmentQueueService fulfilment;

    @Autowired TransactionOrchestratorService orchestrator;
    @Autowired CallbackInbox inbox;
    @Autowired PaymentReconciliationScheduler resolver;
    @Autowired PaymentTimeoutWatchdog watchdog;
    @Autowired TransactionRepository transactions;
    @Autowired PaymentAttemptStore attempts;
    @Autowired PaymentCheckStore checks;
    @Autowired JdbcTemplate jdbc;

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000));
    private static final Map<String, String> SIGNED = Map.of("x-sim-signature", "valid");

    @BeforeEach
    void providersExist() {
        jdbc.update("INSERT IGNORE INTO payment_methods (code, name, is_active, display_order) VALUES ('SIM', 'Simulator', TRUE, 90), ('SIMQ', 'Simulator (query-verified)', TRUE, 91)");
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private String newPayment(long cents) {
        String ref = String.format("PP-API-20261016-%06d", SEQ.incrementAndGet() % 1_000_000);
        InitiateTransactionRequest req = new InitiateTransactionRequest();
        req.setReference(ref);
        req.setProductTypeCode("MERCHANT_CHECKOUT");
        req.setRecipientIdentifier("+263771234567");
        req.setAmountCents(cents);
        req.setCurrency("USD");
        req.setCustomerPhone("+263771234567");
        req.setChannel("API");
        orchestrator.initiateTransaction(req, "127.0.0.1");
        return ref;
    }

    private TransactionResponse pay(String ref, String provider) throws Exception {
        PayTransactionRequest p = new PayTransactionRequest();
        p.setReference(ref);
        p.setPaymentMethodCode(provider);
        p.setCustomerPhone("+263771234567");
        return orchestrator.submitPayment(p);
    }

    private Transaction tx(String ref) {
        return transactions.findByReference(ref).orElseThrow();
    }

    private CallbackInbox.Result callback(String provider, String ref, String status, long cents) {
        return inbox.receive(provider, SimulatorPaymentAdaptor.callbackBody(ref, status, cents, "SIMTX-" + ref), SIGNED, "10.0.0.1");
    }

    private int auditRowsTo(String ref, TransactionStatus to) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM transaction_audit_log WHERE transaction_reference = ? AND to_status = ?",
                Integer.class, ref, to.name());
    }

    /** The money-relevant effects of a confirmation, each of which must happen exactly once (or never). */
    private void assertConfirmedExactlyOnce(String ref) {
        assertEquals(TransactionStatus.PAYMENT_CONFIRMED, tx(ref).getStatus());
        assertEquals(1, auditRowsTo(ref, TransactionStatus.PAYMENT_CONFIRMED), "one confirmation in the audit trail");
        verify(fulfilment, times(1)).enqueue(ref);
        verify(sms, times(1)).sendPaymentConfirmed(argThat(t -> ref.equals(t.getReference())));
        verify(sms, never()).sendPaymentFailed(argThat(t -> ref.equals(t.getReference())), anyString());
    }

    private void assertNeverConfirmed(String ref) {
        assertEquals(0, auditRowsTo(ref, TransactionStatus.PAYMENT_CONFIRMED));
        verify(fulfilment, never()).enqueue(ref);
        verify(sms, never()).sendPaymentConfirmed(argThat(t -> ref.equals(t.getReference())));
    }

    /** A resolver pass as if {@code ahead} of real time, so the grace period has passed. */
    private PaymentReconciliationScheduler.Summary resolveAt(LocalDateTime when) {
        return resolver.runOnce(when);
    }

    private static <T> List<Future<T>> race(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> { start.await(); return task.call(); }));
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "racing threads finished");
        return futures;
    }

    // ─── scenarios ─────────────────────────────────────────────────────────────

    @Test
    void happyPath_acceptedThenConfirmedByCallback() throws Exception {
        String ref = newPayment(500);
        pay(ref, "SIM");

        Transaction t = tx(ref);
        assertEquals(TransactionStatus.PAYMENT_PENDING, t.getStatus());
        assertEquals("SIM-" + ref, t.getProviderReference());
        assertEquals(PaymentAttemptStore.Status.ACCEPTED, attempts.forTransaction(ref).get(0).status());
        assertEquals(PaymentCheckStore.Status.OPEN, checks.find(ref).orElseThrow().status());
        verify(sms, times(1)).sendPaymentPending(argThat(x -> ref.equals(x.getReference())), eq("SIM"));

        assertEquals(CallbackInbox.Status.APPLIED, callback("SIM", ref, "SUCCESS", 500).status());
        assertConfirmedExactlyOnce(ref);
        assertEquals(PaymentCheckStore.Status.RESOLVED, checks.find(ref).orElseThrow().status());
        assertEquals(1, SimulatorPaymentAdaptor.script(ref).submits.get());
    }

    @Test
    void duplicateCallbacks_sequentialAndConcurrent_haveOneEffect() throws Exception {
        String ref = newPayment(700);
        pay(ref, "SIM");

        List<Future<CallbackInbox.Result>> results = race(10, () -> callback("SIM", ref, "SUCCESS", 700));
        for (int i = 0; i < 5; i++) callback("SIM", ref, "SUCCESS", 700);

        long applied = 0;
        for (Future<CallbackInbox.Result> f : results) if (f.get().status() == CallbackInbox.Status.APPLIED) applied++;
        assertEquals(1, applied, "exactly one of the racing deliveries applied");
        assertConfirmedExactlyOnce(ref);

        Map<String, Object> row = jdbc.queryForMap("SELECT COUNT(*) AS n, MAX(received_count) AS received, MAX(status) AS status "
                + "FROM payment_callbacks WHERE payport_reference = ?", ref);
        assertEquals(1L, ((Number) row.get("n")).longValue(), "one inbox row for an identical body");
        assertEquals(15, ((Number) row.get("received")).intValue());
        assertEquals("APPLIED", row.get("status"));
    }

    @Test
    void outOfOrder_failureAfterSuccess_isAConflictAndNeverUndoesTheConfirmation() throws Exception {
        String ref = newPayment(800);
        pay(ref, "SIM");
        assertEquals(CallbackInbox.Status.APPLIED, callback("SIM", ref, "SUCCESS", 800).status());
        assertEquals(CallbackInbox.Status.CONFLICT, callback("SIM", ref, "FAILED", 800).status());
        assertConfirmedExactlyOnce(ref);
    }

    @Test
    void outOfOrder_successAfterFailure_isFlaggedNotSilentlyDropped() throws Exception {
        String ref = newPayment(900);
        pay(ref, "SIM");
        assertEquals(CallbackInbox.Status.APPLIED, callback("SIM", ref, "FAILED", 900).status());
        assertEquals(CallbackInbox.Status.CONFLICT, callback("SIM", ref, "SUCCESS", 900).status(),
                "the customer may have paid for a payment we recorded as failed: a person must see it");
        assertEquals(TransactionStatus.PAYMENT_FAILED, tx(ref).getStatus());
        assertNeverConfirmed(ref);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM payment_callbacks WHERE payport_reference = ? AND status = 'CONFLICT'",
                Integer.class, ref));
    }

    @Test
    void amountMismatchInCallback_isAConflict() throws Exception {
        String ref = newPayment(1000);
        pay(ref, "SIM");
        assertEquals(CallbackInbox.Status.CONFLICT, callback("SIM", ref, "SUCCESS", 10).status());
        assertEquals(TransactionStatus.PAYMENT_PENDING, tx(ref).getStatus());
        assertNeverConfirmed(ref);
    }

    @Test
    void badSignature_changesNothing() throws Exception {
        String ref = newPayment(1100);
        pay(ref, "SIM");
        CallbackInbox.Result r = inbox.receive("SIM", SimulatorPaymentAdaptor.callbackBody(ref, "SUCCESS", 1100, "x"), Map.of(), "6.6.6.6");
        assertEquals(CallbackInbox.Status.INVALID, r.status());
        assertEquals(TransactionStatus.PAYMENT_PENDING, tx(ref).getStatus());
        assertNeverConfirmed(ref);
    }

    @Test
    void callbackBeforeSubmitResponse_confirmsOnceAndTheLateResponseChangesNothing() throws Exception {
        String ref = newPayment(1200);
        SimulatorPaymentAdaptor.script(ref).duringSubmit = r -> assertEquals(CallbackInbox.Status.APPLIED, callback("SIM", r, "SUCCESS", 1200).status());

        pay(ref, "SIM");

        assertConfirmedExactlyOnce(ref);
        verify(sms, never()).sendPaymentPending(argThat(x -> ref.equals(x.getReference())), anyString());
        assertEquals(PaymentAttemptStore.Status.ACCEPTED, attempts.forTransaction(ref).get(0).status());
        assertNotNull(tx(ref).getProviderReference());
    }

    @Test
    void callbackBeforeSubmitResponse_thenTimeout_staysConfirmed() throws Exception {
        String ref = newPayment(1250);
        SimulatorPaymentAdaptor.Script s = SimulatorPaymentAdaptor.script(ref);
        s.submit = Submit.READ_TIMEOUT;
        s.duringSubmit = r -> callback("SIM", r, "SUCCESS", 1250);

        pay(ref, "SIM");

        assertConfirmedExactlyOnce(ref);
        assertEquals(PaymentAttemptStore.Status.UNKNOWN, attempts.forTransaction(ref).get(0).status());
    }

    @Test
    void readTimeout_thenLateSuccess_byResolverQuery() throws Exception {
        String ref = newPayment(1300);
        SimulatorPaymentAdaptor.Script s = SimulatorPaymentAdaptor.script(ref);
        s.submit = Submit.READ_TIMEOUT;

        pay(ref, "SIM");

        Transaction t = tx(ref);
        assertEquals(TransactionStatus.PAYMENT_PENDING, t.getStatus(), "a timeout is not a failure");
        assertEquals("UNKNOWN", t.getPaymentProviderStatus());
        assertEquals(PaymentAttemptStore.Status.UNKNOWN, attempts.forTransaction(ref).get(0).status());
        verify(sms, never()).sendPaymentFailed(argThat(x -> ref.equals(x.getReference())), anyString());

        s.query = Query.PENDING;
        resolveAt(LocalDateTime.now().plusMinutes(1));
        assertEquals(TransactionStatus.PAYMENT_PENDING, tx(ref).getStatus());

        s.query = Query.SUCCESS;
        resolveAt(LocalDateTime.now().plusMinutes(2));
        assertConfirmedExactlyOnce(ref);
        assertEquals(PaymentCheckStore.Status.RESOLVED, checks.find(ref).orElseThrow().status());
        assertEquals(1, s.submits.get(), "resolved by query, never re-sent");
    }

    @Test
    void readTimeout_thenLateSuccess_byCallback() throws Exception {
        String ref = newPayment(1350);
        SimulatorPaymentAdaptor.script(ref).submit = Submit.READ_TIMEOUT;
        pay(ref, "SIM");
        assertEquals(CallbackInbox.Status.APPLIED, callback("SIM", ref, "SUCCESS", 1350).status());
        assertConfirmedExactlyOnce(ref);
    }

    @Test
    void serverError_isUnknown_thenDefiniteFailureByQuery() throws Exception {
        String ref = newPayment(1400);
        SimulatorPaymentAdaptor.Script s = SimulatorPaymentAdaptor.script(ref);
        s.submit = Submit.SERVER_ERROR;

        pay(ref, "SIM");
        assertEquals(TransactionStatus.PAYMENT_PENDING, tx(ref).getStatus());
        verify(sms, never()).sendPaymentFailed(argThat(x -> ref.equals(x.getReference())), anyString());

        s.query = Query.FAILED;
        resolveAt(LocalDateTime.now().plusMinutes(1));
        assertEquals(TransactionStatus.PAYMENT_FAILED, tx(ref).getStatus());
        assertNeverConfirmed(ref);
        verify(sms, times(1)).sendPaymentFailed(argThat(t -> ref.equals(t.getReference())), anyString());
        assertEquals(1, s.submits.get());
    }

    @Test
    void connectionRefused_isADefiniteFailureImmediately() throws Exception {
        String ref = newPayment(1500);
        SimulatorPaymentAdaptor.script(ref).submit = Submit.CONNECT_REFUSED;

        pay(ref, "SIM");

        assertEquals(TransactionStatus.PAYMENT_FAILED, tx(ref).getStatus());
        assertEquals(PaymentAttemptStore.Status.REJECTED, attempts.forTransaction(ref).get(0).status());
        assertEquals(PaymentCheckStore.Status.RESOLVED, checks.find(ref).orElseThrow().status());
        verify(sms, times(1)).sendPaymentFailed(argThat(t -> ref.equals(t.getReference())), anyString());
    }

    @Test
    void providerRejection_isADefiniteFailure() throws Exception {
        String ref = newPayment(1550);
        SimulatorPaymentAdaptor.script(ref).submit = Submit.REJECT_4XX;
        pay(ref, "SIM");
        assertEquals(TransactionStatus.PAYMENT_FAILED, tx(ref).getStatus());
        assertNeverConfirmed(ref);
    }

    @Test
    void providerDownForQueries_paymentStaysOpen_thenGoesToManualAfter72hWithoutFailing() throws Exception {
        String ref = newPayment(1600);
        SimulatorPaymentAdaptor.Script s = SimulatorPaymentAdaptor.script(ref);
        s.submit = Submit.READ_TIMEOUT;
        s.query = Query.DOWN;
        pay(ref, "SIM");

        resolveAt(LocalDateTime.now().plusMinutes(1));
        resolveAt(LocalDateTime.now().plusMinutes(2));
        PaymentCheckStore.Check c = checks.find(ref).orElseThrow();
        assertEquals(PaymentCheckStore.Status.OPEN, c.status());
        assertEquals(2, c.checkCount());
        assertTrue(c.lastResult().contains("UNKNOWN"), c.lastResult());
        assertEquals(TransactionStatus.PAYMENT_PENDING, tx(ref).getStatus());

        resolveAt(LocalDateTime.now().plusHours(73));
        assertEquals(PaymentCheckStore.Status.MANUAL, checks.find(ref).orElseThrow().status());
        assertEquals(TransactionStatus.PAYMENT_PENDING, tx(ref).getStatus(), "time never fails a payment");
        verify(sms, never()).sendPaymentFailed(argThat(x -> ref.equals(x.getReference())), anyString());
        assertEquals(1, s.submits.get());
    }

    @Test
    void resolverAmountMismatch_goesToManualAndIsNotApplied() throws Exception {
        String ref = newPayment(1700);
        SimulatorPaymentAdaptor.Script s = SimulatorPaymentAdaptor.script(ref);
        s.submit = Submit.READ_TIMEOUT;
        s.query = Query.SUCCESS;
        s.queryAmountCents = 17L;
        pay(ref, "SIM");

        resolveAt(LocalDateTime.now().plusMinutes(1));
        assertEquals(PaymentCheckStore.Status.MANUAL, checks.find(ref).orElseThrow().status());
        assertEquals(TransactionStatus.PAYMENT_PENDING, tx(ref).getStatus());
        assertNeverConfirmed(ref);
    }

    @Test
    void concurrentIdenticalPayRequests_sendExactlyOnePush() throws Exception {
        String ref = newPayment(1800);
        AtomicInteger refused = new AtomicInteger();
        List<Future<Boolean>> results = race(10, () -> {
            try {
                pay(ref, "SIM");
                return true;
            } catch (InvalidStateTransitionException e) {
                refused.incrementAndGet();
                return false;
            }
        });
        int ok = 0;
        for (Future<Boolean> f : results) if (f.get()) ok++;

        assertEquals(1, ok);
        assertEquals(9, refused.get());
        assertEquals(1, SimulatorPaymentAdaptor.script(ref).submits.get(), "one push to the customer's phone");
        assertEquals(1, attempts.forTransaction(ref).size());
        assertEquals(1, auditRowsTo(ref, TransactionStatus.PAYMENT_PENDING));
    }

    @Test
    void retryAfterUnknownOutcome_isRefused() throws Exception {
        String ref = newPayment(1900);
        SimulatorPaymentAdaptor.script(ref).submit = Submit.READ_TIMEOUT;
        pay(ref, "SIM");
        SimulatorPaymentAdaptor.script(ref).submit = Submit.ACCEPT;
        assertThrows(InvalidStateTransitionException.class, () -> pay(ref, "SIM"));
        assertEquals(1, SimulatorPaymentAdaptor.script(ref).submits.get(), "no blind retry");
    }

    @Test
    void watchdogTimeout_thenLateConfirmation() throws Exception {
        String ref = newPayment(2000);
        pay(ref, "SIM");
        assertTrue(watchdog.runOnce(LocalDateTime.now().plusMinutes(5)) >= 1);
        assertEquals(TransactionStatus.PAYMENT_TIMEOUT, tx(ref).getStatus());
        verify(sms, never()).sendPaymentFailed(argThat(x -> ref.equals(x.getReference())), anyString());

        assertEquals(CallbackInbox.Status.APPLIED, callback("SIM", ref, "SUCCESS", 2000).status());
        assertConfirmedExactlyOnce(ref);
    }

    @Test
    void watchdogRacingConfirmations_neverOverwritesAConfirmation() throws Exception {
        List<String> refs = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            String ref = newPayment(2100 + i);
            pay(ref, "SIM");
            refs.add(ref);
        }
        LocalDateTime later = LocalDateTime.now().plusMinutes(5);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (String ref : refs) tasks.add(() -> callback("SIM", ref, "SUCCESS", tx(ref).getAmountCents()));
        for (int i = 0; i < 5; i++) tasks.add(() -> watchdog.runOnce(later));

        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        for (Future<Object> f : pool.invokeAll(tasks)) f.get();
        pool.shutdown();

        for (String ref : refs) {
            assertConfirmedExactlyOnce(ref);
        }
    }

    @Test
    void queryVerifiedProvider_forgedSuccessCallbackChangesNothingUntilTheProviderConfirms() throws Exception {
        String ref = newPayment(2200);
        SimulatorPaymentAdaptor.Script s = SimulatorPaymentAdaptor.script(ref);
        pay(ref, "SIMQ");

        s.query = Query.PENDING;
        CallbackInbox.Result forged = inbox.receive("SIMQ", SimulatorPaymentAdaptor.callbackBody(ref, "SUCCESS", 2200, "x"), Map.of(), "6.6.6.6");
        assertEquals(CallbackInbox.Status.NO_CHANGE, forged.status());
        assertEquals(TransactionStatus.PAYMENT_PENDING, tx(ref).getStatus());
        assertNeverConfirmed(ref);

        s.query = Query.SUCCESS;
        assertEquals(CallbackInbox.Status.APPLIED,
                inbox.receive("SIMQ", SimulatorPaymentAdaptor.callbackBody(ref, "SUCCESS", 2200, "y"), Map.of(), "10.0.0.1").status());
        assertConfirmedExactlyOnce(ref);
    }

    @Test
    void queryVerifiedProvider_callbackClaimingSuccessIsOverruledByTheProvidersFailure() throws Exception {
        String ref = newPayment(2300);
        SimulatorPaymentAdaptor.Script s = SimulatorPaymentAdaptor.script(ref);
        pay(ref, "SIMQ");
        s.query = Query.FAILED;
        assertEquals(CallbackInbox.Status.APPLIED,
                inbox.receive("SIMQ", SimulatorPaymentAdaptor.callbackBody(ref, "SUCCESS", 2300, "x"), Map.of(), "10.0.0.1").status());
        assertEquals(TransactionStatus.PAYMENT_FAILED, tx(ref).getStatus());
        assertNeverConfirmed(ref);
    }

    @Test
    void resolverPicksUpPendingPaymentsThatHaveNoCheck() throws Exception {
        String ref = newPayment(2400);
        SimulatorPaymentAdaptor.Script s = SimulatorPaymentAdaptor.script(ref);
        s.submit = Submit.READ_TIMEOUT;
        pay(ref, "SIM");
        jdbc.update("DELETE FROM payment_checks WHERE transaction_reference = ?", ref); // as if submitted before this release

        s.query = Query.SUCCESS;
        resolveAt(LocalDateTime.now().plusMinutes(1)); // backfills the check (due now) and resolves it
        resolveAt(LocalDateTime.now().plusMinutes(1));
        assertConfirmedExactlyOnce(ref);
    }

    @Test
    void callbackForUnknownPayment_isRecordedAndIgnored() {
        CallbackInbox.Result r = callback("SIM", "PP-API-20261016-999999", "SUCCESS", 100);
        assertEquals(CallbackInbox.Status.UNKNOWN_REF, r.status());
        assertTrue(r.id() > 0, "stored in the inbox for investigation");
    }
}
