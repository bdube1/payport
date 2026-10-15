package zw.co.payport.gateway.psp.risk;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure fraud-rule evaluation (no I/O, unit-tested). The engine gathers the
 * features (history stats) and this decides which rules fire.
 */
public final class RuleEvaluator {

    public record Rule(String code, String type, List<String> appliesTo, int windowMinutes, int thresholdCount,
                       long thresholdAmountUsdMinor, int score, String action) {}

    /** History for one subject over the rule's window, NOT including the current transaction. */
    public record Window(long count, long sumUsdMinor, long distinctCounterparties) {}

    public record Features(String txnType, long amountUsdMinor, boolean newCounterparty, LocalTime localTime) {}

    public record Hit(String code, int score, String action, String reason) {}

    public record Outcome(String action, int score, List<Hit> hits) {}

    private RuleEvaluator() {}

    public static boolean applies(Rule r, String txnType) {
        return r.appliesTo().contains("*") || r.appliesTo().contains(txnType);
    }

    public static Hit evaluate(Rule r, Features f, Window w) {
        if (!applies(r, f.txnType())) return null;
        return switch (r.type()) {
            case "SINGLE_AMOUNT" -> f.amountUsdMinor() >= r.thresholdAmountUsdMinor()
                    ? hit(r, "Amount " + f.amountUsdMinor() + " >= " + r.thresholdAmountUsdMinor()) : null;
            case "VELOCITY_COUNT" -> w.count() + 1 > r.thresholdCount()
                    ? hit(r, (w.count() + 1) + " transactions in " + r.windowMinutes() + " min (limit " + r.thresholdCount() + ")") : null;
            case "VELOCITY_AMOUNT" -> w.sumUsdMinor() + f.amountUsdMinor() > r.thresholdAmountUsdMinor()
                    ? hit(r, "Value " + (w.sumUsdMinor() + f.amountUsdMinor()) + " in " + r.windowMinutes() + " min (limit " + r.thresholdAmountUsdMinor() + ")") : null;
            case "NEW_COUNTERPARTY_AMOUNT" -> f.newCounterparty() && f.amountUsdMinor() >= r.thresholdAmountUsdMinor()
                    ? hit(r, "First payment to this counterparty and amount >= " + r.thresholdAmountUsdMinor()) : null;
            case "NIGHT_AMOUNT" -> isNight(f.localTime()) && f.amountUsdMinor() >= r.thresholdAmountUsdMinor()
                    ? hit(r, "High value between 23:00 and 05:00") : null;
            case "DISTINCT_COUNTERPARTIES" -> w.distinctCounterparties() + (f.newCounterparty() ? 1 : 0) > r.thresholdCount()
                    ? hit(r, "Paid " + (w.distinctCounterparties() + 1) + " different counterparties in " + r.windowMinutes() + " min") : null;
            default -> null;
        };
    }

    /**
     * Combines hits: any BLOCK rule blocks; otherwise the total score decides
     * (>= blockScore blocks, >= reviewScore reviews).
     */
    public static Outcome combine(List<Hit> hits, int reviewScore, int blockScore) {
        int score = Math.min(100, hits.stream().mapToInt(Hit::score).sum());
        boolean hardBlock = hits.stream().anyMatch(h -> "BLOCK".equals(h.action()));
        String action = hardBlock || score >= blockScore ? "BLOCK" : (score >= reviewScore || hits.stream().anyMatch(h -> "REVIEW".equals(h.action()))) ? "REVIEW" : "ALLOW";
        return new Outcome(action, score, new ArrayList<>(hits));
    }

    static boolean isNight(LocalTime t) {
        return t != null && (t.isAfter(LocalTime.of(22, 59)) || t.isBefore(LocalTime.of(5, 0)));
    }

    private static Hit hit(Rule r, String reason) {
        return new Hit(r.code(), r.score(), r.action(), r.code() + ": " + reason);
    }
}
