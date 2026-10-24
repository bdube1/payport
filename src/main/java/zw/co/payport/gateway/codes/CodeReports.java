package zw.co.payport.gateway.codes;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.platform.PartyCodes;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;

/** Payments (merchants) and cash-in/cash-out (agents) per six-digit code, for the admin, merchant and agent portals. */
@Service
public class CodeReports {

    private final JdbcTemplate jdbc;
    private final PartyCodes codes;

    public CodeReports(JdbcTemplate jdbc, PartyCodes codes) {
        this.jdbc = jdbc;
        this.codes = codes;
    }

    static LocalDate date(String s, LocalDate fallback) {
        if (s == null || s.isBlank()) return fallback;
        try { return LocalDate.parse(s.trim()); } catch (Exception e) { throw new IllegalArgumentException("Enter dates like 2026-10-24"); }
    }

    /**
     * The report for one party (and one of its codes, or all of them). Payments made before codes existed, or not
     * made by code (API, payment links), count under the main code.
     */
    public Map<String, Object> report(String partyType, long partyId, String code, String from, String to) {
        String type = partyType.trim().toUpperCase(Locale.ROOT);
        LocalDate t = date(to, LocalDate.now());
        LocalDate f = date(from, t.minusDays(30));
        if (f.isAfter(t)) throw new IllegalArgumentException("The start date is after the end date");
        String main = codes.codeOf(type, partyId).orElse("");
        String only = code == null || code.isBlank() ? null : code.trim();
        if (only != null && !codes.owns(type, partyId, only)) throw new IllegalArgumentException("No such code");
        List<Object> args = new ArrayList<>();
        String sql;
        if (PartyCodes.MERCHANT.equals(type)) {
            sql = "SELECT cs.created_at, COALESCE(cs.party_code, ?) AS code, cs.reference, cs.transaction_reference, cs.status, cs.channel, cs.customer_phone, "
                    + "cs.currency, cs.amount_cents AS amount_minor, COALESCE(cs.commission_amount_cents, 0) AS fee_minor, "
                    + "COALESCE(cs.net_amount_cents, cs.amount_cents) AS net_minor FROM checkout_sessions cs "
                    + "WHERE cs.merchant_id = ? AND (cs.livemode IS NULL OR cs.livemode = TRUE) AND cs.created_at >= ? AND cs.created_at < ?";
        } else {
            sql = "SELECT t.created_at, COALESCE(t.party_code, ?) AS code, t.op_ref AS reference, t.kind, w.wallet_ref, w.phone AS customer_phone, "
                    + "t.currency, t.amount_minor, t.commission_minor FROM agent_transactions t JOIN wallets w ON w.id = t.wallet_id "
                    + "WHERE t.agent_id = ? AND t.created_at >= ? AND t.created_at < ?";
        }
        args.add(main); args.add(partyId); args.add(Timestamp.valueOf(f.atStartOfDay())); args.add(Timestamp.valueOf(t.plusDays(1).atStartOfDay()));
        String col = PartyCodes.MERCHANT.equals(type) ? "cs.party_code" : "t.party_code";
        if (only != null) { sql += " AND COALESCE(" + col + ", ?) = ?"; args.add(main); args.add(only); }
        sql += " ORDER BY created_at DESC LIMIT 20000";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args.toArray());

        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        Map<String, String> labels = new HashMap<>();
        for (Map<String, Object> c : codes.codesOf(type, partyId)) labels.put((String) c.get("code"), (String) c.get("label"));
        for (Map<String, Object> r : rows) {
            boolean counted = PartyCodes.AGENT.equals(type) || "PAID".equals(r.get("status"));
            if (!counted) continue;
            String key = r.get("code") + "|" + r.get("currency") + (r.get("kind") != null ? "|" + r.get("kind") : "");
            Map<String, Object> s = byCode.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", r.get("code"));
                m.put("label", labels.getOrDefault((String) r.get("code"), ""));
                if (r.get("kind") != null) m.put("kind", r.get("kind"));
                m.put("currency", r.get("currency"));
                m.put("count", 0L);
                m.put("amount_minor", 0L);
                m.put(PartyCodes.MERCHANT.equals(type) ? "fee_minor" : "commission_minor", 0L);
                return m;
            });
            s.put("count", (Long) s.get("count") + 1);
            s.put("amount_minor", (Long) s.get("amount_minor") + ((Number) r.get("amount_minor")).longValue());
            String feeKey = PartyCodes.MERCHANT.equals(type) ? "fee_minor" : "commission_minor";
            s.put(feeKey, (Long) s.get(feeKey) + ((Number) r.get(feeKey)).longValue());
        }
        for (Map<String, Object> r : rows) r.put("label", labels.getOrDefault((String) r.get("code"), ""));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("from", f.toString());
        out.put("to", t.toString());
        out.put("codes", codes.codesOf(type, partyId));
        out.put("summary", new ArrayList<>(byCode.values()));
        out.put("rows", rows);
        return out;
    }

    /** The party behind a code (admin report by code alone). */
    public long partyOf(String type, String code) {
        Map<String, Object> r = codes.row(code).orElseThrow(() -> new IllegalArgumentException("No such code"));
        if (!type.equalsIgnoreCase((String) r.get("party_type"))) throw new IllegalArgumentException("That is not " + (PartyCodes.AGENT.equalsIgnoreCase(type) ? "an agent" : "a merchant") + " code");
        return ((Number) r.get("party_id")).longValue();
    }
}
