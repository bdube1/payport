package zw.co.payport.gateway.recon;

import org.junit.jupiter.api.Test;
import zw.co.payport.gateway.recon.ReconEngine.Finding;
import zw.co.payport.gateway.recon.ReconEngine.Line;
import zw.co.payport.gateway.recon.ReconEngine.Payment;
import zw.co.payport.gateway.recon.ReconEngine.Result;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Phase 3 acceptance (pure engine): injected differences are all detected and categorised. */
class ReconEngineTest {

    static final LocalDateTime START = LocalDateTime.of(2026, 10, 15, 0, 0);
    static final LocalDateTime END = LocalDateTime.of(2026, 10, 15, 23, 59);

    static Payment paid(String ref, long amount, LocalDateTime at, String... providerRefs) {
        return new Payment(ref, Set.of(providerRefs), amount, "USD", true, "COMPLETED", at);
    }

    static Line line(long id, String ref, long amount, String ccy) {
        return new Line(id, List.of(ref), amount, ccy, START.plusHours(10));
    }

    @Test
    void everyInjectedDifferenceIsDetectedAndCategorised() {
        List<Payment> payments = List.of(
                paid("PP-WEB-20261015-000001", 500, START.plusHours(9)),                     // matched
                paid("PP-WEB-20261015-000002", 700, START.plusHours(9), "ECO-2"),            // matched by provider ref, then duplicated
                paid("PP-WEB-20261015-000003", 900, START.plusHours(9)),                     // amount mismatch
                paid("PP-WEB-20261015-000004", 100, START.plusHours(9)),                     // currency mismatch
                new Payment("PP-WEB-20261015-000005", Set.of(), 300, "USD", false, "PAYMENT_TIMEOUT", null), // paid after timeout
                paid("PP-WEB-20261015-000006", 400, START.minusHours(4)),                      // timing: yesterday's payment in today's file
                paid("PP-WEB-20261015-000007", 800, START.plusHours(8)),                     // missing in statement
                paid("PP-WEB-20261015-000008", 650, END.minusMinutes(10)),                   // timing: expected in the next statement
                paid("PP-WEB-20261015-000009", 250, START.plusHours(9)));                    // matched via attempt reference
        List<Line> lines = List.of(
                line(1, "PP-WEB-20261015-000001", 500, "USD"),
                line(2, "ECO-2", 700, "USD"),
                line(3, "PP-WEB-20261015-000002", 700, "USD"),
                line(4, "PP-WEB-20261015-000003", 990, "USD"),
                line(5, "PP-WEB-20261015-000004", 100, "ZWG"),
                line(6, "PP-WEB-20261015-000005", 300, "USD"),
                line(7, "PP-WEB-20261015-000006", 400, "USD"),
                line(8, "UNKNOWN-REF-77", 1200, "USD"),
                line(9, "PP-WEB-20261015-000009-2", 250, "USD"));

        List<Finding> f = ReconEngine.match(lines, payments, START, END, Duration.ofMinutes(60));
        Map<Result, List<String>> by = f.stream().collect(Collectors.groupingBy(Finding::result,
                Collectors.mapping(x -> x.transactionReference() == null ? "?" : x.transactionReference(), Collectors.toList())));

        assertEquals(List.of("PP-WEB-20261015-000001", "PP-WEB-20261015-000002", "PP-WEB-20261015-000009"), by.get(Result.MATCHED));
        assertEquals(List.of("PP-WEB-20261015-000002"), by.get(Result.DUPLICATE));
        assertEquals(List.of("PP-WEB-20261015-000003"), by.get(Result.AMOUNT_MISMATCH));
        assertEquals(List.of("PP-WEB-20261015-000004"), by.get(Result.CURRENCY_MISMATCH));
        assertEquals(List.of("PP-WEB-20261015-000005"), by.get(Result.STATUS_MISMATCH));
        assertEquals(List.of("?"), by.get(Result.MISSING_IN_PAYPORT));
        assertEquals(List.of("PP-WEB-20261015-000007"), by.get(Result.MISSING_IN_STATEMENT));
        assertEquals(List.of("PP-WEB-20261015-000006", "PP-WEB-20261015-000008"), by.get(Result.TIMING));
        assertEquals(lines.size() + 2, f.size(), "one finding per line plus each unmatched paid payment");
    }

    @Test
    void zigAndZwgAreTheSameCurrency() {
        List<Finding> f = ReconEngine.match(List.of(line(1, "PP-WEB-20261015-000001", 500, "ZIG")),
                List.of(new Payment("PP-WEB-20261015-000001", Set.of(), 500, "ZWG", true, "COMPLETED", START.plusHours(9))),
                START, END, Duration.ofMinutes(60));
        assertEquals(Result.MATCHED, f.get(0).result());
    }
}
