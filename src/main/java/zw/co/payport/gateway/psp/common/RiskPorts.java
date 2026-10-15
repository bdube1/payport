package zw.co.payport.gateway.psp.common;

import java.util.List;

/**
 * Interfaces the payment, wallet, agent and KYC modules use to consult the
 * risk & compliance modules, without depending on their implementation.
 */
public final class RiskPorts {
    private RiskPorts() {}

    /** Real-time fraud decision before money moves. Implemented by risk.FraudEngine. */
    public interface RiskAssessor {
        RiskDecision assess(RiskContext ctx);
    }

    /**
     * @param type        P2P, CASH_IN, CASH_OUT, WALLET_MERCHANT_PAYMENT, WITHDRAWAL, CHECKOUT, FX, PAYOUT
     * @param subjectRef  customer ref / msisdn / merchant id doing the debit
     */
    public record RiskContext(String type, String channel, String subjectType, String subjectRef, String counterpartyRef,
                              Long merchantId, Long agentId, String currency, long amountMinor, String msisdn,
                              String deviceId, String ipAddress) {}

    /** action: ALLOW | REVIEW | BLOCK */
    public record RiskDecision(String action, int score, List<String> reasons, String caseRef) {
        public static RiskDecision allow() { return new RiskDecision("ALLOW", 0, List.of(), null); }
        public boolean blocked() { return "BLOCK".equals(action); }
    }

    /** Sanctions / PEP screening of a person or business. Implemented by risk.AmlScreeningService. */
    public interface Screener {
        ScreeningOutcome screen(String subjectType, String subjectRef, String fullName, String dateOfBirth, String nationality);
    }

    /** status: CLEAR | POTENTIAL_MATCH | CONFIRMED_MATCH */
    public record ScreeningOutcome(String status, int bestScore, String bestMatch, String alertRef) {
        public static ScreeningOutcome clear() { return new ScreeningOutcome("CLEAR", 0, null, null); }
    }
}
