package zw.co.payport.gateway.ledger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static zw.co.payport.gateway.ledger.LedgerCategory.*;
import static zw.co.payport.gateway.ledger.LedgerDb.*;

/** Phase 1 exit criterion E2: concurrent spending can never overdraw or double-post. */
@Tag("ledger-db")
class LedgerConcurrencyTest {

    private static JournalRequest.Builder req(String key) {
        return JournalRequest.builder(key).entity(ENTITY).book(Book.LIVE).effectiveDate(LocalDate.now()).postedBy("concurrency-test");
    }

    @Test
    void twoHundredParallelDebitsNeverOverdrawOrDoublePost() throws Exception {
        LedgerClient ledger = new LedgerClient(app());
        String m = "c" + uid();
        AccountKey receivable = live(PROVIDER_RECEIVABLE, "ECOCASH_" + m, "USD");
        AccountKey payable = live(MERCHANT_PAYABLE, m, "USD");
        AccountKey settlement = live(SETTLEMENT_PAYABLE, m, "USD");
        ledger.post(req("fund-" + m).debit(receivable, 10_000, null).credit(payable, 10_000, null).build());

        int threads = 200;
        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger posted = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        Map<String, Integer> otherErrors = new ConcurrentHashMap<>();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            fs.add(pool.submit(() -> {
                start.await();
                try {
                    ledger.post(req("spend-" + m + "-" + n).debit(payable, 100, null).credit(settlement, 100, null).build());
                    posted.incrementAndGet();
                } catch (LedgerException e) {
                    if ("LEDGER_INSUFFICIENT_FUNDS".equals(e.code())) insufficient.incrementAndGet();
                    else otherErrors.merge(e.getMessage(), 1, Integer::sum);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : fs) f.get(120, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(Map.of(), otherErrors, "no deadlocks or other failures should surface (retries absorb transient lock errors)");
        assertEquals(100, posted.get(), "exactly the available funds were spent");
        assertEquals(100, insufficient.get());
        assertEquals(0, ledger.balance(payable).balance());
        assertEquals(10_000, ledger.balance(settlement).balance());
        assertEquals(101, queryLong(app(), "SELECT COUNT(*) FROM ledger_entries WHERE idempotency_key LIKE '%" + m + "%'"),
                "1 funding entry + 100 spends");
        assertTrue(ledger.integrityReport().isEmpty(), () -> "integrity: " + ledger.integrityReport());
    }

    @Test
    void sameIdempotencyKeyFromManyThreadsPostsExactlyOnce() throws Exception {
        LedgerClient ledger = new LedgerClient(app());
        String m = "k" + uid();
        AccountKey receivable = live(PROVIDER_RECEIVABLE, "ECOCASH_" + m, "USD");
        AccountKey payable = live(MERCHANT_PAYABLE, m, "USD");
        JournalRequest same = req("same-" + m).debit(receivable, 777, null).credit(payable, 777, null).build();

        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<LedgerClient.PostResult>> fs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            fs.add(pool.submit(() -> {
                start.await();
                return ledger.post(same);
            }));
        }
        start.countDown();
        int postedCount = 0;
        long entryId = -1;
        for (Future<LedgerClient.PostResult> f : fs) {
            LedgerClient.PostResult r = f.get(120, TimeUnit.SECONDS);
            if (r.outcome() == LedgerClient.Outcome.POSTED) postedCount++;
            if (entryId < 0) entryId = r.entryId();
            assertEquals(entryId, r.entryId(), "every caller gets the same entry");
        }
        pool.shutdown();
        assertEquals(1, postedCount);
        assertEquals(777, ledger.balance(payable).balance());
    }

    @Test
    void opposingTransfersBetweenTwoMerchantsDoNotDeadlockOrLoseMoney() throws Exception {
        LedgerClient ledger = new LedgerClient(app());
        String m = "d" + uid();
        AccountKey receivable = live(PROVIDER_RECEIVABLE, "ECOCASH_" + m, "USD");
        AccountKey a = live(MERCHANT_PAYABLE, "a" + m, "USD");
        AccountKey b = live(MERCHANT_PAYABLE, "b" + m, "USD");
        ledger.post(req("fa-" + m).debit(receivable, 50_000, null).credit(a, 50_000, null).build());
        ledger.post(req("fb-" + m).debit(receivable, 50_000, null).credit(b, 50_000, null).build());

        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        Map<String, Integer> errors = new ConcurrentHashMap<>();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            final int n = i;
            fs.add(pool.submit(() -> {
                start.await();
                AccountKey from = n % 2 == 0 ? a : b, to = n % 2 == 0 ? b : a;
                try {
                    ledger.post(req("x-" + m + "-" + n).debit(from, 10, null).credit(to, 10, null).build());
                } catch (LedgerException e) {
                    errors.merge(e.code() + " " + e.getMessage(), 1, Integer::sum);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : fs) f.get(120, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(Map.of(), errors);
        assertEquals(100_000, ledger.balance(a).balance() + ledger.balance(b).balance(), "money is conserved");
        assertEquals(50_000, ledger.balance(a).balance(), "100 transfers each way cancel out");
    }
}
