package zw.co.payport.gateway.psp.routing;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.ledger.domain.AccountKey;
import zw.co.payport.gateway.psp.legacy.LegacyPayments;
import zw.co.payport.gateway.psp.platform.audit.AuditService;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Smart routing: picks the rail for a payment or payout.
 *
 *  MOBILE_WALLET  the customer's number decides the network (077/078 Econet -> EcoCash, 071 NetOne -> OneMoney,
 *                 073 Telecel -> Telecash); wallets cannot fail over to another network, so if that rail is down
 *                 the customer is told to use another method instead of being routed somewhere that will fail.
 *  CARD           ZimSwitch vs an international acquirer by priority, health and cost.
 *  BILL / AIRTIME biller aggregator choice (e-Solutions vs direct) by health and cost.
 *  PAYOUT_BANK    ZIPIT for amounts within its band, otherwise RTGS (ZIPSS).
 *  PAYOUT_WALLET  wallet disbursement rail for the beneficiary's network.
 */
@Service
public class RoutingService {

    public static final Set<String> INSTRUMENTS = Set.of("MOBILE_WALLET", "CARD", "BANK_TRANSFER", "BILL", "AIRTIME",
            "PAYOUT_BANK", "PAYOUT_WALLET");

    public record RouteRequest(String instrument, String currency, long amountMinor, String msisdn) {}

    public record RouteDecision(String instrument, String currency, long amountMinor, String customerNetwork,
                                String chosenProvider, List<RouteScorer.Candidate> candidates, String explanation) {
        public boolean routable() { return chosenProvider != null; }
    }

    private final RoutingRuleRepository rules;
    private final LegacyPayments payments;
    private final AuditService audit;

    public RoutingService(RoutingRuleRepository rules, LegacyPayments payments, AuditService audit) {
        this.rules = rules;
        this.payments = payments;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public RouteDecision route(RouteRequest req) {
        String instrument = req.instrument() == null ? "" : req.instrument().toUpperCase();
        if (!INSTRUMENTS.contains(instrument)) throw PspException.badRequest("UNKNOWN_INSTRUMENT", "Instrument must be one of " + INSTRUMENTS);
        String ccy = AccountKey.normaliseCurrency(req.currency());

        String network = (instrument.equals("MOBILE_WALLET") || instrument.equals("PAYOUT_WALLET") || instrument.equals("AIRTIME"))
                ? payments.networkForMsisdn(req.msisdn()).orElse(null) : null;

        List<RouteScorer.Rule> rs = rules.findByInstrumentAndCurrencyAndEnabledTrue(instrument, ccy).stream()
                .map(r -> new RouteScorer.Rule(r.getProvider(), r.getPriority(), r.getMinAmountMinor(), r.getMaxAmountMinor(),
                        r.getCostBps(), r.getCostFixedMinor(), r.getNetworkCode()))
                .toList();

        Map<String, String> health = payments.providerHealth();
        Map<String, Long> failures = new HashMap<>();
        LocalDateTime since = LocalDateTime.now().minusMinutes(15);
        for (RouteScorer.Rule r : rs) failures.put(r.provider().toUpperCase(), payments.recentFailures(r.provider(), since));

        List<RouteScorer.Candidate> ranked = RouteScorer.rank(rs, req.amountMinor(), network, health, failures);
        String chosen = ranked.stream().filter(RouteScorer.Candidate::eligible).map(RouteScorer.Candidate::provider).findFirst().orElse(null);
        String why = chosen != null
                ? "Routed to " + chosen + (network != null ? " for " + network + " number" : "")
                : (rs.isEmpty() ? "No route configured for " + instrument + " " + ccy
                    : "No eligible provider: " + String.join("; ", ranked.stream().map(c -> c.provider() + " " + c.reason()).toList()));
        return new RouteDecision(instrument, ccy, req.amountMinor(), network, chosen, ranked, why);
    }

    /** Convenience for wallet collections: returns the payment method code or throws a clear customer-facing error. */
    public String walletProviderFor(String msisdn, String currency, long amountMinor) {
        RouteDecision d = route(new RouteRequest("MOBILE_WALLET", currency, amountMinor, msisdn));
        if (!d.routable()) throw PspException.unavailable("NO_WALLET_ROUTE", d.explanation());
        return d.chosenProvider();
    }

    // ── Admin ────────────────────────────────────────────────────────────

    public List<RoutingRule> all() {
        return rules.findAllByOrderByInstrumentAscCurrencyAscPriorityAsc();
    }

    /** Routing changes are operational (switching a rail off during an outage must be instant), so they are audited, not four-eyes. */
    @Transactional
    public RoutingRule upsert(Long id, RoutingRule in, Actor actor) {
        RoutingRule r = id == null ? new RoutingRule() : rules.findById(id).orElseThrow(() -> PspException.notFound("Routing rule", id));
        if (!INSTRUMENTS.contains(in.getInstrument())) throw PspException.badRequest("UNKNOWN_INSTRUMENT", "Instrument must be one of " + INSTRUMENTS);
        r.setInstrument(in.getInstrument());
        r.setCurrency(AccountKey.normaliseCurrency(in.getCurrency()));
        r.setProvider(in.getProvider().toUpperCase());
        r.setPriority(in.getPriority());
        r.setWeight(Math.max(1, in.getWeight()));
        r.setMinAmountMinor(Math.max(0, in.getMinAmountMinor()));
        r.setMaxAmountMinor(Math.max(0, in.getMaxAmountMinor()));
        r.setCostBps(in.getCostBps());
        r.setCostFixedMinor(in.getCostFixedMinor());
        r.setNetworkCode(in.getNetworkCode() == null || in.getNetworkCode().isBlank() ? null : in.getNetworkCode().toUpperCase());
        r.setEnabled(in.isEnabled());
        r.setNotes(in.getNotes());
        rules.save(r);
        audit.record(actor, id == null ? "ROUTE_CREATED" : "ROUTE_UPDATED", "ROUTING_RULE", String.valueOf(r.getId()),
                Map.of("instrument", r.getInstrument(), "currency", r.getCurrency(), "provider", r.getProvider(),
                        "priority", r.getPriority(), "enabled", r.isEnabled()));
        return r;
    }
}
