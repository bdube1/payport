package zw.co.payport.gateway.psp.routing;

import zw.co.payport.gateway.psp.ledger.domain.Money;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Pure ranking logic for smart routing (no Spring, unit-tested).
 *
 * Order of precedence:
 *  1. hard filters: amount band, network match (wallets), provider DOWN
 *  2. rule priority (ops-controlled, lower wins)
 *  3. health: UP before DEGRADED
 *  4. recent failures in the last 15 minutes (fewer wins)
 *  5. effective cost for this amount (cheaper wins)
 */
public final class RouteScorer {

    public record Rule(String provider, int priority, long minAmountMinor, long maxAmountMinor,
                       int costBps, long costFixedMinor, String networkCode) {}

    public record Candidate(String provider, boolean eligible, String reason, int priority, String health,
                            long recentFailures, long costMinor) {}

    private RouteScorer() {}

    public static List<Candidate> rank(List<Rule> rules, long amountMinor, String customerNetwork,
                                       Map<String, String> health, Map<String, Long> recentFailures) {
        List<Candidate> out = new ArrayList<>();
        for (Rule r : rules) {
            String h = health.getOrDefault(r.provider().toUpperCase(), "UNKNOWN");
            long fails = recentFailures.getOrDefault(r.provider().toUpperCase(), 0L);
            long cost = Math.addExact(Money.applyBps(amountMinor, r.costBps()), r.costFixedMinor());
            String reason = null;
            if (amountMinor < r.minAmountMinor()) reason = "below provider minimum " + r.minAmountMinor();
            else if (r.maxAmountMinor() > 0 && amountMinor > r.maxAmountMinor()) reason = "above provider maximum " + r.maxAmountMinor();
            else if (r.networkCode() != null && customerNetwork != null && !r.networkCode().equalsIgnoreCase(customerNetwork))
                reason = "customer number is on " + customerNetwork + ", provider serves " + r.networkCode();
            else if ("DOWN".equals(h)) reason = "provider is DOWN";
            out.add(new Candidate(r.provider(), reason == null, reason == null ? "eligible" : reason, r.priority(), h, fails, cost));
        }
        out.sort(Comparator.comparing((Candidate c) -> !c.eligible())
                .thenComparingInt(Candidate::priority)
                .thenComparingInt(c -> "UP".equals(c.health()) ? 0 : "UNKNOWN".equals(c.health()) ? 1 : 2)
                .thenComparingLong(Candidate::recentFailures)
                .thenComparingLong(Candidate::costMinor));
        return out;
    }
}
