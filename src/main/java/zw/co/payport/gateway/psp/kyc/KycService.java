package zw.co.payport.gateway.psp.kyc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.common.Refs;
import zw.co.payport.gateway.psp.common.RiskPorts;
import zw.co.payport.gateway.psp.integration.IdentityAndBureauAdapters;
import zw.co.payport.gateway.psp.integration.IntegrationSpis;
import zw.co.payport.gateway.psp.ledger.domain.AccountKey;
import zw.co.payport.gateway.psp.platform.approval.ApprovalRequest;
import zw.co.payport.gateway.psp.platform.approval.ApprovalService;
import zw.co.payport.gateway.psp.platform.audit.AuditService;
import zw.co.payport.gateway.psp.platform.crypto.FieldCrypto;
import zw.co.payport.gateway.psp.platform.outbox.EventTypes;
import zw.co.payport.gateway.psp.platform.outbox.OutboxService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Customer onboarding and tiered KYC for wallet customers.
 *
 *   TIER1  phone number + name (self-registered, USSD/app)      low limits
 *   TIER2  national ID verified electronically or by document   standard limits
 *   TIER3  + proof of residence, reviewed by compliance          high limits
 *
 * Tier limits live in psp_kyc_tier_limits and must be set to the limits in
 * your RBZ licence conditions. Every registration and ID verification is
 * screened against sanctions/PEP lists; a confirmed match freezes the profile.
 */
@Service
public class KycService {

    public static final String APPROVE_TIER3 = "KYC_TIER3";
    public static final String APPROVE_UNFREEZE = "CUSTOMER_UNFREEZE";

    private final CustomerProfileRepository customers;
    private final KycTierLimitRepository limits;
    private final IdentityAndBureauAdapters.Registry identity;
    private final RiskPorts.Screener screener;
    private final FieldCrypto crypto;
    private final ApprovalService approvals;
    private final AuditService audit;
    private final OutboxService outbox;
    private final ObjectMapper om;

    public KycService(CustomerProfileRepository customers, KycTierLimitRepository limits, IdentityAndBureauAdapters.Registry identity,
                      RiskPorts.Screener screener, FieldCrypto crypto, ApprovalService approvals, AuditService audit,
                      OutboxService outbox, ObjectMapper om) {
        this.customers = customers;
        this.limits = limits;
        this.identity = identity;
        this.screener = screener;
        this.crypto = crypto;
        this.approvals = approvals;
        this.audit = audit;
        this.outbox = outbox;
        this.om = om;
    }

    public static String normaliseMsisdn(String msisdn) {
        if (msisdn == null) throw PspException.badRequest("MSISDN_REQUIRED", "Phone number is required");
        String d = msisdn.replaceAll("\\D", "");
        if (d.startsWith("0") && d.length() == 10) d = "263" + d.substring(1);
        if (!d.matches("2637\\d{8}")) throw PspException.badRequest("INVALID_MSISDN", "Use a Zimbabwean mobile number, e.g. +263771234567");
        return "+" + d;
    }

    @Transactional
    public CustomerProfile register(String msisdn, String firstName, String lastName, LocalDate dob, Long userId, Actor actor) {
        String m = normaliseMsisdn(msisdn);
        if (customers.findByMsisdn(m).isPresent()) throw PspException.conflict("ALREADY_REGISTERED", "This number already has a wallet");
        if (firstName == null || firstName.isBlank() || lastName == null || lastName.isBlank())
            throw PspException.badRequest("NAME_REQUIRED", "First and last name are required");
        CustomerProfile c = new CustomerProfile();
        c.setCustomerRef(Refs.newRef("cus"));
        c.setMsisdn(m);
        c.setUserId(userId);
        c.setFirstName(firstName.trim());
        c.setLastName(lastName.trim());
        c.setDateOfBirth(dob);
        c.setKycTier("TIER1");
        c.setPep(false);
        c.setSanctionsStatus("CLEAR");
        c.setRiskRating("LOW");
        c.setStatus("ACTIVE");
        customers.save(c);
        applyScreening(c, actor);
        audit.record(actor, "CUSTOMER_REGISTERED", "CUSTOMER", c.getCustomerRef(), Map.of("tier", "TIER1"));
        return c;
    }

    /** Electronic ID check. MATCH upgrades to TIER2; anything else leaves the customer at TIER1 for document review. */
    @Transactional
    public CustomerProfile verifyNationalId(String customerRef, String nationalId, Actor actor) {
        CustomerProfile c = byRef(customerRef);
        String index = crypto.blindIndex(nationalId);
        customers.findByNationalIdIndex(index).filter(o -> !o.getId().equals(c.getId())).ifPresent(o -> {
            throw PspException.conflict("ID_ALREADY_USED", "This national ID is registered to another wallet");
        });
        IntegrationSpis.IdCheckResult r = identity.verifier().verify(
                new IntegrationSpis.IdCheckRequest(nationalId, c.getFirstName(), c.getLastName(), c.getDateOfBirth()));
        c.setNationalId(nationalId.trim().toUpperCase());
        c.setNationalIdIndex(index);
        c.setIdVerificationResult(r.result());
        c.setIdVerificationRef(r.providerRef());
        if ("MATCH".equals(r.result()) && "TIER1".equals(c.getKycTier())) {
            c.setKycTier("TIER2");
            c.setIdVerifiedAt(LocalDateTime.now());
            outbox.publish(EventTypes.KYC_TIER_CHANGED, "CUSTOMER", c.getCustomerRef(), Map.of("customerRef", c.getCustomerRef(), "tier", "TIER2"));
        }
        customers.save(c);
        applyScreening(c, actor);
        audit.record(actor, "CUSTOMER_ID_CHECK", "CUSTOMER", customerRef, Map.of("result", r.result(), "provider", identity.verifier().code()));
        return c;
    }

    /** Compliance confirms an ID from documents when the electronic check was unavailable / partial. */
    @Transactional
    public CustomerProfile confirmIdManually(String customerRef, String note, Actor actor) {
        if (!actor.hasAnyRole("COMPLIANCE", "ADMIN")) throw PspException.forbidden("COMPLIANCE_ONLY", "Compliance role required");
        CustomerProfile c = byRef(customerRef);
        if (c.getNationalIdIndex() == null) throw PspException.unprocessable("NO_ID", "Capture the national ID first");
        c.setIdVerificationResult("MANUAL_MATCH");
        c.setIdVerifiedAt(LocalDateTime.now());
        if ("TIER1".equals(c.getKycTier())) c.setKycTier("TIER2");
        customers.save(c);
        audit.record(actor, "CUSTOMER_ID_MANUAL", "CUSTOMER", customerRef, Map.of("note", String.valueOf(note)));
        return c;
    }

    @Transactional
    public ApprovalRequest requestTier3(String customerRef, String proofOfResidenceRef, Actor actor) {
        CustomerProfile c = byRef(customerRef);
        if (!"TIER2".equals(c.getKycTier())) throw PspException.unprocessable("TIER2_REQUIRED", "Customer must be TIER2 first");
        return approvals.request(APPROVE_TIER3, customerRef, json(Map.of("customerRef", customerRef, "proof", proofOfResidenceRef)),
                "Upgrade " + customerRef + " to TIER3 (proof of residence " + proofOfResidenceRef + ")", null, actor);
    }

    /** Freezing is protective and immediate; unfreezing needs four-eyes. */
    @Transactional
    public CustomerProfile freeze(String customerRef, String reason, Actor actor) {
        CustomerProfile c = byRef(customerRef);
        c.setStatus("FROZEN");
        c.setStatusReason(reason);
        customers.save(c);
        audit.record(actor, "CUSTOMER_FROZEN", "CUSTOMER", customerRef, Map.of("reason", String.valueOf(reason)));
        return c;
    }

    @Transactional
    public ApprovalRequest requestUnfreeze(String customerRef, String reason, Actor actor) {
        CustomerProfile c = byRef(customerRef);
        if (!"FROZEN".equals(c.getStatus())) throw PspException.conflict("NOT_FROZEN", "Customer is " + c.getStatus());
        if ("CONFIRMED_MATCH".equals(c.getSanctionsStatus())) throw PspException.forbidden("SANCTIONED", "Sanctioned customers cannot be unfrozen");
        return approvals.request(APPROVE_UNFREEZE, customerRef, json(Map.of("customerRef", customerRef)),
                "Unfreeze customer " + customerRef + " (frozen for: " + c.getStatusReason() + ")", reason, actor);
    }

    private void applyScreening(CustomerProfile c, Actor actor) {
        RiskPorts.ScreeningOutcome s = screener.screen("CUSTOMER", c.getCustomerRef(), c.getFirstName() + " " + c.getLastName(),
                c.getDateOfBirth() != null ? c.getDateOfBirth().toString() : null, "ZW");
        c.setSanctionsStatus(s.status());
        if ("CONFIRMED_MATCH".equals(s.status())) {
            c.setStatus("FROZEN");
            c.setStatusReason("Sanctions match " + s.alertRef());
        } else if ("POTENTIAL_MATCH".equals(s.status())) {
            c.setRiskRating("HIGH");
        }
        customers.save(c);
    }

    // ── Limits ───────────────────────────────────────────────────────────

    public KycTierLimit tierLimit(String tier, String currency) {
        return limits.findByKycTierAndCurrency(tier, AccountKey.normaliseCurrency(currency))
                .orElseThrow(() -> PspException.unavailable("TIER_LIMITS_MISSING", "No limits configured for " + tier + " " + currency));
    }

    public CustomerProfile requireActive(String msisdnOrRef) {
        CustomerProfile c = msisdnOrRef.startsWith("cus_") || msisdnOrRef.startsWith("CUS_")
                ? byRef(msisdnOrRef)
                : customers.findByMsisdn(normaliseMsisdn(msisdnOrRef)).orElseThrow(() -> PspException.notFound("Wallet for", msisdnOrRef));
        if (!"ACTIVE".equals(c.getStatus())) throw PspException.forbidden("CUSTOMER_" + c.getStatus(), "This wallet is " + c.getStatus().toLowerCase());
        if ("CONFIRMED_MATCH".equals(c.getSanctionsStatus())) throw PspException.forbidden("CUSTOMER_BLOCKED", "This wallet is blocked");
        return c;
    }

    public CustomerProfile byRef(String ref) {
        return customers.findByCustomerRef(ref).orElseThrow(() -> PspException.notFound("Customer", ref));
    }

    public java.util.Optional<CustomerProfile> byMsisdn(String msisdn) {
        return customers.findByMsisdn(normaliseMsisdn(msisdn));
    }

    private String json(Object o) {
        try { return om.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
