package zw.co.payport.gateway.payments;

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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.model.request.InitiateTransactionRequest;
import zw.co.payport.gateway.model.request.PayTransactionRequest;
import zw.co.payport.gateway.repository.TransactionRepository;
import zw.co.payport.gateway.scheduler.FulfilmentWorker;
import zw.co.payport.gateway.service.FulfilmentQueueService;
import zw.co.payport.gateway.service.FulfilmentQueueService.JobStatus;
import zw.co.payport.gateway.service.SmsNotificationService;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Phase 2.2 acceptance: durable delivery jobs. A confirmed payment always gets exactly one
 * job; a delivery is sent once per claim; a delivery whose outcome is unknown is never sent
 * again automatically, only resolved by the provider's delivery query or handed to a person.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Import(FulfilmentJobScenarioTest.Providers.class)
class FulfilmentJobScenarioTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("DB_URL", MYSQL::getJdbcUrl);
        r.add("DB_USERNAME", MYSQL::getUsername);
        r.add("DB_PASSWORD", MYSQL::getPassword);
        r.add("JWT_SECRET", () -> "fulfilment-test-secret-fulfilment-test-secret-fulfil-0123456789");
        r.add("payport.payments.resolver.enabled", () -> "false");
        r.add("payport.payments.watchdog.enabled", () -> "false");
        r.add("payport.fulfilment.worker.enabled", () -> "false"); // driven explicitly with runOnce
        r.add("payport.fulfilment.unknown-manual-after-minutes", () -> "120");
    }

    @TestConfiguration
    static class Providers {
        @Bean SimulatorPaymentAdaptor simAdaptor() { return new SimulatorPaymentAdaptor("SIM"); }
        @Bean SimulatorFulfilmentAdaptor simFulfilment() { return new SimulatorFulfilmentAdaptor(); }
    }

    @MockitoBean SmsNotificationService sms;

    @Autowired TransactionOrchestratorService orchestrator;
    @Autowired CallbackInbox inbox;
    @Autowired FulfilmentWorker worker;
    @Autowired FulfilmentQueueService queue;
    @Autowired TransactionRepository transactions;
    @Autowired JdbcTemplate jdbc;

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000));

    @BeforeEach
    void setUp() {
        jdbc.update("INSERT IGNORE INTO payment_methods (code, name, is_active, display_order) VALUES ('SIM', 'Simulator', TRUE, 90)");
        jdbc.update("INSERT IGNORE INTO product_types (code, name, category, provider_code, is_active, min_amount_cents, max_amount_cents, currency) "
                + "VALUES ('SIM_SERVICE', 'Simulated service', 'MERCHANT', 'SIMFUL', TRUE, 1, 100000000, 'USD')");
    }

    /** A payment confirmed through the real callback path (which queues the delivery job). */
    private String confirmedPayment() throws Exception {
        String ref = String.format("PP-API-20261016-%06d", SEQ.incrementAndGet() % 1_000_000);
        InitiateTransactionRequest req = new InitiateTransactionRequest();
        req.setReference(ref);
        req.setProductTypeCode("SIM_SERVICE");
        req.setRecipientIdentifier("0123456789");
        req.setAmountCents(1000L);
        req.setCurrency("USD");
        req.setCustomerPhone("+263771234567");
        req.setChannel("API");
        orchestrator.initiateTransaction(req, "127.0.0.1");
        PayTransactionRequest p = new PayTransactionRequest();
        p.setReference(ref);
        p.setPaymentMethodCode("SIM");
        p.setCustomerPhone("+263771234567");
        orchestrator.submitPayment(p);
        CallbackInbox.Result r = inbox.receive("SIM", SimulatorPaymentAdaptor.callbackBody(ref, "SUCCESS", 1000, "x-" + ref),
                Map.of("x-sim-signature", "valid"), "10.0.0.1");
        assertEquals(CallbackInbox.Status.APPLIED, r.status());
        return ref;
    }

    private TransactionStatus status(String ref) {
        return transactions.findByReference(ref).orElseThrow().getStatus();
    }

    private JobStatus job(String ref) {
        return queue.find(ref).orElseThrow().status();
    }

    private int deliveries(String ref) {
        return SimulatorFulfilmentAdaptor.script(ref).deliveries.get();
    }

    private void makeDue(String ref) {
        jdbc.update("UPDATE fulfilment_jobs SET next_run_at = ? WHERE transaction_reference = ?", LocalDateTime.now().minusSeconds(1), ref);
    }

    @Test
    void confirmationQueuesTheJobInTheSameTransaction_andTheWorkerDeliversOnce() throws Exception {
        String ref = confirmedPayment();
        assertEquals(JobStatus.QUEUED, job(ref), "the job exists as soon as the confirmation is committed");
        assertEquals(TransactionStatus.PAYMENT_CONFIRMED, status(ref));

        worker.runOnce(LocalDateTime.now());

        assertEquals(TransactionStatus.COMPLETED, status(ref));
        assertEquals(JobStatus.DONE, job(ref));
        assertEquals(1, deliveries(ref));
        verify(sms, times(1)).sendDeliverySuccess(argThat(t -> ref.equals(t.getReference())));

        worker.runOnce(LocalDateTime.now().plusHours(1));
        assertEquals(1, deliveries(ref), "a done job is never run again");
    }

    @Test
    void concurrentWorkers_deliverExactlyOnce() throws Exception {
        String ref = confirmedPayment();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) tasks.add(() -> worker.runOnce(LocalDateTime.now()));
        for (Future<Object> f : pool.invokeAll(tasks)) f.get();
        pool.shutdown();

        assertEquals(1, deliveries(ref));
        assertEquals(TransactionStatus.COMPLETED, status(ref));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM transaction_audit_log WHERE transaction_reference = ? AND to_status = 'COMPLETED'",
                Integer.class, ref));
    }

    @Test
    void deliveryTimeout_isNeverResent_andIsResolvedByQueryAsDelivered() throws Exception {
        String ref = confirmedPayment();
        SimulatorFulfilmentAdaptor.Script s = SimulatorFulfilmentAdaptor.script(ref);
        s.deliver = SimulatorFulfilmentAdaptor.Deliver.READ_TIMEOUT;

        worker.runOnce(LocalDateTime.now());
        assertEquals(JobStatus.UNKNOWN, job(ref));
        assertEquals(TransactionStatus.FULFILMENT_PENDING, status(ref));
        verify(sms, never()).sendFulfilmentDelayed(argThat(t -> ref.equals(t.getReference())));

        queue.enqueue(ref); // e.g. a stray re-queue: must not turn an unknown delivery into a new one
        assertEquals(JobStatus.UNKNOWN, job(ref));

        s.query = SimulatorFulfilmentAdaptor.Query.PENDING;
        worker.runOnce(LocalDateTime.now().plusMinutes(6));
        assertEquals(JobStatus.UNKNOWN, job(ref));

        s.query = SimulatorFulfilmentAdaptor.Query.DELIVERED;
        worker.runOnce(LocalDateTime.now().plusMinutes(12));
        assertEquals(TransactionStatus.COMPLETED, status(ref));
        assertEquals(JobStatus.DONE, job(ref));
        assertEquals(1, deliveries(ref), "resolved by query, never delivered twice");
        verify(sms, times(1)).sendDeliverySuccess(argThat(t -> ref.equals(t.getReference())));
    }

    @Test
    void deliveryTimeout_providerConfirmsNotDelivered_thenOpsRetryDeliversOnce() throws Exception {
        String ref = confirmedPayment();
        SimulatorFulfilmentAdaptor.Script s = SimulatorFulfilmentAdaptor.script(ref);
        s.deliver = SimulatorFulfilmentAdaptor.Deliver.READ_TIMEOUT;
        worker.runOnce(LocalDateTime.now());

        s.query = SimulatorFulfilmentAdaptor.Query.FAILED;
        worker.runOnce(LocalDateTime.now().plusMinutes(6));
        assertEquals(TransactionStatus.FULFILMENT_FAILED, status(ref));
        assertEquals(JobStatus.FAILED, job(ref));
        verify(sms, times(1)).sendFulfilmentDelayed(argThat(t -> ref.equals(t.getReference())));
        assertEquals(1, deliveries(ref));

        s.deliver = SimulatorFulfilmentAdaptor.Deliver.OK;
        orchestrator.manualRetryFulfilment(ref, "ops.tester");
        assertEquals(JobStatus.QUEUED, job(ref));
        worker.runOnce(LocalDateTime.now());
        assertEquals(TransactionStatus.COMPLETED, status(ref));
        assertEquals(2, deliveries(ref), "the second delivery is the deliberate ops retry after a confirmed non-delivery");
    }

    @Test
    void unknownForTooLong_goesToManualAndIsNeverResent() throws Exception {
        String ref = confirmedPayment();
        SimulatorFulfilmentAdaptor.Script s = SimulatorFulfilmentAdaptor.script(ref);
        s.deliver = SimulatorFulfilmentAdaptor.Deliver.READ_TIMEOUT;
        s.query = SimulatorFulfilmentAdaptor.Query.DOWN;
        worker.runOnce(LocalDateTime.now());

        worker.runOnce(LocalDateTime.now().plusMinutes(6));
        assertEquals(JobStatus.UNKNOWN, job(ref));
        worker.runOnce(LocalDateTime.now().plusHours(3));
        assertEquals(JobStatus.MANUAL, job(ref));
        assertEquals(TransactionStatus.FULFILMENT_PENDING, status(ref), "left for a person; not failed, not re-delivered");
        assertEquals(1, deliveries(ref));
        assertThrows(IllegalArgumentException.class, () -> orchestrator.manualRetryFulfilment(ref, "ops.tester"),
                "a blind ops retry of an unknown delivery is refused too");
    }

    @Test
    void definiteRejection_failsWithoutAutomaticRetryByDefault() throws Exception {
        String ref = confirmedPayment();
        SimulatorFulfilmentAdaptor.script(ref).deliver = SimulatorFulfilmentAdaptor.Deliver.REJECT;
        worker.runOnce(LocalDateTime.now());
        assertEquals(TransactionStatus.FULFILMENT_FAILED, status(ref));
        assertEquals(JobStatus.FAILED, job(ref));
        verify(sms, times(1)).sendFulfilmentDelayed(argThat(t -> ref.equals(t.getReference())));
        worker.runOnce(LocalDateTime.now().plusHours(1));
        assertEquals(1, deliveries(ref));
    }

    @Test
    void connectionRefused_isADefiniteNonDelivery() throws Exception {
        String ref = confirmedPayment();
        SimulatorFulfilmentAdaptor.script(ref).deliver = SimulatorFulfilmentAdaptor.Deliver.CONNECT_REFUSED;
        worker.runOnce(LocalDateTime.now());
        assertEquals(TransactionStatus.FULFILMENT_FAILED, status(ref));
        assertEquals(JobStatus.FAILED, job(ref));
    }

    @Test
    void instanceDiedMidDelivery_isResolvedByQueryNotResent() throws Exception {
        String ref = confirmedPayment();
        // Simulate: an instance claimed the job, committed FULFILMENT_PENDING and sent the delivery, then died.
        assertTrue(queue.claim(ref, "dead-instance", LocalDateTime.now(), LocalDateTime.now().plusSeconds(1)));
        jdbc.update("UPDATE transactions SET status = 'FULFILMENT_PENDING' WHERE reference = ?", ref);
        SimulatorFulfilmentAdaptor.script(ref).query = SimulatorFulfilmentAdaptor.Query.DELIVERED;

        worker.runOnce(LocalDateTime.now().plusMinutes(10)); // lease expired -> UNKNOWN -> resolved by query in the same pass
        assertEquals(TransactionStatus.COMPLETED, status(ref));
        assertEquals(JobStatus.DONE, job(ref));
        assertTrue(SimulatorFulfilmentAdaptor.script(ref).queries.get() >= 1);
        assertEquals(0, deliveries(ref), "the dead instance's delivery was confirmed by query, not repeated");
    }

    @Test
    void instanceDiedBeforeSending_isQueuedAgainAndDeliveredOnce() throws Exception {
        String ref = confirmedPayment();
        // Claimed, but died before FULFILMENT_PENDING was committed: nothing was sent.
        assertTrue(queue.claim(ref, "dead-instance", LocalDateTime.now(), LocalDateTime.now().plusSeconds(1)));

        worker.runOnce(LocalDateTime.now().plusMinutes(10)); // lease expired -> UNKNOWN -> NOT_STARTED -> QUEUED
        makeDue(ref);
        worker.runOnce(LocalDateTime.now().plusMinutes(10));
        assertEquals(TransactionStatus.COMPLETED, status(ref));
        assertEquals(1, deliveries(ref));
    }

    @Test
    void confirmedPaymentWithoutAJob_isPickedUp() throws Exception {
        String ref = confirmedPayment();
        jdbc.update("DELETE FROM fulfilment_jobs WHERE transaction_reference = ?", ref); // as if confirmed before this release
        worker.runOnce(LocalDateTime.now());
        assertEquals(TransactionStatus.COMPLETED, status(ref));
        assertEquals(1, deliveries(ref));
    }
}
