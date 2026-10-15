package zw.co.payport.gateway.psp.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.payport.gateway.psp.agent.Agent;
import zw.co.payport.gateway.psp.agent.AgentRepository;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.common.Refs;
import zw.co.payport.gateway.psp.common.RiskPorts;
import zw.co.payport.gateway.psp.integration.IntegrationSpis;
import zw.co.payport.gateway.psp.kyc.CustomerProfile;
import zw.co.payport.gateway.psp.kyc.CustomerProfileRepository;
import zw.co.payport.gateway.psp.ledger.domain.ChainHasher;
import zw.co.payport.gateway.psp.legacy.LegacyGateway;
import zw.co.payport.gateway.psp.merchant.MerchantProfile;
import zw.co.payport.gateway.psp.merchant.MerchantProfileRepository;
import zw.co.payport.gateway.psp.platform.audit.AuditService;
import zw.co.payport.gateway.psp.platform.outbox.EventTypes;
import zw.co.payport.gateway.psp.platform.outbox.OutboxService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sanctions and PEP screening.
 *
 *  - Lists: UN consolidated, OFAC SDN (daily refresh when feeds are enabled),
 *    plus LOCAL (FIU / domestic designations) and PEP lists uploaded by compliance.
 *  - Who is screened: every customer at registration and ID verification,
 *    every agent and merchant at onboarding, and everyone again whenever a list changes.
 *  - Outcome: score >= potential threshold raises an AML alert for a human.
 *    Score >= the auto-freeze threshold freezes the customer pending review
 *    (protective; a person still decides). Nothing is "cleared" automatically.
 */
@Service
public class AmlScreeningService implements RiskPorts.Screener {

    private static final Logger log = LoggerFactory.getLogger(AmlScreeningService.class);

    record Indexed(long id, String listCode, String type, String primary, List<String> aliases, String dob) {}

    private final SanctionsEntryRepository entries;
    private final SanctionsListVersionRepository versions;
    private final AmlAlertRepository alerts;
    private final List<IntegrationSpis.SanctionsListSource> sources;
    private final CustomerProfileRepository customers;
    private final AgentRepository agents;
    private final MerchantProfileRepository merchantProfiles;
    private final LegacyGateway legacy;
    private final AuditService audit;
    private final OutboxService outbox;
    private final TransactionTemplate newTx;
    private final AtomicReference<Map<String, List<Indexed>>> index = new AtomicReference<>(Map.of());

    @Value("${payport.psp.aml.potential-match-score:85}")
    private int potentialScore = 85;
    @Value("${payport.psp.aml.auto-freeze-score:97}")
    private int autoFreezeScore = 97;

    public AmlScreeningService(SanctionsEntryRepository entries, SanctionsListVersionRepository versions, AmlAlertRepository alerts,
                               List<IntegrationSpis.SanctionsListSource> sources, CustomerProfileRepository customers,
                               AgentRepository agents, MerchantProfileRepository merchantProfiles, LegacyGateway legacy,
                               AuditService audit, OutboxService outbox, PlatformTransactionManager txManager) {
        this.entries = entries;
        this.versions = versions;
        this.alerts = alerts;
        this.sources = sources;
        this.customers = customers;
        this.agents = agents;
        this.merchantProfiles = merchantProfiles;
        this.legacy = legacy;
        this.audit = audit;
        this.outbox = outbox;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ── Screening ─────────────────────────────────────────────────────────

    @Override
    public RiskPorts.ScreeningOutcome screen(String subjectType, String subjectRef, String fullName, String dateOfBirth, String nationality) {
        if (fullName == null || fullName.isBlank()) return RiskPorts.ScreeningOutcome.clear();
        if (index.get().isEmpty()) reloadIndex();
        Indexed best = null;
        int bestScore = 0;
        for (Indexed e : candidates(fullName)) {
            int s = NameMatcher.bestScore(fullName, e.primary(), e.aliases());
            if (s > 0 && dateOfBirth != null && e.dob() != null && !e.dob().isBlank()) {
                String y = dateOfBirth.length() >= 4 ? dateOfBirth.substring(0, 4) : dateOfBirth;
                s = e.dob().contains(y) ? Math.min(100, s + 3) : s - 10; // DOB agreement strengthens, conflict weakens
            }
            if (s > bestScore) { bestScore = s; best = e; }
        }
        if (best == null || bestScore < potentialScore) return RiskPorts.ScreeningOutcome.clear();

        String alertRef = raiseAlert("SANCTIONS_MATCH", subjectType, subjectRef, fullName, "entry:" + best.id(), best.id(), bestScore, null,
                "Possible match with " + best.listCode() + " " + best.type() + " '" + best.primary() + "' (score " + bestScore + ")");
        String status = bestScore >= autoFreezeScore ? "CONFIRMED_MATCH" : "POTENTIAL_MATCH";
        // CONFIRMED_MATCH here means "strong enough to freeze pending human review", never "cleared by a machine".
        return new RiskPorts.ScreeningOutcome(status, bestScore, best.listCode() + ":" + best.primary(), alertRef);
    }

    private Collection<Indexed> candidates(String name) {
        Map<String, List<Indexed>> idx = index.get();
        Map<Long, Indexed> out = new LinkedHashMap<>();
        for (String t : NameMatcher.normalise(name).split(" ")) {
            if (t.length() < 2) continue;
            for (Indexed e : idx.getOrDefault(t.substring(0, 2), List.of())) out.put(e.id(), e);
        }
        return out.values();
    }

    public synchronized void reloadIndex() {
        Map<String, List<Indexed>> idx = new HashMap<>();
        for (SanctionsEntry e : entries.findByActiveTrue()) {
            List<String> aliases = e.getAliases() == null || e.getAliases().isBlank() ? List.of() : Arrays.asList(e.getAliases().split("\\|"));
            Indexed ie = new Indexed(e.getId(), e.getListCode(), e.getEntryType(), e.getPrimaryName(), aliases, e.getDateOfBirth());
            Set<String> keys = new HashSet<>();
            List<String> names = new ArrayList<>(aliases);
            names.add(e.getPrimaryName());
            for (String n : names) for (String t : NameMatcher.normalise(n).split(" ")) if (t.length() >= 2) keys.add(t.substring(0, 2));
            for (String k : keys) idx.computeIfAbsent(k, x -> new ArrayList<>()).add(ie);
        }
        index.set(idx);
        log.info("[AML] Screening index loaded: {} prefixes", idx.size());
    }

    // ── Alerts & decisions ───────────────────────────────────────────────

    public String raiseAlert(String type, String subjectType, String subjectRef, String subjectName, String dedupeKey,
                             Long entryId, int score, Long amountUsdMinor, String details) {
        String[] ref = new String[1];
        try {
            newTx.executeWithoutResult(s -> {
                Optional<AmlAlert> existing = alerts.findByAlertTypeAndSubjectRefAndDedupeKey(type, subjectRef, dedupeKey);
                if (existing.isPresent()) { ref[0] = existing.get().getAlertRef(); return; }
                AmlAlert a = new AmlAlert();
                a.setAlertRef(Refs.newRef("aml"));
                a.setAlertType(type);
                a.setSubjectType(subjectType);
                a.setSubjectRef(subjectRef);
                a.setSubjectName(subjectName);
                a.setDedupeKey(dedupeKey);
                a.setMatchedEntryId(entryId);
                a.setScore(score);
                a.setAmountUsdMinor(amountUsdMinor);
                a.setDetails(details);
                a.setStatus("OPEN");
                alerts.saveAndFlush(a);
                outbox.publish(EventTypes.AML_ALERT_RAISED, "AML_ALERT", a.getAlertRef(), Map.of("alertRef", a.getAlertRef(),
                        "type", type, "subjectRef", subjectRef, "score", score));
                ref[0] = a.getAlertRef();
            });
        } catch (DataIntegrityViolationException raced) {
            ref[0] = alerts.findByAlertTypeAndSubjectRefAndDedupeKey(type, subjectRef, dedupeKey).map(AmlAlert::getAlertRef).orElse(null);
        }
        return ref[0];
    }

    /** Compliance decision on an alert. CONFIRMED freezes the subject; STR_FILED records that a suspicious transaction report went to the FIU. */
    @Transactional
    public AmlAlert decide(String alertRef, String decision, String note, Actor actor) {
        if (!actor.hasAnyRole("COMPLIANCE", "ADMIN")) throw PspException.forbidden("COMPLIANCE_ONLY", "Compliance role required");
        if (!Set.of("CLEARED", "CONFIRMED", "STR_FILED").contains(decision)) throw PspException.badRequest("INVALID_DECISION", "CLEARED, CONFIRMED or STR_FILED");
        if (note == null || note.length() < 10) throw PspException.badRequest("NOTE_REQUIRED", "Record the reasoning");
        AmlAlert a = alerts.findByAlertRef(alertRef).orElseThrow(() -> PspException.notFound("AML alert", alertRef));
        a.setStatus(decision);
        a.setReviewedBy(actor.id());
        a.setReviewNote(note);
        a.setDecidedAt(LocalDateTime.now());
        alerts.save(a);
        if ("CONFIRMED".equals(decision) && a.getAlertType().startsWith("SANCTIONS")) freezeSubject(a, actor);
        if ("CLEARED".equals(decision) && "CUSTOMER".equals(a.getSubjectType())) {
            customers.findByCustomerRef(a.getSubjectRef()).ifPresent(c -> {
                if (!"CONFIRMED_MATCH".equals(c.getSanctionsStatus()) || c.getStatusReason() == null || !c.getStatusReason().startsWith("Sanctions")) return;
                c.setSanctionsStatus("CLEAR");
                customers.save(c); // unfreezing still needs the four-eyes CUSTOMER_UNFREEZE approval
            });
        }
        audit.record(actor, "AML_ALERT_" + decision, "AML_ALERT", alertRef, Map.of("subject", a.getSubjectRef(), "note", note));
        return a;
    }

    private void freezeSubject(AmlAlert a, Actor actor) {
        switch (a.getSubjectType()) {
            case "CUSTOMER" -> customers.findByCustomerRef(a.getSubjectRef()).ifPresent(c -> {
                c.setStatus("FROZEN");
                c.setSanctionsStatus("CONFIRMED_MATCH");
                c.setStatusReason("Sanctions match confirmed " + a.getAlertRef());
                customers.save(c);
            });
            case "AGENT" -> agents.findByAgentRef(a.getSubjectRef()).ifPresent(ag -> {
                ag.setStatus("SUSPENDED");
                ag.setStatusReason("Sanctions match confirmed " + a.getAlertRef());
                agents.save(ag);
            });
            case "MERCHANT" -> merchantProfiles.findByMerchantId(Long.parseLong(a.getSubjectRef())).ifPresent(p -> {
                p.setSuspensionScope("FULL");
                p.setSuspensionReason("Sanctions match confirmed " + a.getAlertRef());
                p.setSuspendedBy(actor.id());
                p.setSuspendedAt(LocalDateTime.now());
                merchantProfiles.save(p);
            });
            default -> { }
        }
    }

    public List<AmlAlert> openAlerts() {
        return alerts.findByStatusInOrderByCreatedAtDesc(List.of("OPEN"));
    }

    // ── List refresh ─────────────────────────────────────────────────────

    @Scheduled(cron = "${payport.psp.aml.refresh-cron:0 0 4 * * *}", zone = "Africa/Harare")
    public void refreshLists() {
        boolean changed = false;
        for (IntegrationSpis.SanctionsListSource s : sources) {
            try {
                List<IntegrationSpis.SanctionsEntry> list = s.fetch();
                if (list == null) continue; // feed disabled
                changed |= load(s.listCode(), list, "feed");
            } catch (Exception e) {
                log.error("[AML] Refresh of {} failed: {}", s.listCode(), e.getMessage());
                newTx.executeWithoutResult(st -> {
                    SanctionsListVersion v = new SanctionsListVersion();
                    v.setListCode(s.listCode());
                    v.setSha256("-");
                    v.setEntryCount(0);
                    v.setStatus("FAILED");
                    v.setError(e.getMessage() == null ? "error" : (e.getMessage().length() > 1000 ? e.getMessage().substring(0, 1000) : e.getMessage()));
                    v.setFetchedAt(LocalDateTime.now());
                    versions.save(v);
                });
            }
        }
        if (changed) {
            reloadIndex();
            rescreenEveryone();
        }
    }

    /**
     * Compliance upload for LOCAL (FIU / domestic) or PEP lists.
     * CSV columns: id,name,type,aliases(separated by ;),date_of_birth,nationality,programme
     */
    public int importList(String listCode, byte[] csv, Actor actor) throws Exception {
        if (!Set.of("LOCAL", "PEP").contains(listCode)) throw PspException.badRequest("INVALID_LIST", "Upload LOCAL or PEP lists");
        if (!actor.hasAnyRole("COMPLIANCE", "ADMIN")) throw PspException.forbidden("COMPLIANCE_ONLY", "Compliance role required");
        List<IntegrationSpis.SanctionsEntry> list = new ArrayList<>();
        for (List<String> r : SanctionsListSources.Csv.parse(new String(csv, StandardCharsets.UTF_8))) {
            if (r.size() < 2 || r.get(0).equalsIgnoreCase("id") || r.get(1).isBlank()) continue;
            List<String> aliases = r.size() > 3 && !r.get(3).isBlank() ? Arrays.stream(r.get(3).split(";")).map(String::trim).toList() : List.of();
            list.add(new IntegrationSpis.SanctionsEntry(listCode, r.get(0).trim(), r.size() > 2 && !r.get(2).isBlank() ? r.get(2).trim().toUpperCase() : "INDIVIDUAL",
                    r.get(1).trim(), aliases, r.size() > 5 ? r.get(5) : null, r.size() > 4 ? r.get(4) : null, r.size() > 6 ? r.get(6) : listCode));
        }
        boolean changed = load(listCode, list, "upload by " + actor.id());
        audit.record(actor, "AML_LIST_UPLOADED", "SANCTIONS_LIST", listCode, Map.of("entries", list.size()));
        if (changed) {
            reloadIndex();
            rescreenEveryone();
        }
        return list.size();
    }

    private boolean load(String listCode, List<IntegrationSpis.SanctionsEntry> list, String source) {
        StringBuilder sig = new StringBuilder();
        list.stream().sorted(Comparator.comparing(IntegrationSpis.SanctionsEntry::externalId))
                .forEach(e -> sig.append(e.externalId()).append('|').append(e.primaryName()).append('|').append(e.aliases()).append('\n'));
        String sha = ChainHasher.sha256(sig.toString());
        Optional<SanctionsListVersion> last = versions.findTopByListCodeAndStatusOrderByFetchedAtDesc(listCode, "LOADED");
        if (last.isPresent() && last.get().getSha256().equals(sha)) return false;
        if (list.isEmpty()) throw new IllegalStateException(listCode + " list came back empty; keeping the previous version");

        newTx.executeWithoutResult(s -> {
            Map<String, SanctionsEntry> existing = new HashMap<>();
            for (SanctionsEntry e : entries.findByListCode(listCode)) existing.put(e.getExternalId(), e);
            Set<String> seen = new HashSet<>();
            for (IntegrationSpis.SanctionsEntry in : list) {
                seen.add(in.externalId());
                SanctionsEntry e = existing.getOrDefault(in.externalId(), new SanctionsEntry());
                e.setListCode(listCode);
                e.setExternalId(in.externalId());
                e.setEntryType(in.entryType());
                e.setPrimaryName(trim(in.primaryName(), 400));
                e.setNormalisedName(trim(NameMatcher.normalise(in.primaryName()), 400));
                e.setAliases(in.aliases() == null ? null : String.join("|", in.aliases()));
                e.setNationality(trim(in.nationality(), 200));
                e.setDateOfBirth(trim(in.dateOfBirth(), 100));
                e.setProgramme(trim(in.programme(), 200));
                e.setActive(true);
                e.setListVersion(sha);
                entries.save(e);
            }
            for (SanctionsEntry e : existing.values()) {
                if (!seen.contains(e.getExternalId()) && e.isActive()) { e.setActive(false); entries.save(e); }
            }
            SanctionsListVersion v = new SanctionsListVersion();
            v.setListCode(listCode);
            v.setSha256(sha);
            v.setEntryCount(list.size());
            v.setSource(trim(source, 300));
            v.setStatus("LOADED");
            v.setFetchedAt(LocalDateTime.now());
            versions.save(v);
        });
        log.info("[AML] {} list loaded: {} entries (version {})", listCode, list.size(), sha.substring(0, 12));
        return true;
    }

    /** Re-screens all customers, agents and merchants against the current lists. */
    public void rescreenEveryone() {
        int hits = 0;
        for (CustomerProfile c : customers.findAll()) {
            RiskPorts.ScreeningOutcome o = screen("CUSTOMER", c.getCustomerRef(), c.getFirstName() + " " + c.getLastName(),
                    c.getDateOfBirth() != null ? c.getDateOfBirth().toString() : null, "ZW");
            if (!"CLEAR".equals(o.status())) hits++;
        }
        for (Agent a : agents.findAll()) {
            if (!"CLEAR".equals(screen("AGENT", a.getAgentRef(), a.getContactName() != null ? a.getContactName() : a.getBusinessName(), null, "ZW").status())) hits++;
        }
        for (LegacyGateway.MerchantSnapshot m : legacy.allMerchants()) {
            if (!"CLEAR".equals(screen("MERCHANT", String.valueOf(m.id()), m.businessName(), null, "ZW").status())) hits++;
        }
        log.info("[AML] Re-screening complete: {} potential matches (alerts raised for new ones)", hits);
    }

    public List<SanctionsListVersion> listVersions() {
        return versions.findTop50ByOrderByFetchedAtDesc();
    }

    private static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
