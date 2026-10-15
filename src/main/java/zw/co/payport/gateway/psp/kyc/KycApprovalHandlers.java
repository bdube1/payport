package zw.co.payport.gateway.psp.kyc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.platform.approval.ApprovalHandler;

public final class KycApprovalHandlers {
    private KycApprovalHandlers() {}

    static JsonNode parse(ObjectMapper om, String s) {
        try { return om.readTree(s); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Component
    public static class Tier3 implements ApprovalHandler {
        private final CustomerProfileRepository customers;
        private final ObjectMapper om;
        public Tier3(CustomerProfileRepository customers, ObjectMapper om) { this.customers = customers; this.om = om; }
        @Override public String actionType() { return KycService.APPROVE_TIER3; }
        @Override public String requiredRole() { return "COMPLIANCE"; }
        @Override public String execute(String ref, String payload, String requestedBy, String approvedBy) {
            CustomerProfile c = customers.findByCustomerRef(ref).orElseThrow(() -> PspException.notFound("Customer", ref));
            c.setKycTier("TIER3");
            c.setProofOfResidenceRef(parse(om, payload).path("proof").asText());
            customers.save(c);
            return ref + " is TIER3";
        }
    }

    @Component
    public static class Unfreeze implements ApprovalHandler {
        private final CustomerProfileRepository customers;
        public Unfreeze(CustomerProfileRepository customers) { this.customers = customers; }
        @Override public String actionType() { return KycService.APPROVE_UNFREEZE; }
        @Override public String requiredRole() { return "COMPLIANCE"; }
        @Override public String execute(String ref, String payload, String requestedBy, String approvedBy) {
            CustomerProfile c = customers.findByCustomerRef(ref).orElseThrow(() -> PspException.notFound("Customer", ref));
            if ("CONFIRMED_MATCH".equals(c.getSanctionsStatus())) throw PspException.forbidden("SANCTIONED", "Sanctioned customers cannot be unfrozen");
            c.setStatus("ACTIVE");
            c.setStatusReason("Unfrozen by " + approvedBy);
            customers.save(c);
            return ref + " unfrozen";
        }
    }
}
