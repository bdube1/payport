package zw.co.payport.gateway.psp.merchant;

/**
 * Default fund-protection policy per merchant risk class. These are
 * starting points for your compliance team to confirm, not regulatory
 * figures: individual merchants can be moved off them through the
 * four-eyes funds-policy request.
 */
public record RiskPolicy(int holdDays, int reserveBps, int reserveDays, int kycReviewMonths) {

    public static RiskPolicy defaultsFor(String riskClass) {
        return switch (riskClass) {
            case "LOW" -> new RiskPolicy(1, 0, 0, 36);
            case "MEDIUM" -> new RiskPolicy(2, 500, 60, 24);
            case "HIGH" -> new RiskPolicy(7, 1000, 120, 12);
            default -> new RiskPolicy(30, 10000, 180, 6); // PROHIBITED: nothing released without review
        };
    }
}
