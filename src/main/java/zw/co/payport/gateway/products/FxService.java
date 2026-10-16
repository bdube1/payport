package zw.co.payport.gateway.products;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.markets.MarketRegistry;
import zw.co.payport.gateway.risk.ComplianceAudit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 8 FX (harvested design from psp-foundation's FxRateService): reference rates with
 * maker-checker (database CHECK), and customer quotes. Conversions are executed between a customer's
 * own wallets (WalletService.convert, one balanced journal through FX_CLEARING); quotes and
 * conversions are refused unless the FX_CONVERSION line is switched on.
 *
 * Quote: amount × rate, less the spread, rounded DOWN to the target currency's minor unit (the
 * customer never gets more than the rate allows). Quotes are valid for 60 seconds.
 */
@Service
public class FxService {

    private final JdbcTemplate jdbc;
    private final MarketRegistry markets;
    private final ServiceLineService lines;
    private final ComplianceAudit audit;

    public FxService(JdbcTemplate jdbc, MarketRegistry markets, ServiceLineService lines, ComplianceAudit audit) {
        this.jdbc = jdbc;
        this.markets = markets;
        this.lines = lines;
        this.audit = audit;
    }

    public List<Map<String, Object>> rates() {
        return jdbc.queryForList("SELECT * FROM fx_rates ORDER BY id DESC LIMIT 200");
    }

    public Map<String, Object> propose(String base, String quote, BigDecimal rate, int spreadBps, String source, String by) {
        String b = base.toUpperCase(), q = quote.toUpperCase();
        if (b.equals(q)) throw new IllegalArgumentException("base and quote currencies must differ");
        markets.requireForCurrency(b);
        markets.requireForCurrency(q);
        if (rate == null || rate.signum() <= 0) throw new IllegalArgumentException("rate must be positive");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source is required (e.g. RBZ interbank rate of the day)");
        jdbc.update("INSERT INTO fx_rates (base_ccy, quote_ccy, rate, spread_bps, source, status, created_by, created_at) VALUES (?,?,?,?,?, 'PENDING', ?, ?)",
                b, q, rate, spreadBps, source, by, now());
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM fx_rates WHERE created_by = ?", Long.class, by);
        audit.record(by, "FX_RATE_PROPOSED", "fx:" + b + q, rate + " spread " + spreadBps + "bps, " + source);
        return Map.of("id", id, "status", "PENDING");
    }

    @Transactional
    public Map<String, Object> approve(long id, boolean approve, String by) {
        Map<String, Object> r = jdbc.queryForMap("SELECT * FROM fx_rates WHERE id = ? FOR UPDATE", id);
        if (!"PENDING".equals(r.get("status"))) throw new IllegalStateException("rate is " + r.get("status"));
        if (by.equals(r.get("created_by"))) throw new IllegalStateException("the proposer cannot approve the rate");
        if (approve) {
            jdbc.update("UPDATE fx_rates SET status = 'RETIRED' WHERE base_ccy = ? AND quote_ccy = ? AND status = 'ACTIVE'", r.get("base_ccy"), r.get("quote_ccy"));
            jdbc.update("UPDATE fx_rates SET status = 'ACTIVE', approved_by = ?, approved_at = ? WHERE id = ?", by, now(), id);
        } else {
            jdbc.update("UPDATE fx_rates SET status = 'REJECTED', approved_by = ?, approved_at = ? WHERE id = ?", by, now(), id);
        }
        audit.record(by, approve ? "FX_RATE_APPROVED" : "FX_RATE_REJECTED", "fx:" + r.get("base_ccy") + r.get("quote_ccy"), "rate id " + id);
        return Map.of("id", id, "status", approve ? "ACTIVE" : "REJECTED");
    }

    public Map<String, Object> quote(String from, String to, long amountMinor) {
        lines.require("FX_CONVERSION", "Currency conversion");
        if (amountMinor <= 0) throw new IllegalArgumentException("amount must be positive");
        String f = from.toUpperCase(), t = to.toUpperCase();
        List<Map<String, Object>> direct = jdbc.queryForList("SELECT * FROM fx_rates WHERE base_ccy = ? AND quote_ccy = ? AND status = 'ACTIVE'", f, t);
        // Exact arithmetic, one rounding at the end (down): amount × (1 − spread) × rate, or ÷ rate for the inverse pair.
        java.math.MathContext mc = java.math.MathContext.DECIMAL128;
        BigDecimal keep;
        BigDecimal converted;
        int spread;
        Object rateId;
        if (!direct.isEmpty()) {
            BigDecimal rate = (BigDecimal) direct.get(0).get("rate");
            spread = ((Number) direct.get(0).get("spread_bps")).intValue();
            rateId = direct.get(0).get("id");
            keep = BigDecimal.valueOf(10_000 - spread).divide(BigDecimal.valueOf(10_000), mc);
            converted = BigDecimal.valueOf(amountMinor).multiply(rate, mc).multiply(keep, mc);
        } else {
            List<Map<String, Object>> inverse = jdbc.queryForList("SELECT * FROM fx_rates WHERE base_ccy = ? AND quote_ccy = ? AND status = 'ACTIVE'", t, f);
            if (inverse.isEmpty()) throw new IllegalArgumentException("No approved rate for " + f + "/" + t);
            BigDecimal rate = (BigDecimal) inverse.get(0).get("rate");
            spread = ((Number) inverse.get(0).get("spread_bps")).intValue();
            rateId = inverse.get(0).get("id");
            keep = BigDecimal.valueOf(10_000 - spread).divide(BigDecimal.valueOf(10_000), mc);
            converted = BigDecimal.valueOf(amountMinor).multiply(keep, mc).divide(rate, mc);
        }
        long out = converted.setScale(0, RoundingMode.DOWN).longValueExact();
        BigDecimal customerRate = converted.divide(BigDecimal.valueOf(amountMinor), 12, RoundingMode.DOWN);
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("from", f);
        q.put("to", t);
        q.put("amountMinor", amountMinor);
        q.put("convertedMinor", out);
        q.put("rate", customerRate.stripTrailingZeros().toPlainString());
        q.put("spreadBps", spread);
        q.put("rateId", rateId);
        q.put("validUntil", LocalDateTime.now().plusSeconds(60).toString());
        q.put("note", "Indicative quote. Wallet holders can lock and execute a quote between their own wallets (POST /api/v1/wallet/fx/quotes).");
        return q;
    }

    private static Timestamp now() {
        return Timestamp.valueOf(LocalDateTime.now());
    }
}
