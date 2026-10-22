package zw.co.payport.gateway.integration;

/**
 * Thrown BEFORE anything is sent to a provider that is suspended by operations or whose circuit
 * breaker is open. Nothing was charged or delivered, so the caller can safely tell the customer
 * to try again later or use another method.
 */
public class ProviderUnavailableException extends IllegalStateException {
    private final String providerCode;

    public ProviderUnavailableException(String providerCode, String message) {
        super(message);
        this.providerCode = providerCode;
    }

    public String getProviderCode() { return providerCode; }
}
