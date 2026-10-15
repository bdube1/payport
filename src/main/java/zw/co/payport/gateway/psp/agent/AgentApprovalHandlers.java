package zw.co.payport.gateway.psp.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.ledger.LedgerService;
import zw.co.payport.gateway.psp.ledger.domain.WalletPostingRules;
import zw.co.payport.gateway.psp.platform.approval.ApprovalHandler;

public final class AgentApprovalHandlers {
    private AgentApprovalHandlers() {}

    static JsonNode parse(ObjectMapper om, String s) {
        try { return om.readTree(s); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Component
    public static class Activate implements ApprovalHandler {
        private final AgentRepository agents;
        public Activate(AgentRepository agents) { this.agents = agents; }
        @Override public String actionType() { return AgentService.APPROVE_ACTIVATE; }
        @Override public String requiredRole() { return "COMPLIANCE"; }
        @Override public String execute(String agentRef, String payload, String requestedBy, String approvedBy) {
            Agent a = agents.findByAgentRef(agentRef).orElseThrow(() -> PspException.notFound("Agent", agentRef));
            a.setStatus("ACTIVE");
            a.setKycVerified(true);
            a.setApprovedBy(approvedBy);
            agents.save(a);
            return "Agent " + a.getAgentCode() + " active";
        }
    }

    @Component
    public static class Limits implements ApprovalHandler {
        private final AgentRepository agents;
        private final ObjectMapper om;
        public Limits(AgentRepository agents, ObjectMapper om) { this.agents = agents; this.om = om; }
        @Override public String actionType() { return AgentService.APPROVE_LIMITS; }
        @Override public String requiredRole() { return "COMPLIANCE"; }
        @Override public String execute(String agentRef, String payload, String requestedBy, String approvedBy) {
            JsonNode n = parse(om, payload);
            Agent a = agents.findByAgentRef(agentRef).orElseThrow(() -> PspException.notFound("Agent", agentRef));
            a.setPerTxnLimitMinor(n.path("perTxn").asLong());
            a.setDailyCashInLimitMinor(n.path("dailyIn").asLong());
            a.setDailyCashOutLimitMinor(n.path("dailyOut").asLong());
            a.setMaxFloatMinor(n.path("maxFloat").asLong());
            agents.save(a);
            return "Limits updated";
        }
    }

    @Component
    public static class FloatPurchase implements ApprovalHandler {
        private final LedgerService ledger;
        private final ObjectMapper om;
        public FloatPurchase(LedgerService ledger, ObjectMapper om) { this.ledger = ledger; this.om = om; }
        @Override public String actionType() { return AgentService.APPROVE_FLOAT_PURCHASE; }
        @Override public String requiredRole() { return "FINANCE"; }
        @Override public String execute(String target, String payload, String requestedBy, String approvedBy) {
            JsonNode n = parse(om, payload);
            LedgerService.PostedEntry e = ledger.post(WalletPostingRules.agentFloatPurchase(n.path("depositRef").asText(),
                    n.path("agentId").asLong(), n.path("currency").asText(), n.path("amountMinor").asLong(),
                    n.path("cashAccount").asText()), approvedBy);
            return "Float credited " + e.entryRef();
        }
    }

    @Component
    public static class FloatRedemption implements ApprovalHandler {
        private final LedgerService ledger;
        private final ObjectMapper om;
        public FloatRedemption(LedgerService ledger, ObjectMapper om) { this.ledger = ledger; this.om = om; }
        @Override public String actionType() { return AgentService.APPROVE_FLOAT_REDEMPTION; }
        @Override public String requiredRole() { return "FINANCE"; }
        @Override public String execute(String target, String payload, String requestedBy, String approvedBy) {
            JsonNode n = parse(om, payload);
            LedgerService.PostedEntry e = ledger.post(WalletPostingRules.agentFloatRedemption(n.path("redemptionRef").asText(),
                    n.path("agentId").asLong(), n.path("currency").asText(), n.path("amountMinor").asLong(),
                    n.path("cashAccount").asText()), approvedBy);
            return "Float redeemed " + e.entryRef() + "; pay the agent from the trust account";
        }
    }
}
