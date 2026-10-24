package zw.co.payport.gateway.rules;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.risk.ComplianceAudit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Limits, charges and tax set by ops for each kind of party (WALLET customers, AGENT, MERCHANT), per
 * product, currency and channel:
 *
 *  - per transaction: minimum and maximum value;
 *  - per day, week (from Monday) and month: maximum value and maximum number of transactions;
 *  - charge: flat + percentage, bounded by a minimum and a maximum charge;
 *  - tax: whether it is charged, the rate, and whether it is on the charge or on the amount.
 *
 * A rule's product is a product code (e.g. AIRTIME_ECONET), a group (AIRTIME, DATA, BILLS, P2P_TRANSFER,
 * CASH_OUT, CASH_IN, MERCHANT_PAYMENT, COLLECTION, ...) or ALL; its channel is one channel or ALL. The most
 * specific active rule applies: product code before group before ALL, a named channel before ALL.
 * No rule means no extra limit and no charge.
 */
@Service
public class PartyRules {

    public static final Set<String> PARTIES = Set.of("WALLET", "AGENT", "MERCHANT");
    public static final List<String> CHANNELS = List.of("ALL", "MOB", "WEB", "USD", "POS", "WHATSAPP", "API", "AGENT");
    public static final Map<String, List<String>> PRODUCT_GROUPS = Map.of(
            "WALLET", List.of("ALL", "AIRTIME", "DATA", "BILLS", "WALLET_TOPUP", "P2P_TRANSFER", "CASH_OUT", "MERCHANT_PAYMENT", "FX_CONVERSION"),
            "AGENT", List.of("ALL", "CASH_IN", "CASH_OUT"),
            "MERCHANT", List.of("ALL", "COLLECTION"));

    public record Rule(long id, String partyType, String product, String currency, String channel,
                       Long minAmount, Long maxAmount, Long dailyMax, Long weeklyMax, Long monthlyMax,
                       Integer dailyCount, Integer weeklyCount, Integer monthlyCount,
                       long chargeFlat, int chargeBps, Long chargeMin, Long chargeMax,
                       boolean taxChargeable, int taxBps, String taxBase) {}

    /** What the party pays on top of the amount. */
    public record Charge(long charge, long tax) {
        public static final Charge NONE = new Charge(0, 0);
        public long total() { return charge + tax; }
    }

    /** Value and count already used in a period. */
    public record Totals(long value, long count) {}

    /** Sums what the party already did in [from, now) for a rule's product and channel scope. */
    @FunctionalInterface
    public interface Usage {
        Totals since(String product, String channel, LocalDateTime from);
    }

    private final JdbcTemplate jdbc;
    private final ComplianceAudit audit;

    public PartyRules(JdbcTemplate jdbc, ComplianceAudit audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ─── configuration ───────────────────────────────────────────────────────

    public List<Map<String, Object>> list(String partyType) {
        return jdbc.queryForList("SELECT * FROM party_rules WHERE party_type = ? ORDER BY currency, product, channel", party(partyType));
    }

    /** Products a rule can name for this party: the groups and the configured product codes. */
    public Map<String, Object> options(String partyType) {
        String p = party(partyType);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groups", PRODUCT_GROUPS.get(p));
        out.put("products", "WALLET".equals(p)
                ? jdbc.queryForList("SELECT code, name, category FROM product_types WHERE is_active = TRUE ORDER BY category, name")
                : List.of());
        out.put("channels", CHANNELS);
        return out;
    }

    public Map<String, Object> save(String partyType, Map<String, Object> b, String by) {
        String p = party(partyType);
        String product = text(b.get("product"), "ALL").toUpperCase(Locale.ROOT);
        String currency = text(b.get("currency"), "").toUpperCase(Locale.ROOT);
        String channel = channel(text(b.get("channel"), "ALL"));
        if (!currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("Choose the currency");
        if (!CHANNELS.contains(channel)) throw new IllegalArgumentException("Unknown channel " + channel);
        String taxBase = text(b.get("taxBase"), "CHARGE").toUpperCase(Locale.ROOT);
        if (!Set.of("CHARGE", "AMOUNT").contains(taxBase)) throw new IllegalArgumentException("Tax is on the CHARGE or on the AMOUNT");
        Long min = minor(b.get("minAmount")), max = minor(b.get("maxAmount"));
        if (min != null && max != null && min > max) throw new IllegalArgumentException("The minimum is above the maximum");
        Long cMin = minor(b.get("chargeMin")), cMax = minor(b.get("chargeMax"));
        if (cMin != null && cMax != null && cMin > cMax) throw new IllegalArgumentException("The minimum charge is above the maximum charge");
        int chargeBps = bps(b.get("chargePercent")), taxBps = bps(b.get("taxPercent"));
        Long flat = minor(b.get("chargeFlat"));
        Object[] values = {min, max, minor(b.get("dailyMax")), minor(b.get("weeklyMax")), minor(b.get("monthlyMax")),
                count(b.get("dailyCount")), count(b.get("weeklyCount")), count(b.get("monthlyCount")),
                flat == null ? 0L : flat, chargeBps, cMin, cMax, bool(b.get("taxChargeable")), taxBps, taxBase,
                b.get("active") == null || bool(b.get("active")), by, Timestamp.valueOf(LocalDateTime.now())};
        Object id = b.get("id");
        if (id != null && !String.valueOf(id).isBlank()) {
            long rid = Long.parseLong(String.valueOf(id));
            Object[] args = new Object[values.length + 5];
            args[0] = product; args[1] = currency; args[2] = channel;
            System.arraycopy(values, 0, args, 3, values.length);
            args[values.length + 3] = rid; args[values.length + 4] = p;
            try {
                int n = jdbc.update("UPDATE party_rules SET product = ?, currency = ?, channel = ?, min_amount_minor = ?, max_amount_minor = ?, daily_max_minor = ?, "
                        + "weekly_max_minor = ?, monthly_max_minor = ?, daily_max_count = ?, weekly_max_count = ?, monthly_max_count = ?, charge_flat_minor = ?, "
                        + "charge_percent_bps = ?, charge_min_minor = ?, charge_max_minor = ?, tax_chargeable = ?, tax_percent_bps = ?, tax_base = ?, active = ?, "
                        + "updated_by = ?, updated_at = ? WHERE id = ? AND party_type = ?", args);
                if (n == 0) throw new IllegalArgumentException("No such rule");
            } catch (org.springframework.dao.DuplicateKeyException e) {
                throw new IllegalStateException("There is already a rule for this product, currency and channel");
            }
            audit.record(by, "RULE_UPDATED", "rule:" + p + ":" + rid, product + " " + currency + " " + channel);
            return get(rid);
        }
        Object[] args = new Object[values.length + 4];
        args[0] = p; args[1] = product; args[2] = currency; args[3] = channel;
        System.arraycopy(values, 0, args, 4, values.length);
        try {
            jdbc.update("INSERT INTO party_rules (party_type, product, currency, channel, min_amount_minor, max_amount_minor, daily_max_minor, weekly_max_minor, "
                    + "monthly_max_minor, daily_max_count, weekly_max_count, monthly_max_count, charge_flat_minor, charge_percent_bps, charge_min_minor, "
                    + "charge_max_minor, tax_chargeable, tax_percent_bps, tax_base, active, updated_by, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", args);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalStateException("There is already a rule for this product, currency and channel");
        }
        Long rid = jdbc.queryForObject("SELECT id FROM party_rules WHERE party_type = ? AND product = ? AND currency = ? AND channel = ?", Long.class, p, product, currency, channel);
        audit.record(by, "RULE_CREATED", "rule:" + p + ":" + rid, product + " " + currency + " " + channel);
        return get(rid);
    }

    public void delete(String partyType, long id, String by) {
        if (jdbc.update("DELETE FROM party_rules WHERE id = ? AND party_type = ?", id, party(partyType)) == 0) throw new IllegalArgumentException("No such rule");
        audit.record(by, "RULE_DELETED", "rule:" + party(partyType) + ":" + id, "");
    }

    public Map<String, Object> get(long id) {
        return jdbc.queryForList("SELECT * FROM party_rules WHERE id = ?", id).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("No such rule"));
    }

    // ─── applying ────────────────────────────────────────────────────────────

    /** The most specific active rule; products are given most specific first (e.g. AIRTIME_ECONET, AIRTIME). */
    public Optional<Rule> match(String partyType, List<String> products, String currency, String channel) {
        String p = party(partyType);
        String ch = channel(channel);
        List<String> names = new ArrayList<>();
        for (String x : products) if (x != null && !x.isBlank()) names.add(x.toUpperCase(Locale.ROOT));
        names.add("ALL");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM party_rules WHERE party_type = ? AND currency = ? AND active = TRUE AND channel IN (?, 'ALL')",
                p, currency == null ? "" : currency.toUpperCase(Locale.ROOT), ch);
        Map<String, Object> best = null;
        int bestScore = -1;
        for (Map<String, Object> r : rows) {
            int pi = names.indexOf(String.valueOf(r.get("product")));
            if (pi < 0) continue;
            int score = (names.size() - pi) * 2 + ("ALL".equals(r.get("channel")) ? 0 : 1);
            if (score > bestScore) { best = r; bestScore = score; }
        }
        return Optional.ofNullable(best).map(PartyRules::rule);
    }

    /** Charge and tax for an amount under a rule (none without one). */
    public static Charge charge(Optional<Rule> rule, long amount) {
        if (rule.isEmpty()) return Charge.NONE;
        Rule r = rule.get();
        long c = r.chargeFlat() + BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(r.chargeBps())).divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP).longValueExact();
        if (r.chargeMin() != null && c < r.chargeMin()) c = r.chargeMin();
        if (r.chargeMax() != null && c > r.chargeMax()) c = r.chargeMax();
        long tax = 0;
        if (r.taxChargeable() && r.taxBps() > 0) {
            long base = "AMOUNT".equals(r.taxBase()) ? amount : c;
            tax = BigDecimal.valueOf(base).multiply(BigDecimal.valueOf(r.taxBps())).divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP).longValueExact();
        }
        return new Charge(c, tax);
    }

    /**
     * Checks the amount against the matching rule's limits (refusing with a message the customer can read)
     * and returns the charge and tax that apply.
     */
    public Charge check(String partyType, List<String> products, String currency, String channel, long amount, Usage usage) {
        Optional<Rule> rule = match(partyType, products, currency, channel);
        if (rule.isEmpty()) return Charge.NONE;
        Rule r = rule.get();
        String ccy = r.currency();
        if (r.minAmount() != null && amount < r.minAmount()) throw new LimitException("The minimum is " + ccy + " " + major(r.minAmount()));
        if (r.maxAmount() != null && amount > r.maxAmount()) throw new LimitException("The maximum is " + ccy + " " + major(r.maxAmount()));
        LocalDate today = LocalDate.now();
        period(r.dailyMax(), r.dailyCount(), "today", today.atStartOfDay(), r, amount, usage);
        period(r.weeklyMax(), r.weeklyCount(), "this week", today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(), r, amount, usage);
        period(r.monthlyMax(), r.monthlyCount(), "this month", today.withDayOfMonth(1).atStartOfDay(), r, amount, usage);
        return charge(rule, amount);
    }

    private static void period(Long maxValue, Integer maxCount, String label, LocalDateTime from, Rule r, long amount, Usage usage) {
        if (maxValue == null && maxCount == null) return;
        Totals t = usage.since(r.product(), r.channel(), from);
        if (maxValue != null && t.value() + amount > maxValue) {
            throw new LimitException("This would go over the limit of " + r.currency() + " " + major(maxValue) + " " + label
                    + " (" + r.currency() + " " + major(Math.max(0, maxValue - t.value())) + " left)");
        }
        if (maxCount != null && t.count() + 1 > maxCount) {
            throw new LimitException("You have reached the limit of " + maxCount + " transaction" + (maxCount == 1 ? "" : "s") + " " + label);
        }
    }

    /** Records a completed operation for the period limits (idempotent per operation reference). */
    public void record(String partyType, String partyKey, String product, String currency, String channel, long amount, Charge charge, String opRef) {
        jdbc.update("INSERT INTO party_usage (party_type, party_key, product, currency, channel, amount_minor, charge_minor, tax_minor, op_ref, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE op_ref = op_ref",
                party(partyType), partyKey, product.toUpperCase(Locale.ROOT), currency.toUpperCase(Locale.ROOT), channel(channel), amount,
                charge == null ? 0 : charge.charge(), charge == null ? 0 : charge.tax(), opRef, Timestamp.valueOf(LocalDateTime.now()));
    }

    /** Usage from party_usage for one party (for wallet, agent and merchant operations). */
    public Usage recorded(String partyType, String partyKey, String currency) {
        return (product, channel, from) -> {
            StringBuilder sql = new StringBuilder("SELECT COALESCE(SUM(amount_minor), 0) AS v, COUNT(*) AS c FROM party_usage WHERE party_type = ? AND party_key = ? AND currency = ? AND created_at >= ?");
            List<Object> args = new ArrayList<>(List.of(party(partyType), partyKey, currency.toUpperCase(Locale.ROOT), Timestamp.valueOf(from)));
            if (!"ALL".equals(product)) { sql.append(" AND product = ?"); args.add(product); }
            if (!"ALL".equals(channel)) { sql.append(" AND channel = ?"); args.add(channel); }
            Map<String, Object> m = jdbc.queryForMap(sql.toString(), args.toArray());
            return new Totals(((Number) m.get("v")).longValue(), ((Number) m.get("c")).longValue());
        };
    }

    public static class LimitException extends IllegalArgumentException {
        public LimitException(String message) { super(message); }
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    static Rule rule(Map<String, Object> r) {
        return new Rule(((Number) r.get("id")).longValue(), (String) r.get("party_type"), (String) r.get("product"), (String) r.get("currency"), (String) r.get("channel"),
                l(r.get("min_amount_minor")), l(r.get("max_amount_minor")), l(r.get("daily_max_minor")), l(r.get("weekly_max_minor")), l(r.get("monthly_max_minor")),
                i(r.get("daily_max_count")), i(r.get("weekly_max_count")), i(r.get("monthly_max_count")),
                ((Number) r.get("charge_flat_minor")).longValue(), ((Number) r.get("charge_percent_bps")).intValue(), l(r.get("charge_min_minor")), l(r.get("charge_max_minor")),
                truthy(r.get("tax_chargeable")), ((Number) r.get("tax_percent_bps")).intValue(), (String) r.get("tax_base"));
    }

    public static String channel(String c) {
        String v = c == null || c.isBlank() ? "ALL" : c.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "USSD" -> "USD";
            case "MOBILE", "APP" -> "MOB";
            default -> v;
        };
    }

    static String party(String p) {
        String v = p == null ? "" : p.trim().toUpperCase(Locale.ROOT);
        if (!PARTIES.contains(v)) throw new IllegalArgumentException("Unknown party " + p);
        return v;
    }

    private static Long l(Object o) { return o == null ? null : ((Number) o).longValue(); }
    private static Integer i(Object o) { return o == null ? null : ((Number) o).intValue(); }
    private static boolean truthy(Object o) { return Boolean.TRUE.equals(o) || (o instanceof Number n && n.intValue() != 0); }
    private static boolean bool(Object o) { return o instanceof Boolean b ? b : o != null && Set.of("true", "1", "yes", "on").contains(String.valueOf(o).toLowerCase(Locale.ROOT)); }

    private static String text(Object o, String d) {
        return o == null || String.valueOf(o).isBlank() ? d : String.valueOf(o).trim();
    }

    /** Amounts arrive in major units ("12.50"); blank means no limit. */
    static Long minor(Object o) {
        if (o == null || String.valueOf(o).isBlank()) return null;
        try {
            BigDecimal v = new BigDecimal(String.valueOf(o).trim());
            if (v.signum() < 0) throw new IllegalArgumentException("Amounts cannot be negative");
            return v.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("Not an amount: " + o);
        }
    }

    /** Percentages arrive as "1.5" (per cent) and are kept in basis points. */
    static int bps(Object o) {
        if (o == null || String.valueOf(o).isBlank()) return 0;
        try {
            BigDecimal v = new BigDecimal(String.valueOf(o).trim());
            if (v.signum() < 0 || v.compareTo(BigDecimal.valueOf(100)) > 0) throw new IllegalArgumentException("A percentage is between 0 and 100");
            return v.movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("Not a percentage: " + o);
        }
    }

    static Integer count(Object o) {
        if (o == null || String.valueOf(o).isBlank()) return null;
        try {
            int v = Integer.parseInt(String.valueOf(o).trim());
            if (v < 0) throw new IllegalArgumentException("Counts cannot be negative");
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a whole number: " + o);
        }
    }

    static String major(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }
}
