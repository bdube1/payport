package zw.co.payport.gateway.merchant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a merchant sees about its money, per currency and mode (live/test), without staff help.
 *
 *   pending       paid, still inside the settlement hold (payport.settlement.hold-hours)
 *   available     paid, past the hold, not yet in a payout
 *   inSettlement  in a payout that has been drafted/approved/instructed but not confirmed by the bank
 *   paidOut       confirmed paid by the bank (new settlement) or marked paid by the legacy settlement
 *
 * All amounts are net of Payport's fee and completed refunds, in minor units. Drafts made in
 * PARALLEL (comparison) mode are not real payouts and are not shown to merchants.
 */
@Service
public class MerchantBalanceService {

    private final JdbcTemplate jdbc;
    private final long holdHours;

    public MerchantBalanceService(JdbcTemplate jdbc, @Value("${payport.settlement.hold-hours:24}") long holdHours) {
        this.jdbc = jdbc;
        this.holdHours = holdHours;
    }

    /** Net of fee and refunds, refund share prorated (same rule as settlement). */
    private static final String NET = "(CASE WHEN cs.amount_cents <= 0 THEN 0 ELSE ROUND(COALESCE(cs.net_amount_cents, cs.amount_cents - "
            + "COALESCE(cs.commission_amount_cents, 0)) * (cs.amount_cents - LEAST(cs.amount_cents, COALESCE((SELECT SUM(r.amount_cents) FROM refunds r "
            + "WHERE r.checkout_session_id = cs.id AND r.status = 'COMPLETED'), 0))) / cs.amount_cents) END)";

    private static final String LIVE_ITEM_STATUS = "(SELECT i.status FROM stl_session_claims c JOIN stl_items i ON i.id = c.item_id "
            + "JOIN stl_batches b ON b.id = i.batch_id WHERE c.session_id = cs.id AND b.mode = 'LIVE' LIMIT 1)";

    public Map<String, Object> balances(long merchantId, boolean livemode) {
        LocalDateTime holdCutoff = LocalDateTime.now().minusHours(holdHours);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT cs.currency, "
                + "SUM(CASE WHEN cs.settlement_id IS NULL AND " + LIVE_ITEM_STATUS + " IS NULL AND cs.created_at >= ? THEN " + NET + " ELSE 0 END) AS pending, "
                + "SUM(CASE WHEN cs.settlement_id IS NULL AND " + LIVE_ITEM_STATUS + " IS NULL AND cs.created_at < ? THEN " + NET + " ELSE 0 END) AS available, "
                + "SUM(CASE WHEN cs.settlement_id IS NULL AND " + LIVE_ITEM_STATUS + " IN ('PENDING','INSTRUCTED') THEN " + NET + " ELSE 0 END) AS in_settlement, "
                + "SUM(CASE WHEN cs.settlement_id IS NOT NULL OR " + LIVE_ITEM_STATUS + " = 'PAID' THEN " + NET + " ELSE 0 END) AS paid_out, "
                + "SUM(cs.amount_cents) AS gross, SUM(COALESCE(cs.commission_amount_cents, 0)) AS fees, COUNT(*) AS payments "
                + "FROM checkout_sessions cs WHERE cs.merchant_id = ? AND COALESCE(cs.livemode, FALSE) = ? "
                + "AND cs.status IN ('PAID','PARTIALLY_REFUNDED','REFUNDED') GROUP BY cs.currency ORDER BY cs.currency",
                Timestamp.valueOf(holdCutoff), Timestamp.valueOf(holdCutoff), merchantId, livemode);
        List<Map<String, Object>> currencies = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("currency", r.get("currency"));
            c.put("pendingMinor", num(r.get("pending")));
            c.put("availableMinor", num(r.get("available")));
            c.put("inSettlementMinor", num(r.get("in_settlement")));
            c.put("paidOutMinor", num(r.get("paid_out")));
            c.put("grossMinor", num(r.get("gross")));
            c.put("feesMinor", num(r.get("fees")));
            c.put("payments", num(r.get("payments")));
            currencies.add(c);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("livemode", livemode);
        out.put("holdHours", holdHours);
        out.put("balances", currencies);
        return out;
    }

    /** Statement lines (payments, refunds, payouts) for a date range, oldest first, with a running net balance. */
    public Map<String, Object> statement(long merchantId, String currency, LocalDate from, LocalDate to, boolean livemode) {
        LocalDateTime start = from.atStartOfDay(), end = to.plusDays(1).atStartOfDay();
        String ccy = currency.toUpperCase();
        List<Map<String, Object>> lines = new ArrayList<>();
        jdbc.query("SELECT cs.id, cs.reference, cs.transaction_reference, cs.created_at, cs.amount_cents, COALESCE(cs.commission_amount_cents, 0) AS fee "
                        + "FROM checkout_sessions cs WHERE cs.merchant_id = ? AND cs.currency = ? AND COALESCE(cs.livemode, FALSE) = ? "
                        + "AND cs.status IN ('PAID','PARTIALLY_REFUNDED','REFUNDED') AND cs.created_at >= ? AND cs.created_at < ?",
                rs -> {
                    lines.add(line(rs.getTimestamp("created_at").toLocalDateTime(), "PAYMENT", rs.getString("reference"),
                            rs.getLong("amount_cents"), rs.getLong("fee"), rs.getLong("amount_cents") - rs.getLong("fee")));
                }, merchantId, ccy, livemode, Timestamp.valueOf(start), Timestamp.valueOf(end));
        jdbc.query("SELECT r.id, r.amount_cents, r.created_at, cs.reference FROM refunds r JOIN checkout_sessions cs ON cs.id = r.checkout_session_id "
                        + "WHERE cs.merchant_id = ? AND cs.currency = ? AND COALESCE(cs.livemode, FALSE) = ? AND r.status = 'COMPLETED' "
                        + "AND r.created_at >= ? AND r.created_at < ?",
                rs -> {
                    lines.add(line(rs.getTimestamp("created_at").toLocalDateTime(), "REFUND", rs.getString("reference"),
                            -rs.getLong("amount_cents"), 0, -rs.getLong("amount_cents")));
                }, merchantId, ccy, livemode, Timestamp.valueOf(start), Timestamp.valueOf(end));
        if (livemode) {
            for (Map<String, Object> p : payouts(merchantId)) {
                if (!"PAID".equals(p.get("status")) || !ccy.equals(p.get("currency")) || p.get("paidAt") == null) continue;
                LocalDateTime at = (LocalDateTime) p.get("paidAt");
                if (at.isBefore(start) || !at.isBefore(end)) continue;
                long amt = ((Number) p.get("amountMinor")).longValue();
                lines.add(line(at, "PAYOUT", (String) p.get("payoutId"), -amt, 0, -amt));
            }
        }
        lines.sort(Comparator.comparing(l -> (LocalDateTime) l.get("at")));
        long running = 0;
        for (Map<String, Object> l : lines) {
            running += ((Number) l.get("netMinor")).longValue();
            l.put("runningNetMinor", running);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currency", ccy);
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("livemode", livemode);
        out.put("lines", lines);
        out.put("netChangeMinor", running);
        return out;
    }

    /** Payouts: the new settlement's live payouts and the legacy settlement records. */
    public List<Map<String, Object>> payouts(long merchantId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT i.instruction_ref, i.currency, i.net_minor, i.session_count, i.status, i.bank_reference, "
                + "i.paid_at, i.return_reason, i.updated_at, b.batch_ref FROM stl_items i JOIN stl_batches b ON b.id = i.batch_id "
                + "WHERE i.merchant_id = ? AND b.mode = 'LIVE' AND b.status <> 'CANCELLED' ORDER BY i.id DESC", merchantId)) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("payoutId", r.get("instruction_ref"));
            p.put("source", "SETTLEMENT");
            p.put("currency", r.get("currency"));
            p.put("amountMinor", num(r.get("net_minor")));
            p.put("payments", num(r.get("session_count")));
            p.put("status", "PENDING".equals(r.get("status")) ? "SCHEDULED" : r.get("status"));
            p.put("bankReference", r.get("bank_reference"));
            p.put("paidAt", ts(r.get("paid_at")));
            p.put("returnReason", r.get("return_reason"));
            out.add(p);
        }
        for (Map<String, Object> r : jdbc.queryForList("SELECT id, currency, net_amount_cents, session_count, status, payout_reference, paid_at "
                + "FROM settlements WHERE merchant_id = ? ORDER BY id DESC", merchantId)) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("payoutId", "LEGACY-" + r.get("id"));
            p.put("source", "LEGACY");
            p.put("currency", r.get("currency"));
            p.put("amountMinor", num(r.get("net_amount_cents")));
            p.put("payments", num(r.get("session_count")));
            p.put("status", r.get("status"));
            p.put("bankReference", r.get("payout_reference"));
            p.put("paidAt", ts(r.get("paid_at")));
            out.add(p);
        }
        return out;
    }

    private static Map<String, Object> line(LocalDateTime at, String type, String ref, long gross, long fee, long net) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", at);
        m.put("type", type);
        m.put("reference", ref);
        m.put("grossMinor", gross);
        m.put("feeMinor", fee);
        m.put("netMinor", net);
        return m;
    }

    private static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private static LocalDateTime ts(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDateTime l) return l;
        if (o instanceof Timestamp t) return t.toLocalDateTime();
        return null;
    }
}
