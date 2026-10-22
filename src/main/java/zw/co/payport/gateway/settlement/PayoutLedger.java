package zw.co.payport.gateway.settlement;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import zw.co.payport.gateway.ledger.AccountKey;
import zw.co.payport.gateway.ledger.Book;
import zw.co.payport.gateway.ledger.JournalRequest;
import zw.co.payport.gateway.ledger.LedgerCategory;
import zw.co.payport.gateway.ledger.LedgerClient;
import zw.co.payport.gateway.markets.MarketRegistry;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Posts settlement payouts to the ledger of the market's legal entity:
 *
 *   STL_PAYOUT_PAID     Dr MERCHANT_PAYABLE[merchant] · Cr BANK_CASH[market bank account]
 *   STL_PAYOUT_RETURNED Dr BANK_CASH[market bank account] · Cr MERCHANT_PAYABLE[merchant]
 *
 * Posted after the business transaction commits (the ledger never rolls back a bank result,
 * ADR-0009) with an idempotency key per item and event, so a retry or the statement trace
 * (BankStatementService) can call ensurePosted safely. A posting the ledger refuses (for
 * example MERCHANT_PAYABLE would go negative because the collections are not in the ledger
 * yet) is recorded as FAILED and shows on the statement trace as LEDGER_PENDING.
 */
@Slf4j
@Component
public class PayoutLedger {

    public static final String PAID = "STL_PAYOUT_PAID";
    public static final String RETURNED = "STL_PAYOUT_RETURNED";

    private final JdbcTemplate jdbc;
    private final LedgerClient ledger;
    private final MarketRegistry markets;

    public PayoutLedger(JdbcTemplate jdbc, LedgerClient ledger, MarketRegistry markets) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.markets = markets;
    }

    void afterCommit(long itemId, String event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    ensurePosted(itemId, event);
                }
            });
        } else {
            ensurePosted(itemId, event);
        }
    }

    /** Posts (or finds) the journal for this item and event; returns the entry reference, empty if the ledger refused it. */
    public Optional<String> ensurePosted(long itemId, String event) {
        Map<String, Object> i = jdbc.queryForMap("SELECT id, merchant_id, currency, net_minor, instruction_ref, paid_at, updated_at FROM stl_items WHERE id = ?", itemId);
        String ccy = (String) i.get("currency");
        MarketRegistry.Market market = markets.requireForCurrency(ccy);
        String entity = market.legalEntity();
        String bank = markets.bankAccount(ccy).ledgerOwner();
        long net = ((Number) i.get("net_minor")).longValue();
        LocalDate on = date(i.get("PAID".equals(event) ? "paid_at" : "updated_at"));
        AccountKey payable = AccountKey.of(entity, Book.LIVE, LedgerCategory.MERCHANT_PAYABLE, String.valueOf(i.get("merchant_id")), ccy);
        AccountKey cash = AccountKey.of(entity, Book.LIVE, LedgerCategory.BANK_CASH, bank, ccy);
        String template = "PAID".equals(event) ? PAID : RETURNED;
        JournalRequest.Builder b = JournalRequest.builder(template + ":stl_item:" + itemId)
                .entity(entity).book(Book.LIVE).template(template, 1).effectiveDate(on)
                .description("Payout " + i.get("instruction_ref") + ("PAID".equals(event) ? " paid" : " returned by the bank"))
                .source("stl_item", i.get("instruction_ref")).postedBy("SYSTEM:settlement");
        if ("PAID".equals(event)) b.debit(payable, net, "paid out").credit(cash, net, "bank payout");
        else b.debit(cash, net, "returned to bank").credit(payable, net, "owed again");
        LocalDateTime now = LocalDateTime.now();
        try {
            LedgerClient.PostResult r = ledger.post(b.build());
            record(itemId, event, r.entryRef(), "POSTED", null, now);
            return Optional.of(r.entryRef());
        } catch (RuntimeException e) {
            log.warn("[SETTLEMENT] ledger posting {} for payout {} refused: {}", template, i.get("instruction_ref"), e.getMessage());
            record(itemId, event, null, "FAILED", e.getMessage(), now);
            return Optional.empty();
        }
    }

    public List<Map<String, Object>> forItem(long itemId) {
        return jdbc.queryForList("SELECT * FROM stl_item_ledger WHERE stl_item_id = ? ORDER BY event", itemId);
    }

    private void record(long itemId, String event, String ref, String status, String error, LocalDateTime now) {
        jdbc.update("INSERT INTO stl_item_ledger (stl_item_id, event, entry_ref, status, error, updated_at) VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE error = IF(VALUES(status) = 'POSTED', NULL, VALUES(error)), entry_ref = COALESCE(VALUES(entry_ref), entry_ref), "
                        + "status = IF(status = 'POSTED', status, VALUES(status)), updated_at = VALUES(updated_at)",
                itemId, event, ref, status, error == null ? null : error.substring(0, Math.min(500, error.length())), Timestamp.valueOf(now));
    }

    private static LocalDate date(Object o) {
        if (o instanceof Timestamp t) return t.toLocalDateTime().toLocalDate();
        if (o instanceof LocalDateTime l) return l.toLocalDate();
        return LocalDate.now();
    }
}
