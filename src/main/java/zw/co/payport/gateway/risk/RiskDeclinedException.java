package zw.co.payport.gateway.risk;

/**
 * A payment refused by an enforced risk rule. The customer-facing message is deliberately neutral
 * (no "tipping off": it never says which rule or list), and quotes a reference for support.
 */
public class RiskDeclinedException extends RuntimeException {
    public RiskDeclinedException(String reference) {
        super("This payment cannot be processed. Please contact Payport support quoting reference " + reference + ".");
    }
}
