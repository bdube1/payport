package zw.co.payport.gateway.wallets;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.Web;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wallet statements and the wallet balances report (owner, 2026-10-25). Both are read from the ledger, which is
 * where a wallet's balance lives: a statement is the wallet account's ledger lines in order with a running balance
 * that starts from the balance before the period, and its closing balance equals the ledger balance when the
 * period runs to today. Descriptions come from the wallet operation each entry belongs to.
 */
@RestController
public class WalletStatements {

    private static final Map<String, String> KIND = Map.of(
            "TOPUP", "Top-up", "PAYMENT", "Payment", "P2P_OUT", "Money sent", "P2P_IN", "Money received", "CASH_IN", "Cash in at agent",
            "CASH_OUT", "Cash out at agent", "FX_OUT", "Converted out", "FX_IN", "Converted in", "REFUND", "Refund");

    private final JdbcTemplate jdbc;
    private final WalletService wallets;

    public WalletStatements(JdbcTemplate jdbc, WalletService wallets) {
        this.jdbc = jdbc;
        this.wallets = wallets;
    }

    private record Account(long id, int sign) {}

    /** The wallet's ledger account and the sign that turns a credit-minus-debit total into its balance. */
    private Account account(WalletService.Wallet w) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT a.id, b.balance, b.debit_total, b.credit_total FROM ledger_accounts a "
                + "LEFT JOIN ledger_balances b ON b.account_id = a.id WHERE a.code = ?", w.account().code());
        if (rows.isEmpty()) return null;
        Map<String, Object> r = rows.get(0);
        long bal = num(r.get("balance")), dr = num(r.get("debit_total")), cr = num(r.get("credit_total"));
        int sign = (bal == 0 && dr == cr) || bal == cr - dr ? 1 : -1;
        return new Account(num(r.get("id")), sign);
    }

    public Map<String, Object> statement(String ref, LocalDate from, LocalDate to) {
        WalletService.Wallet w = wallets.byRef(ref).orElseThrow(() -> new IllegalArgumentException("No such wallet"));
        if (from != null && to != null && to.isBefore(from)) throw new IllegalArgumentException("The end date is before the start date");
        LocalDateTime start = from == null ? LocalDateTime.of(1970, 1, 1, 0, 0) : from.atStartOfDay();
        LocalDateTime end = to == null ? LocalDateTime.now().plusYears(10) : to.plusDays(1).atStartOfDay();

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> holder = jdbc.queryForMap("SELECT w.wallet_ref, w.currency, w.status, w.tier, w.phone, w.created_at, u.full_name, u.id_number "
                + "FROM wallets w LEFT JOIN users u ON u.id = w.user_id WHERE w.id = ?", w.id());
        out.put("wallet", holder);
        out.put("from", from);
        out.put("to", to);
        out.put("generatedAt", LocalDateTime.now());

        Account a = account(w);
        long opening = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        long debits = 0, credits = 0;
        if (a != null) {
            opening = a.sign() * num(jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount_minor ELSE -l.amount_minor END), 0) "
                    + "FROM ledger_lines l JOIN ledger_entries e ON e.id = l.entry_id WHERE l.account_id = ? AND e.posted_at < ?",
                    Long.class, a.id(), Timestamp.valueOf(start)));
            long running = opening;
            for (Map<String, Object> l : jdbc.queryForList("SELECT e.posted_at, e.entry_ref, e.description, l.direction, l.amount_minor, l.memo, "
                    + "o.kind, o.counterparty, o.reference, o.op_ref FROM ledger_lines l JOIN ledger_entries e ON e.id = l.entry_id "
                    + "LEFT JOIN wallet_operations o ON o.entry_ref = e.entry_ref AND o.wallet_id = ? "
                    + "WHERE l.account_id = ? AND e.posted_at >= ? AND e.posted_at < ? ORDER BY e.id, l.line_no",
                    w.id(), a.id(), Timestamp.valueOf(start), Timestamp.valueOf(end))) {
                long amount = num(l.get("amount_minor"));
                boolean credit = "CREDIT".equals(l.get("direction"));
                long signed = a.sign() * (credit ? amount : -amount);
                running += signed;
                if (signed >= 0) credits += signed; else debits += -signed;
                String kind = (String) l.get("kind");
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("date", l.get("posted_at"));
                r.put("type", kind == null ? (String) l.get("description") : KIND.getOrDefault(kind, kind));
                r.put("details", details(kind, (String) l.get("counterparty"), (String) l.get("reference"), (String) l.get("memo")));
                r.put("reference", l.get("reference") != null ? l.get("reference") : l.get("entry_ref"));
                r.put("debitMinor", signed < 0 ? -signed : null);
                r.put("creditMinor", signed >= 0 ? signed : null);
                r.put("balanceMinor", running);
                rows.add(r);
            }
        }
        out.put("currency", w.currency());
        out.put("openingBalanceMinor", opening);
        out.put("totalDebitsMinor", debits);
        out.put("totalCreditsMinor", credits);
        out.put("closingBalanceMinor", opening + credits - debits);
        long ledger = wallets.balance(w);
        out.put("currentBalanceMinor", ledger);
        // A statement that runs to today must close on the ledger balance; anything else is reported, never hidden.
        out.put("reconciled", to != null && to.isBefore(LocalDate.now()) || opening + credits - debits == ledger);
        out.put("rows", rows);
        return out;
    }

    private String details(String kind, String counterparty, String reference, String memo) {
        String who = kind == null ? null : wallets.toName(kind, counterparty, reference);
        if (memo != null && !memo.isBlank() && (who == null || !memo.equals(who))) return who == null ? memo : who + " · " + memo;
        return who;
    }

    /** Every wallet with its holder and current ledger balance, largest first. */
    public Map<String, Object> balances(String currency, String q) {
        StringBuilder sql = new StringBuilder("SELECT w.wallet_ref, w.currency, w.status, w.tier, w.phone, w.created_at, u.full_name, u.id_number, "
                + "COALESCE(b.balance, 0) AS balance_minor FROM wallets w LEFT JOIN users u ON u.id = w.user_id "
                + "LEFT JOIN ledger_accounts a ON a.entity_code = w.entity_code AND a.book = 'LIVE' AND a.category = 'CUSTOMER_WALLET' "
                + "AND a.owner_ref = w.wallet_ref AND a.currency = w.currency LEFT JOIN ledger_balances b ON b.account_id = a.id WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (currency != null && !currency.isBlank()) { sql.append(" AND w.currency = ?"); args.add(currency.trim().toUpperCase()); }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim() + "%";
            sql.append(" AND (w.wallet_ref LIKE ? OR w.phone LIKE ? OR u.full_name LIKE ? OR u.id_number LIKE ?)");
            args.add(like); args.add(like); args.add(like); args.add(like);
        }
        sql.append(" ORDER BY balance_minor DESC, w.wallet_ref LIMIT 5000");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        // ledger_balances.balance is already signed for the account's normal side, exactly as WalletService.balance reads it.
        Map<String, Map<String, Object>> totals = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> t = totals.computeIfAbsent((String) r.get("currency"), c -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("currency", c); m.put("wallets", 0L); m.put("balanceMinor", 0L);
                return m;
            });
            t.put("wallets", (Long) t.get("wallets") + 1);
            t.put("balanceMinor", (Long) t.get("balanceMinor") + num(r.get("balance_minor")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", LocalDateTime.now());
        out.put("totals", new ArrayList<>(totals.values()));
        out.put("rows", rows);
        return out;
    }

    private static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private static LocalDate date(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s.trim().substring(0, 10)); } catch (Exception e) { throw new IllegalArgumentException("Dates are YYYY-MM-DD"); }
    }

    // ─── ops console ─────────────────────────────────────────────────────────

    @GetMapping("/api/v1/admin/wallets/{ref}/statement-report")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> statementReport(@PathVariable String ref, @RequestParam(required = false) String from,
                                                                          @RequestParam(required = false) String to, HttpServletRequest req) {
        return Web.run(() -> statement(ref, date(from), date(to)), req);
    }

    @GetMapping("/api/v1/admin/wallets/balances")
    @PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> balancesReport(@RequestParam(required = false) String currency,
                                                                         @RequestParam(required = false) String q, HttpServletRequest req) {
        return Web.run(() -> balances(currency, q), req);
    }
}
