package zw.co.payport.gateway.psp.risk;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.platform.audit.AuditService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Case management for fraud cases, blocklist and rule administration. */
@Service
public class RiskCaseService {

    public static final Set<String> CLOSE_OUTCOMES = Set.of("CLOSED_FALSE_POSITIVE", "CLOSED_CONFIRMED_FRAUD", "ESCALATED_TO_AML");

    private final RiskCaseRepository cases;
    private final BlocklistRepository blocklist;
    private final RiskRuleRepository rules;
    private final AuditService audit;

    public RiskCaseService(RiskCaseRepository cases, BlocklistRepository blocklist, RiskRuleRepository rules, AuditService audit) {
        this.cases = cases;
        this.blocklist = blocklist;
        this.rules = rules;
        this.audit = audit;
    }

    public List<RiskCase> open() {
        return cases.findByStatusInOrderByCreatedAtDesc(List.of("OPEN", "INVESTIGATING"));
    }

    @Transactional
    public RiskCase assign(String caseRef, String to, Actor actor) {
        RiskCase c = get(caseRef);
        c.setAssignedTo(to);
        c.setStatus("INVESTIGATING");
        cases.save(c);
        audit.record(actor, "RISK_CASE_ASSIGNED", "RISK_CASE", caseRef, Map.of("to", to));
        return c;
    }

    @Transactional
    public RiskCase close(String caseRef, String outcome, String note, boolean blockSubject, Actor actor) {
        if (!CLOSE_OUTCOMES.contains(outcome)) throw PspException.badRequest("INVALID_OUTCOME", "One of " + CLOSE_OUTCOMES);
        if (note == null || note.length() < 10) throw PspException.badRequest("NOTE_REQUIRED", "Record the investigation outcome");
        RiskCase c = get(caseRef);
        c.setStatus(outcome);
        c.setOutcomeNote(note);
        c.setClosedBy(actor.id());
        c.setClosedAt(LocalDateTime.now());
        cases.save(c);
        if (blockSubject) addToBlocklist("SUBJECT", c.getSubjectRef(), "Confirmed fraud " + caseRef, null, actor);
        audit.record(actor, "RISK_CASE_CLOSED", "RISK_CASE", caseRef, Map.of("outcome", outcome, "blocked", blockSubject));
        return c;
    }

    @Transactional
    public BlocklistEntry addToBlocklist(String type, String value, String reason, LocalDateTime expiresAt, Actor actor) {
        BlocklistEntry b = blocklist.findByEntryTypeAndEntryValue(type, value).orElseGet(BlocklistEntry::new);
        b.setEntryType(type);
        b.setEntryValue(value);
        b.setReason(reason);
        b.setAddedBy(actor.id());
        b.setExpiresAt(expiresAt);
        blocklist.save(b);
        audit.record(actor, "BLOCKLIST_ADD", "BLOCKLIST", type + ":" + value, Map.of("reason", reason));
        return b;
    }

    @Transactional
    public void removeFromBlocklist(Long id, String reason, Actor actor) {
        BlocklistEntry b = blocklist.findById(id).orElseThrow(() -> PspException.notFound("Blocklist entry", id));
        blocklist.delete(b);
        audit.record(actor, "BLOCKLIST_REMOVE", "BLOCKLIST", b.getEntryType() + ":" + b.getEntryValue(), Map.of("reason", String.valueOf(reason)));
    }

    public List<BlocklistEntry> blocklist() {
        return blocklist.findAll();
    }

    public List<RiskRule> rules() {
        return rules.findAll();
    }

    /** Rules are tuned by the fraud team; every change is audited with before/after. */
    @Transactional
    public RiskRule updateRule(String ruleCode, Integer thresholdCount, Long thresholdAmountUsdMinor, Integer windowMinutes,
                               Integer score, String action, Boolean enabled, Actor actor) {
        RiskRule r = rules.findByRuleCode(ruleCode).orElseThrow(() -> PspException.notFound("Rule", ruleCode));
        String before = r.getThresholdCount() + "/" + r.getThresholdAmountUsdMinor() + "/" + r.getWindowMinutes() + "/" + r.getScore() + "/" + r.getAction() + "/" + r.isEnabled();
        if (thresholdCount != null) r.setThresholdCount(thresholdCount);
        if (thresholdAmountUsdMinor != null) r.setThresholdAmountUsdMinor(thresholdAmountUsdMinor);
        if (windowMinutes != null) r.setWindowMinutes(windowMinutes);
        if (score != null) r.setScore(Math.max(0, Math.min(100, score)));
        if (action != null) {
            if (!Set.of("REVIEW", "BLOCK", "SCORE").contains(action)) throw PspException.badRequest("INVALID_ACTION", "REVIEW, BLOCK or SCORE");
            r.setAction(action);
        }
        if (enabled != null) r.setEnabled(enabled);
        rules.save(r);
        String after = r.getThresholdCount() + "/" + r.getThresholdAmountUsdMinor() + "/" + r.getWindowMinutes() + "/" + r.getScore() + "/" + r.getAction() + "/" + r.isEnabled();
        audit.record(actor, "RISK_RULE_UPDATED", "RISK_RULE", ruleCode, Map.of("before", before, "after", after));
        return r;
    }

    private RiskCase get(String ref) {
        return cases.findByCaseRef(ref).orElseThrow(() -> PspException.notFound("Case", ref));
    }
}
