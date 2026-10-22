package zw.co.payport.gateway.iso20022;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.ledger.AccountKey;
import zw.co.payport.gateway.ledger.Book;
import zw.co.payport.gateway.ledger.JournalRequest;
import zw.co.payport.gateway.ledger.LedgerCategory;
import zw.co.payport.gateway.ledger.LedgerClient;
import zw.co.payport.gateway.markets.MarketRegistry;
import zw.co.payport.gateway.risk.ComplianceAudit;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Payment rails settle what they collected into PayPort's bank account. A booked credit on a camt.053
 * statement that matches an ACTIVE rule (counterparty and/or remittance text contains the pattern,
 * same currency) is booked Dr BANK_CASH[market account] · Cr PROVIDER_RECEIVABLE[provider], idempotent
 * per statement entry, and traced as PROVIDER_SETTLEMENT. Rules are proposed by one FINANCE user and
 * approved by another (database CHECK). No rule matches → the credit stays UNIDENTIFIED for review.
 */
@Slf4j
@Service
public class ProviderSettlementService {

    private final JdbcTemplate jdbc;
    private final LedgerClient ledger;
    private final MarketRegistry markets;
    private final ComplianceAudit audit;

    public ProviderSettlementService(JdbcTemplate jdbc, LedgerClient ledger, MarketRegistry markets, ComplianceAudit audit) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.markets = markets;
        this.audit = audit;
    }

    public List<Map<String, Object>> rules() {
        return jdbc.queryForList("SELECT * FROM provider_settlement_rules ORDER BY status, provider_code, id DESC");
    }

    public Map<String, Object> propose(String provider, String currency, String counterparty, String remittance, String by) {
        if (provider == null || provider.isBlank()) throw new IllegalArgumentException("provider is required");
        String ccy = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        markets.requireForCurrency(ccy);
        String cp = blank(counterparty), rm = blank(remittance);
        if (cp == null && rm == null) throw new IllegalArgumentException("Give a counterparty or remittance pattern");
        jdbc.update("INSERT INTO provider_settlement_rules (provider_code, currency, counterparty_pattern, remittance_pattern, created_by, created_at) VALUES (?,?,?,?,?,?)",
                provider.trim().toUpperCase(Locale.ROOT), ccy, cp, rm, by, now());
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM provider_settlement_rules WHERE created_by = ?", Long.class, by);
        audit.record(by, "PROVIDER_SETTLEMENT_RULE_PROPOSED", "provider_settlement_rule:" + id, provider + " " + ccy + " cp=" + cp + " rm=" + rm);
        return jdbc.queryForMap("SELECT * FROM provider_settlement_rules WHERE id = ?", id);
    }

    public Map<String, Object> decide(long id, boolean approve, String by) {
        Map<String, Object> r = jdbc.queryForMap("SELECT * FROM provider_settlement_rules WHERE id = ?", id);
        if (!"PENDING".equals(r.get("status"))) throw new IllegalStateException("Rule is " + r.get("status"));
        if (by.equals(r.get("created_by"))) throw new IllegalStateException("The proposer cannot approve the rule");
        jdbc.update("UPDATE provider_settlement_rules SET status = ?, approved_by = ?, approved_at = ? WHERE id = ?", approve ? "ACTIVE" : "REJECTED", by, now(), id);
        audit.record(by, approve ? "PROVIDER_SETTLEMENT_RULE_APPROVED" : "PROVIDER_SETTLEMENT_RULE_REJECTED", "provider_settlement_rule:" + id, String.valueOf(r.get("provider_code")));
        return jdbc.queryForMap("SELECT * FROM provider_settlement_rules WHERE id = ?", id);
    }

    public Map<String, Object> retire(long id, String by) {
        int n = jdbc.update("UPDATE provider_settlement_rules SET status = 'RETIRED' WHERE id = ? AND status = 'ACTIVE'", id);
        if (n == 0) throw new IllegalStateException("Only an active rule can be retired");
        audit.record(by, "PROVIDER_SETTLEMENT_RULE_RETIRED", "provider_settlement_rule:" + id, "");
        return jdbc.queryForMap("SELECT * FROM provider_settlement_rules WHERE id = ?", id);
    }

    /** The first ACTIVE rule this booked credit matches, if any. */
    public Optional<Map<String, Object>> match(String currency, String counterparty, String remittance) {
        String cp = counterparty == null ? "" : counterparty.toLowerCase(Locale.ROOT);
        String rm = remittance == null ? "" : remittance.toLowerCase(Locale.ROOT);
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM provider_settlement_rules WHERE status = 'ACTIVE' AND currency = ? ORDER BY id", currency)) {
            String pc = (String) r.get("counterparty_pattern"), pr = (String) r.get("remittance_pattern");
            boolean ok = (pc == null || cp.contains(pc.toLowerCase(Locale.ROOT))) && (pr == null || rm.contains(pr.toLowerCase(Locale.ROOT)));
            if (ok) return Optional.of(r);
        }
        return Optional.empty();
    }

    /** Books the settlement; returns the ledger entry reference. */
    public LedgerClient.PostResult book(Map<String, Object> rule, String entryKey, long amount, String currency, java.time.LocalDate on, String by) {
        MarketRegistry.Market m = markets.requireForCurrency(currency);
        MarketRegistry.BankAccount bank = markets.bankAccount(currency);
        if (bank == null || bank.ledgerOwner() == null) throw new IllegalStateException("No PayPort bank account is configured for " + currency);
        String provider = (String) rule.get("provider_code");
        JournalRequest j = JournalRequest.builder("PROVIDER_SETTLEMENT:" + entryKey).entity(m.legalEntity()).book(Book.LIVE).template("PROVIDER_SETTLEMENT", 1)
                .effectiveDate(on == null ? java.time.LocalDate.now() : on).description(provider + " settlement " + entryKey)
                .source("bank_statement_entry", entryKey).postedBy(by)
                .debit(AccountKey.of(m.legalEntity(), Book.LIVE, LedgerCategory.BANK_CASH, bank.ledgerOwner(), currency), amount, "received from " + provider)
                .credit(AccountKey.of(m.legalEntity(), Book.LIVE, LedgerCategory.PROVIDER_RECEIVABLE, provider, currency), amount, "settled by " + provider).build();
        return ledger.post(j);
    }

    /**
     * Comparison view (no automation): what each rail still owes PayPort per currency, from the ledger.
     * Positive = collected by the rail and not yet settled to the bank (outstanding or a partial
     * settlement); negative = the bank received more than the ledger shows as collected (over-settled
     * or an unrecognised credit), which needs investigation.
     */
    public java.util.List<Map<String, Object>> positions() {
        java.util.List<Map<String, Object>> rows = jdbc.queryForList("SELECT a.entity_code, a.owner_ref AS provider_code, a.currency, b.balance AS outstanding_minor, "
                + "b.debit_total AS collected_minor, b.credit_total AS settled_minor FROM ledger_accounts a JOIN ledger_balances b ON b.account_id = a.id "
                + "WHERE a.category = 'PROVIDER_RECEIVABLE' AND a.book = 'LIVE' ORDER BY a.entity_code, a.owner_ref, a.currency");
        for (Map<String, Object> r : rows) {
            long bal = ((Number) r.get("outstanding_minor")).longValue();
            r.put("position", bal > 0 ? "OUTSTANDING" : bal < 0 ? "OVER_SETTLED" : "SETTLED");
        }
        return rows;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Timestamp now() {
        return Timestamp.valueOf(LocalDateTime.now());
    }
}
