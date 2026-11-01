package zw.co.payport.gateway.intelligence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Forecasts for finance and agents, from the ledger and agent operations (no outside service):
 *  - settlement: how much merchants will be owed over the next days (what accrues to MERCHANT_PAYABLE), on top of what
 *    they are owed now, so finance can have the money at the sponsor bank in time;
 *  - agent float: each agent's expected net use of e-float (cash-in uses it, cash-out returns it), when it will run out
 *    at that pace, and the top-up that covers the next week on a busy week (90th percentile).
 *
 * Method: daily totals for the last 8 weeks; a level smoothed exponentially (recent days weigh more) times a
 * day-of-week pattern (Saturdays and month ends look different from Tuesdays); the 90th percentile adds 1.28 standard
 * deviations of how far the method missed on past days. With under two weeks of history the forecast says so.
 */
@Service
public class ForecastService {

    static final int HISTORY_DAYS = 56, HORIZON = 7;
    static final double ALPHA = 0.3, Z90 = 1.2816;

    public record Forecast(double[] expected, double[] p90, int historyDays, boolean enoughHistory) {}

    private final JdbcTemplate jdbc;

    public ForecastService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Forecasts {@code horizon} days after the last day of {@code daily} (oldest first; index i is the day
     * {@code lastDay - (daily.length - 1 - i)}).
     */
    static Forecast forecast(double[] daily, LocalDate lastDay, int horizon) {
        int n = daily.length;
        double[] exp = new double[horizon], p90 = new double[horizon];
        if (n == 0) return new Forecast(exp, p90, 0, false);
        // day-of-week pattern: each weekday's average over the overall average
        double total = 0;
        double[] dowSum = new double[7];
        int[] dowN = new int[7];
        for (int i = 0; i < n; i++) {
            int dow = lastDay.minusDays(n - 1 - i).getDayOfWeek().getValue() - 1;
            dowSum[dow] += daily[i];
            dowN[dow]++;
            total += daily[i];
        }
        double mean = total / n;
        double[] season = new double[7];
        for (int d = 0; d < 7; d++) season[d] = mean == 0 || dowN[d] == 0 ? 1 : (dowSum[d] / dowN[d]) / mean;
        if (n < 14) java.util.Arrays.fill(season, 1);
        // smoothed level of the de-seasonalised series, and how far one-day-ahead guesses missed
        double level = Double.NaN, sq = 0;
        int misses = 0;
        for (int i = 0; i < n; i++) {
            int dow = lastDay.minusDays(n - 1 - i).getDayOfWeek().getValue() - 1;
            double s = season[dow] == 0 ? 1 : season[dow];
            double x = daily[i] / s;
            if (Double.isNaN(level)) { level = x; continue; }
            double guess = level * s;
            sq += (daily[i] - guess) * (daily[i] - guess);
            misses++;
            level = ALPHA * x + (1 - ALPHA) * level;
        }
        double sd = misses == 0 ? 0 : Math.sqrt(sq / misses);
        for (int h = 0; h < horizon; h++) {
            int dow = lastDay.plusDays(h + 1).getDayOfWeek().getValue() - 1;
            exp[h] = level * season[dow];
            p90[h] = exp[h] >= 0 ? exp[h] + Z90 * sd : exp[h] - Z90 * sd;
        }
        return new Forecast(exp, p90, n, n >= 14);
    }

    private static double[] series(List<Map<String, Object>> rows, LocalDate first, int days) {
        double[] out = new double[days];
        for (Map<String, Object> r : rows) {
            LocalDate d = r.get("d") instanceof java.sql.Date sd ? sd.toLocalDate() : LocalDate.parse(String.valueOf(r.get("d")).substring(0, 10));
            int i = (int) java.time.temporal.ChronoUnit.DAYS.between(first, d);
            if (i >= 0 && i < days) out[i] += ((Number) r.get("v")).doubleValue();
        }
        return out;
    }

    private static List<Map<String, Object>> days(LocalDate today, Forecast f, String expectedKey, String p90Key) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int h = 0; h < f.expected().length; h++) {
            Map<String, Object> v = new LinkedHashMap<>();
            LocalDate d = today.plusDays(h + 1);
            v.put("date", d.toString());
            v.put("day", d.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH));
            v.put(expectedKey, Math.round(f.expected()[h]));
            v.put(p90Key, Math.round(f.p90()[h]));
            out.add(v);
        }
        return out;
    }

    /** Settlement needs per currency: owed now, and expected to become owed over the next 7 days. */
    public List<Map<String, Object>> settlement() {
        LocalDate today = LocalDate.now(), first = today.minusDays(HISTORY_DAYS);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String ccy : jdbc.queryForList("SELECT DISTINCT currency FROM ledger_accounts WHERE category = 'MERCHANT_PAYABLE' AND book = 'LIVE'", String.class)) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT e.effective_date AS d, SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount_minor ELSE 0 END) AS v "
                    + "FROM ledger_lines l JOIN ledger_accounts a ON a.id = l.account_id JOIN ledger_entries e ON e.id = l.entry_id "
                    + "WHERE a.category = 'MERCHANT_PAYABLE' AND a.book = 'LIVE' AND a.currency = ? AND e.effective_date >= ? AND e.effective_date < ? "
                    + "AND (e.template_code IS NULL OR e.template_code NOT LIKE 'STL_PAYOUT%') GROUP BY e.effective_date", ccy, first, today);
            Forecast f = forecast(series(rows, first, HISTORY_DAYS), today.minusDays(1), HORIZON);
            Long owed = jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount_minor ELSE -l.amount_minor END), 0) FROM ledger_lines l "
                    + "JOIN ledger_accounts a ON a.id = l.account_id WHERE a.category = 'MERCHANT_PAYABLE' AND a.book = 'LIVE' AND a.currency = ?", Long.class, ccy);
            long next7 = Math.round(java.util.Arrays.stream(f.expected()).sum()), next7p90 = Math.round(java.util.Arrays.stream(f.p90()).sum());
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("currency", ccy);
            v.put("owedNowMinor", owed == null ? 0 : owed);
            v.put("next7DaysMinor", next7);
            v.put("next7DaysP90Minor", next7p90);
            v.put("fundingNeededMinor", (owed == null ? 0 : owed) + next7p90);
            v.put("enoughHistory", f.enoughHistory());
            v.put("days", days(today, f, "expectedMinor", "p90Minor"));
            out.add(v);
        }
        return out;
    }

    /** One agent's float forecast per currency (current float from the caller). */
    public List<Map<String, Object>> agent(long agentId, Map<String, Long> floatNow) {
        LocalDate today = LocalDate.now(), first = today.minusDays(HISTORY_DAYS);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT DATE(created_at) AS d, currency, SUM(CASE WHEN kind = 'CASH_IN' THEN amount_minor ELSE -amount_minor END) AS v "
                + "FROM agent_transactions WHERE agent_id = ? AND created_at >= ? AND created_at < ? GROUP BY DATE(created_at), currency", agentId, first, today);
        Map<String, List<Map<String, Object>>> byCcy = new HashMap<>();
        for (Map<String, Object> r : rows) byCcy.computeIfAbsent(String.valueOf(r.get("currency")), k -> new ArrayList<>()).add(r);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : floatNow.entrySet()) {
            Forecast f = forecast(series(byCcy.getOrDefault(e.getKey(), List.of()), first, HISTORY_DAYS), today.minusDays(1), HORIZON);
            long have = e.getValue() == null ? 0 : e.getValue();
            double cum = 0, cum90 = 0;
            Integer runsOutIn = null;
            for (int h = 0; h < HORIZON; h++) {
                cum += Math.max(0, f.expected()[h]);
                cum90 += Math.max(0, f.p90()[h]);
                if (runsOutIn == null && cum > have) runsOutIn = h + 1;
            }
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("currency", e.getKey());
            v.put("floatNowMinor", have);
            v.put("useNext7DaysMinor", Math.round(cum));
            v.put("useNext7DaysP90Minor", Math.round(cum90));
            v.put("runsOutInDays", runsOutIn);
            v.put("topUpMinor", Math.max(0, Math.round(cum90) - have));
            v.put("enoughHistory", f.enoughHistory());
            v.put("days", days(today, f, "netUseMinor", "p90Minor"));
            out.add(v);
        }
        return out;
    }

}
