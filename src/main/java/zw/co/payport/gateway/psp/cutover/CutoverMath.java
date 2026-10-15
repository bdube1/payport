package zw.co.payport.gateway.psp.cutover;

import zw.co.payport.gateway.psp.ledger.domain.AccountKey;
import zw.co.payport.gateway.psp.legacy.LegacyGateway;

import java.util.*;
import java.util.function.Predicate;

/** Pure cutover arithmetic (unit-tested). */
public final class CutoverMath {
    private CutoverMath() {}

    public static List<LedgerCutoverService.CurrencyPreview> preview(List<LegacyGateway.UnsettledSession> sessions,
                                                                     Predicate<String> alreadyInLedger) {
        Map<String, long[]> sums = new TreeMap<>();
        Map<String, List<Long>> ids = new TreeMap<>();
        for (LegacyGateway.UnsettledSession s : sessions) {
            if (alreadyInLedger.test(s.transactionReference())) continue;
            String ccy = AccountKey.normaliseCurrency(s.currency());
            sums.computeIfAbsent(ccy, k -> new long[2]);
            sums.get(ccy)[0] += s.netRemainingCents();
            sums.get(ccy)[1]++;
            ids.computeIfAbsent(ccy, k -> new ArrayList<>()).add(s.sessionId());
        }
        List<LedgerCutoverService.CurrencyPreview> out = new ArrayList<>();
        sums.forEach((ccy, v) -> out.add(new LedgerCutoverService.CurrencyPreview(ccy, (int) v[1], v[0], ids.get(ccy))));
        return out;
    }
}
