package zw.co.payport.gateway.model.request;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.Map;

/**
 * Request to initiate a new transaction.
 * The reference is PROVIDED BY THE CLIENT (frontend generates it).
 * The gateway VALIDATES but NEVER generates the reference.
 */
@Data
public class InitiateTransactionRequest {

    // PP-{CHANNEL}-{YYYYMMDD}-{SEQ6} e.g. PP-WEB-20260315-000042
    @NotBlank(message = "Reference is required")
    @Pattern(regexp = "^PP-[A-Z]{2,10}-\\d{8}-\\d{6}$",
            message = "Invalid reference format. Expected: PP-{CHANNEL}-{YYYYMMDD}-{SEQ6}")
    private String reference;

    @NotBlank(message = "Product type code is required")
    private String productTypeCode;

    @NotBlank(message = "Recipient identifier is required")
    private String recipientIdentifier;  // phone, meter number, account number

    // Amount in CENTS - $5.00 = 500
    @NotNull(message = "Amount is required")
    @Min(value = 1, message = "Amount must be positive")
    private Long amountCents;

    @Size(max = 3)
    private String currency = "USD";

    @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "Customer phone must be in international (E.164) format, e.g. +263771234567")
    private String customerPhone;  // optional - for SMS receipt

    @Email(message = "Enter a valid email")
    private String customerEmail;  // optional - for email receipt

    private String channel = "WEB";

    /**
     * Product-specific configuration for Nyaradzo and other dynamic products
     * For Nyaradzo, this contains:
     *   - noOfMonths: number of months to pay (0-3)
     *   - monthlyPremiumCents: monthly premium amount in cents
     *   - balanceCents: outstanding balance in cents
     *   - holderName: policy holder name
     *   - currency: currency code (ZWG or USD)
     *   - sourcePhone: source phone number
     */
    private Map<String, Object> productConfig;

    /**
     * The app sends productConfig as a JSON string ("{\"bundleCode\":\"X\"}") and the website and the session channels
     * as an object: both are read into the same map.
     */
    @com.fasterxml.jackson.annotation.JsonSetter("productConfig")
    @SuppressWarnings("unchecked")
    public void readProductConfig(Object value) {
        if (value == null) { this.productConfig = null; return; }
        if (value instanceof Map<?, ?> m) { this.productConfig = (Map<String, Object>) m; return; }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) { this.productConfig = null; return; }
        try {
            this.productConfig = new com.fasterxml.jackson.databind.ObjectMapper().readValue(s, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("productConfig is not valid JSON");
        }
    }
}
