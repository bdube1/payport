package zw.co.payport.gateway.psp.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.payport.gateway.psp.common.Refs;
import zw.co.payport.gateway.psp.common.RiskPorts;
import zw.co.payport.gateway.psp.fx.FxRateService;
import zw.co.payport.gateway.psp.platform.outbox.EventTypes;
import zw.co.payport.gateway.psp.platform.outbox.OutboxService;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real-time fraud engine. Called before money moves (wallet, agent,
 * checkout, payouts). Decision in a few milliseconds from:
 *   1. blocklists (MSISDN, device, IP, counterparty): hard BLOCK
 *   2. configurable rules over velocity features from psp_risk_events
 *   3. score thresholds: >= block-score BLOCK, >= review-score REVIEW
 * REVIEW lets the payment through and opens a case; BLOCK stops it and
 * opens a case. If the engine itself fails, it fails OPEN for low values and
 * CLOSED above payport.psp.risk.fail-closed-above-usd-minor, and says so.
 */
@Service
public class FraudEngine implements RiskPorts.RiskAssessor {

    private static final Logger log = LoggerFactory.getLogger(FraudEngine.class);
    private static final ZoneId HARARE = ZoneId.of("Africa/Harare");

    private final RiskRuleRepository rules;
    private final BlocklistRepository blocklist;
    private final RiskEventRepository events;
    private final RiskCaseRepository cases;
    private final FxRateService fx;
    private final OutboxService outbox;
    private final TransactionTemplate newTx;
    private final AtomicReference<List<RuleEvaluator.Rule>> cache = new AtomicReference<>(List.of());

    @Value("${payport.psp.risk.review-score:40}")
    private int reviewScore = 40;
    @Value("${payport.psp.risk.block-score:80}")
    private int blockScore = 80;
    @Value("${payport.psp.risk.fail-closed-above-usd-minor:50000}")
    private long failClosedAbove = 50_000;

    public FraudEngine(RiskRuleRepository rules, BlocklistRepository blocklist, RiskEventRepository events, RiskCaseRepository cases,
                       FxRateService fx, OutboxService outbox, PlatformTransactionManager txManager) {
        this.rules = rules;
        this.blocklist = blocklist;
        this.events = events;
        this.cases = cases;
        this.fx = fx;
        this.outbox = outbox;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 5_000)
    public void reloadRules() {
        cache.set(rules.findByEnabledTrue().stream().map(r -> new RuleEvaluator.Rule(r.getRuleCode(), r.getRuleType(),
                Arrays.stream(r.getAppliesTo().split(",")).map(String::trim).toList(), r.getWindowMinutes(), r.getThresholdCount(),
                r.getThresholdAmountUsdMinor(), r.getScore(), r.getAction())).toList());
    }

    @Override
    public RiskPorts.RiskDecision assess(RiskPorts.RiskContext ctx) {
        long usd;
        try {
            usd = fx.usdEquivalent(ctx.amountMinor(), ctx.currency());
        } catch (Exception e) {
            usd = ctx.amountMinor(); // no FX rate: treat face value as USD (conservative for ZWG)
        }
        try {
            RiskPorts.RiskDecision d = decide(ctx, usd);
            record(ctx, usd, d);
            return d;
        } catch (Exception e) {
            log.error("[FRAUD] Engine failure on {} {}: {}", ctx.type(), ctx.subjectRef(), e.getMessage());
            if (usd > failClosedAbove) {
                return new RiskPorts.RiskDecision("BLOCK", 100, List.of("Risk engine unavailable; high-value transaction held"), null);
            }
            return new RiskPorts.RiskDecision("ALLOW", 0, List.of("Risk engine unavailable; allowed under fail-open threshold"), null);
        }
    }

    private RiskPorts.RiskDecision decide(RiskPorts.RiskContext ctx, long usd) {
        List<String> keys = new ArrayList<>();
        if (ctx.msisdn() != null) keys.add(ctx.msisdn());
        if (ctx.subjectRef() != null) keys.add(ctx.subjectRef());
        if (ctx.counterpartyRef() != null) keys.add(ctx.counterpartyRef());
        if (ctx.deviceId() != null) keys.add(ctx.deviceId());
        if (ctx.ipAddress() != null) keys.add(ctx.ipAddress());
        LocalDateTime now = LocalDateTime.now();
        for (BlocklistEntry b : blocklist.findByEntryValueIn(keys)) {
            if (b.getExpiresAt() == null || b.getExpiresAt().isAfter(now)) {
                return new RiskPorts.RiskDecision("BLOCK", 100, List.of("BLOCKLIST " + b.getEntryType() + ": " + b.getReason()), null);
            }
        }

        boolean newCounterparty = ctx.counterpartyRef() != null && events.countWithCounterparty(ctx.subjectRef(), ctx.counterpartyRef()) == 0;
        RuleEvaluator.Features f = new RuleEvaluator.Features(ctx.type(), usd, newCounterparty, LocalTime.now(HARARE));
        Map<Integer, RuleEvaluator.Window> windows = new HashMap<>();
        List<RuleEvaluator.Hit> hits = new ArrayList<>();
        for (RuleEvaluator.Rule r : cache.get()) {
            if (!RuleEvaluator.applies(r, ctx.type())) continue;
            RuleEvaluator.Window w = windows.computeIfAbsent(r.windowMinutes(), m -> window(ctx.subjectRef(), m));
            RuleEvaluator.Hit h = RuleEvaluator.evaluate(r, f, w);
            if (h != null) hits.add(h);
        }
        RuleEvaluator.Outcome o = RuleEvaluator.combine(hits, reviewScore, blockScore);
        String caseRef = null;
        if (!"ALLOW".equals(o.action())) caseRef = openCase(ctx, o);
        return new RiskPorts.RiskDecision(o.action(), o.score(), hits.stream().map(RuleEvaluator.Hit::reason).toList(), caseRef);
    }

    private RuleEvaluator.Window window(String subject, int minutes) {
        if (minutes <= 0) return new RuleEvaluator.Window(0, 0, 0);
        List<Object[]> r = events.stats(subject, LocalDateTime.now().minusMinutes(minutes));
        if (r == null || r.isEmpty()) return new RuleEvaluator.Window(0, 0, 0);
        Object[] x = r.get(0);
        return new RuleEvaluator.Window(((Number) x[0]).longValue(), ((Number) x[1]).longValue(), ((Number) x[2]).longValue());
    }

    /** Recorded in its own transaction: a BLOCK rolls the payment back, but the attempt must still count. */
    private void record(RiskPorts.RiskContext ctx, long usd, RiskPorts.RiskDecision d) {
        newTx.executeWithoutResult(s -> {
            RiskEvent e = new RiskEvent();
            e.setTxnType(ctx.type());
            e.setChannel(ctx.channel());
            e.setSubjectRef(ctx.subjectRef());
            e.setCounterpartyRef(ctx.counterpartyRef());
            e.setAmountUsdMinor(usd);
            e.setCurrency(ctx.currency());
            e.setAmountMinor(ctx.amountMinor());
            e.setScore(d.score());
            e.setDecision(d.action());
            String reasons = String.join(" | ", d.reasons());
            e.setReasons(reasons.length() > 1000 ? reasons.substring(0, 1000) : reasons);
            events.save(e);
        });
    }

    private String openCase(RiskPorts.RiskContext ctx, RuleEvaluator.Outcome o) {
        String ref = Refs.newRef("rcs");
        newTx.executeWithoutResult(s -> {
            RiskCase c = new RiskCase();
            c.setCaseRef(ref);
            c.setCaseType("FRAUD");
            c.setSubjectType(ctx.subjectType());
            c.setSubjectRef(ctx.subjectRef());
            c.setTriggerRef(ctx.type() + ":" + ctx.counterpartyRef());
            c.setScore(o.score());
            c.setReasons(String.join("\n", o.hits().stream().map(RuleEvaluator.Hit::reason).toList())
                    + "\nAmount " + ctx.amountMinor() + " " + ctx.currency() + " via " + ctx.channel() + " -> " + o.action());
            c.setStatus("OPEN");
            cases.save(c);
            outbox.publish(EventTypes.FRAUD_CASE_OPENED, "RISK_CASE", ref, Map.of("caseRef", ref, "subjectRef", ctx.subjectRef(),
                    "decision", o.action(), "score", o.score()));
        });
        return ref;
    }

    @Scheduled(cron = "0 40 2 * * *", zone = "Africa/Harare")
    public void purgeOldEvents() {
        Integer n = newTx.execute(s -> events.purgeBefore(LocalDateTime.now().minusMonths(13)));
        if (n != null && n > 0) log.info("[FRAUD] Purged {} risk events older than 13 months", n);
    }
}
