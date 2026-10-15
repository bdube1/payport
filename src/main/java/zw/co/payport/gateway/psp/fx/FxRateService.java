package zw.co.payport.gateway.psp.fx;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.common.Refs;
import zw.co.payport.gateway.psp.integration.IntegrationSpis;
import zw.co.payport.gateway.psp.ledger.domain.AccountKey;
import zw.co.payport.gateway.psp.ledger.domain.FxMath;
import zw.co.payport.gateway.psp.platform.approval.ApprovalRequest;
import zw.co.payport.gateway.psp.platform.approval.ApprovalService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ZiG (ZWG) and USD: rates, quotes and USD-equivalents (conversions are in FxConversionService).
 *
 * Rates are stored as USD/ZWG (1 USD = x ZWG). Sources:
 *   - RBZ interbank rate: entered by treasury (RBZ publishes rates on its
 *     website; there is no public API), effective only after four-eyes approval
 *   - any FxRateSource bean (e.g. a bank's rate feed) ingested automatically
 * Quotes are refused when the latest rate is older than the staleness limit,
 * so a stale rate can never be used to move money.
 */
@Service
public class FxRateService {

    private static final Logger log = LoggerFactory.getLogger(FxRateService.class);
    public static final String APPROVE_RATE = "FX_RATE_ACTIVATE";
    public static final String BASE = "USD";
    public static final String QUOTE = "ZWG";

    public record Quote(String from, String to, long fromAmountMinor, long toAmountMinor, long midAmountMinor,
                        BigDecimal customerRate, BigDecimal midRate, String rateRef, LocalDateTime rateAsOf) {}

    private final FxRateRepository rates;
    private final ApprovalService approvals;
    private final List<IntegrationSpis.FxRateSource> sources;
    private final ObjectMapper om;

    @Value("${payport.psp.fx.max-rate-age-hours:36}")
    private int maxAgeHours = 36;

    @Value("${payport.psp.fx.customer-spread-bps:150}")
    private int spreadBps = 150;

    public FxRateService(FxRateRepository rates, ApprovalService approvals, List<IntegrationSpis.FxRateSource> sources, ObjectMapper om) {
        this.rates = rates;
        this.approvals = approvals;
        this.sources = sources;
        this.om = om;
    }

    public FxRate currentRate() {
        FxRate r = rates.findTopByBaseCcyAndQuoteCcyAndStatusOrderByAsOfDesc(BASE, QUOTE, "ACTIVE")
                .orElseThrow(() -> PspException.unavailable("NO_FX_RATE", "No approved USD/ZWG rate"));
        if (Duration.between(r.getAsOf(), LocalDateTime.now()).toHours() > maxAgeHours) {
            throw PspException.unavailable("FX_RATE_STALE", "The latest USD/ZWG rate (" + r.getAsOf() + ") is older than " + maxAgeHours + "h");
        }
        return r;
    }

    public Quote quote(String from, String to, long amountMinor) {
        String f = AccountKey.normaliseCurrency(from), t = AccountKey.normaliseCurrency(to);
        if (f.equals(t)) throw PspException.badRequest("SAME_CURRENCY", "Choose two different currencies");
        if (amountMinor <= 0) throw PspException.badRequest("INVALID_AMOUNT", "Amount must be positive");
        FxRate r = currentRate();
        boolean toQuote = t.equals(QUOTE);
        BigDecimal customerRate = toQuote ? r.getBid() : r.getAsk();
        BigDecimal spreadRate = FxMath.applySpread(customerRate, spreadBps, toQuote);
        long mid = FxMath.convert(amountMinor, f, t, BASE, QUOTE, r.getMid(), RoundingMode.HALF_EVEN);
        long cust = FxMath.convert(amountMinor, f, t, BASE, QUOTE, spreadRate, RoundingMode.DOWN);
        if (cust > mid) cust = mid; // never pay the customer more than mid
        return new Quote(f, t, amountMinor, cust, mid, spreadRate.setScale(8, RoundingMode.HALF_EVEN), r.getMid(), r.getRateRef(), r.getAsOf());
    }

    /** USD value of an amount at the mid rate: for limits that are set in USD and for regulatory returns. */
    public long usdEquivalent(long amountMinor, String currency) {
        String c = AccountKey.normaliseCurrency(currency);
        if (BASE.equals(c) || amountMinor == 0) return amountMinor;
        Optional<FxRate> r = rates.findTopByBaseCcyAndQuoteCcyAndStatusOrderByAsOfDesc(BASE, QUOTE, "ACTIVE");
        if (r.isEmpty()) throw PspException.unavailable("NO_FX_RATE", "No approved USD/ZWG rate");
        return FxMath.convert(amountMinor, c, BASE, BASE, QUOTE, r.get().getMid(), RoundingMode.HALF_EVEN);
    }

    // ── Rate management ──────────────────────────────────────────────────

    @Transactional
    public ApprovalRequest proposeRate(BigDecimal bid, BigDecimal ask, LocalDateTime asOf, String source, Actor actor) {
        if (bid == null || ask == null || bid.signum() <= 0 || ask.signum() <= 0) throw PspException.badRequest("INVALID_RATE", "bid and ask must be positive");
        if (bid.compareTo(ask) > 0) throw PspException.badRequest("INVALID_RATE", "bid must not exceed ask");
        FxRate current = rates.findTopByBaseCcyAndQuoteCcyAndStatusOrderByAsOfDesc(BASE, QUOTE, "ACTIVE").orElse(null);
        BigDecimal mid = bid.add(ask).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_EVEN);
        String warning = "";
        if (current != null) {
            BigDecimal move = mid.subtract(current.getMid()).abs().divide(current.getMid(), 6, RoundingMode.HALF_EVEN);
            if (move.compareTo(new BigDecimal("0.05")) > 0) warning = " WARNING: moves mid by " + move.movePointRight(2).setScale(2, RoundingMode.HALF_EVEN) + "%";
        }
        FxRate r = new FxRate();
        r.setRateRef(Refs.newRef("fxr"));
        r.setBaseCcy(BASE);
        r.setQuoteCcy(QUOTE);
        r.setBid(bid);
        r.setAsk(ask);
        r.setMid(mid);
        r.setAsOf(asOf != null ? asOf : LocalDateTime.now());
        r.setSource(source == null ? "MANUAL" : source.toUpperCase());
        r.setStatus("PENDING_APPROVAL");
        r.setCreatedBy(actor.id());
        rates.save(r);
        try {
            return approvals.request(APPROVE_RATE, r.getRateRef(), om.writeValueAsString(Map.of("rateRef", r.getRateRef())),
                    "USD/ZWG " + r.getSource() + " bid " + bid + " ask " + ask + " as of " + r.getAsOf() + warning, null, actor);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Pulls from automated sources (bank feeds); RBZ manual entry goes through proposeRate. */
    @Scheduled(cron = "${payport.psp.fx.ingest-cron:0 15 8,14 * * MON-FRI}", zone = "Africa/Harare")
    @Transactional
    public void ingest() {
        for (IntegrationSpis.FxRateSource s : sources) {
            try {
                for (IntegrationSpis.FxQuote q : s.fetch()) {
                    if (!BASE.equals(q.base()) || !QUOTE.equals(AccountKey.normaliseCurrency(q.quote()))) continue;
                    FxRate r = new FxRate();
                    r.setRateRef(Refs.newRef("fxr"));
                    r.setBaseCcy(BASE);
                    r.setQuoteCcy(QUOTE);
                    r.setBid(q.bid());
                    r.setAsk(q.ask());
                    r.setMid(q.mid());
                    r.setAsOf(q.asOf());
                    r.setSource(s.code());
                    r.setStatus("ACTIVE");
                    r.setCreatedBy("SYSTEM:" + s.code());
                    r.setApprovedBy("SYSTEM:" + s.code());
                    rates.save(r);
                    log.info("[FX] Ingested {} USD/ZWG mid {} as of {}", s.code(), q.mid(), q.asOf());
                }
            } catch (Exception e) {
                log.warn("[FX] Rate source {} failed: {}", s.code(), e.getMessage());
            }
        }
    }

    public List<FxRate> history() {
        return rates.findTop100ByOrderByAsOfDesc();
    }
}
