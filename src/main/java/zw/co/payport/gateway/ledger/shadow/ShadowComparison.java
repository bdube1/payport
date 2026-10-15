package zw.co.payport.gateway.ledger.shadow;

import zw.co.payport.gateway.ledger.templates.PostingTemplates;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Phase 1 exit evidence (E5): shadow ledger against legacy figures, per merchant and currency.
 *
 *   owed:          legacy unsettled net (CheckoutSessionService.balanceByCurrency rules, live sessions)
 *                  vs ledger LIVE MERCHANT_PAYABLE balance
 *   in settlement: legacy settlements not yet PAID (net)
 *                  vs ledger LIVE SETTLEMENT_PAYABLE balance
 *
 * Every row with a difference must be explained before the ledger can replace legacy figures.
 */
public class ShadowComparison {

    public record Row(long merchantId, String currency, long legacyOwed, long ledgerOwed, long legacyInSettlement, long ledgerInSettlement) {
        public long owedDifference() { return ledgerOwed - legacyOwed; }
        public long settlementDifference() { return ledgerInSettlement - legacyInSettlement; }
        public boolean matches() { return owedDifference() == 0 && settlementDifference() == 0; }
    }

    private final DataSource dataSource;
    private final String entity;

    public ShadowComparison(DataSource dataSource, String entity) {
        this.dataSource = dataSource;
        this.entity = entity;
    }

    public List<Row> compare() {
        Map<String, long[]> m = new TreeMap<>(); // key merchant|ccy -> [legacyOwed, ledgerOwed, legacyInSettl, ledgerInSettl]
        try (Connection c = dataSource.getConnection()) {
            // Legacy owed: same selection and arithmetic as balanceByCurrency (live sessions only since Phase 0.5).
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT cs.merchant_id, cs.currency, cs.amount_cents, cs.net_amount_cents, "
                            + "(SELECT COALESCE(SUM(r.amount_cents), 0) FROM refunds r WHERE r.checkout_session_id = cs.id AND r.status = 'COMPLETED') AS refunded "
                            + "FROM checkout_sessions cs WHERE cs.livemode = TRUE AND cs.settlement_id IS NULL "
                            + "AND cs.status IN ('PAID','PARTIALLY_REFUNDED','REFUNDED')");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long gross = rs.getLong("amount_cents");
                    long netRaw = rs.getLong("net_amount_cents");
                    Long net = rs.wasNull() ? null : netRaw;
                    long remaining = gross - rs.getLong("refunded");
                    if (remaining <= 0) continue;
                    long owed = PostingTemplates.legacyProRata(PostingTemplates.legacyNet(gross, net), gross, remaining);
                    m.computeIfAbsent(rs.getLong("merchant_id") + "|" + rs.getString("currency"), k -> new long[4])[0] += owed;
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT merchant_id, currency, SUM(net_amount_cents) FROM settlements WHERE status <> 'PAID' GROUP BY merchant_id, currency");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) m.computeIfAbsent(rs.getLong(1) + "|" + rs.getString(2), k -> new long[4])[2] += rs.getLong(3);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT a.category, a.owner_ref, a.currency, b.balance FROM ledger_accounts a JOIN ledger_balances b ON b.account_id = a.id "
                            + "WHERE a.entity_code = ? AND a.book = 'LIVE' AND a.category IN ('MERCHANT_PAYABLE','SETTLEMENT_PAYABLE')")) {
                ps.setString(1, entity);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int slot = "MERCHANT_PAYABLE".equals(rs.getString(1)) ? 1 : 3;
                        m.computeIfAbsent(rs.getString(2) + "|" + rs.getString(3), k -> new long[4])[slot] += rs.getLong(4);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("comparison failed: " + e.getMessage(), e);
        }
        List<Row> rows = new ArrayList<>();
        m.forEach((k, v) -> {
            String[] p = k.split("\\|");
            long merchant;
            try {
                merchant = Long.parseLong(p[0]);
            } catch (NumberFormatException e) {
                merchant = -1; // ledger owner that is not a merchant id: shown so it gets explained
            }
            rows.add(new Row(merchant, p[1], v[0], v[1], v[2], v[3]));
        });
        return rows;
    }
}
