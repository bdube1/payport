package zw.co.payport.gateway.merchant;

/**
 * Test-mode ("sandbox") outcomes chosen by the customer phone number, so a merchant can
 * exercise every branch of an integration without real money. Only used for sessions created
 * with a TEST key; live sessions never look at this.
 *
 *   ...0000001  declined: "Insufficient funds" (as a provider would word it)
 *   ...0000002  declined: "Transaction cancelled by the customer"
 *   anything else succeeds
 */
public final class SandboxScenarios {

    public record Outcome(boolean success, String providerMessage) {}

    private SandboxScenarios() {}

    public static Outcome forPhone(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        if (digits.endsWith("0000001")) return new Outcome(false, "Insufficient funds");
        if (digits.endsWith("0000002")) return new Outcome(false, "Transaction cancelled by the customer");
        return new Outcome(true, null);
    }
}
