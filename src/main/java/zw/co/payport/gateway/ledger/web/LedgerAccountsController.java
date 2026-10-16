package zw.co.payport.gateway.ledger.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.Web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Read-only views of the ledger for the ops console: trial balance and accounts with balances. */
@RestController
@RequestMapping("/api/v1/admin/ledger")
@PreAuthorize("hasAnyRole('ADMIN','FINANCE','COMPLIANCE')")
public class LedgerAccountsController {

    private final JdbcTemplate jdbc;

    public LedgerAccountsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/trial-balance")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> trialBalance(HttpServletRequest req) {
        return Web.run(() -> jdbc.queryForList("SELECT a.entity_code, a.book, a.category, c.account_type, a.currency, COUNT(*) AS accounts, "
                + "SUM(b.debit_total) AS debit_total, SUM(b.credit_total) AS credit_total, SUM(b.balance) AS balance "
                + "FROM ledger_accounts a JOIN ledger_balances b ON b.account_id = a.id JOIN ledger_categories c ON c.category = a.category "
                + "GROUP BY a.entity_code, a.book, a.category, c.account_type, a.currency ORDER BY a.entity_code, a.book, c.account_type, a.category, a.currency"), req);
    }

    @GetMapping("/accounts")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> accounts(@RequestParam(required = false) String category,
                                                                           @RequestParam(required = false) String owner,
                                                                           @RequestParam(required = false) String currency, HttpServletRequest req) {
        return Web.run(() -> {
            StringBuilder sql = new StringBuilder("SELECT a.code, a.entity_code, a.book, a.category, a.owner_ref, a.currency, a.status, b.balance, b.debit_total, b.credit_total "
                    + "FROM ledger_accounts a JOIN ledger_balances b ON b.account_id = a.id WHERE 1=1");
            List<Object> args = new ArrayList<>();
            if (category != null && !category.isBlank()) { sql.append(" AND a.category = ?"); args.add(category.trim().toUpperCase()); }
            if (owner != null && !owner.isBlank()) { sql.append(" AND a.owner_ref LIKE ?"); args.add("%" + owner.trim() + "%"); }
            if (currency != null && !currency.isBlank()) { sql.append(" AND a.currency = ?"); args.add(currency.trim().toUpperCase()); }
            sql.append(" ORDER BY a.category, a.owner_ref LIMIT 1000");
            return jdbc.queryForList(sql.toString(), args.toArray());
        }, req);
    }

    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> categories(HttpServletRequest req) {
        return Web.run(() -> jdbc.queryForList("SELECT * FROM ledger_categories ORDER BY account_type, category"), req);
    }
}
