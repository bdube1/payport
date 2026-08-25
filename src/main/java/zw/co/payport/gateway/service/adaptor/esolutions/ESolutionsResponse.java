package zw.co.payport.gateway.service.adaptor.esolutions;

import java.util.Map;

/**
 * Strongly-typed wrapper around the raw e-Solutions JSON response map.
 *
 * Response codes (from spec):
 *   00  = SUCCESS (the ONLY success code)
 *   09  = Still in progress (retry with resend)
 *   05  = General error
 *   12  = Amount below/above limit or cannot cover debt
 *   14  = Invalid meter / blocked meter
 *   51  = Insufficient vendor funds
 *   57  = Reversal in progress on customer account
 *   63  = Auth failure
 *   68  = Timeout (treat as failure — DO NOT retry without resend first for ZESA)
 *   80  = Currency code mismatch
 *   94  = Duplicate transaction
 */
public class ESolutionsResponse {

    private final Map<String, Object> data;
    private final String rawJson;

    public ESolutionsResponse(Map<String, Object> data, String rawJson) {
        this.data    = data;
        this.rawJson = rawJson;
    }

    // ─── Core fields ──────────────────────────────────────────────────────────

    public String getMti()               { return str("mti"); }
    public String getResponseCode()      { return str("responseCode"); }
    public String getTransactionRef()    { return str("transactionReference"); }
    public String getVendorReference()   { return str("vendorReference"); }
    public String getNarrative()         { return str("narrative"); }
    public String getMerchantName()      { return str("merchantName"); }
    public String getProductName()       { return str("productName"); }
    public String getUtilityAccount()    { return str("utilityAccount"); }
    public String getCurrencyCode()      { return str("currencyCode"); }
    public String getSettlementCurrency(){ return str("settlementCurrencyCode"); }
    public String getReceiptNumber()     { return str("receiptNumber"); }

    // Customer info fields
    public String getCustomerData()      { return str("customerData"); }
    public String getCustomerAddress()   { return str("customerAddress"); }

    // Token / delivery fields
    public String getToken()             { return str("token"); }
    public String getFixedCharges()      { return str("fixedCharges"); }
    public String getArrears()           { return str("arrears"); }
    public String getMiscellaneousData() { return str("miscellaneousData"); }
    public String getPaymentType()       { return str("paymentType"); }

    // Vendor balance fields (for balance enquiry)
    public String getVendorBalance()     { return str("vendorBalance"); }

    // ─── Status helpers ───────────────────────────────────────────────────────

    /** Returns true ONLY for response code "00" — spec is explicit about this */
    public boolean isSuccess() {
        return "00".equals(getResponseCode());
    }

    /** Response code 09 = still in progress, must resend later */
    public boolean isInProgress() {
        return "09".equals(getResponseCode());
    }

    /** Duplicate transaction */
    public boolean isDuplicate() {
        return "94".equals(getResponseCode());
    }

    public String getRawJson() { return rawJson; }

    /**
     * Build a human-readable failure message combining response code + narrative.
     * Spec says: "Vendors' vending systems should display the given narratives as they are"
     */
    public String getFailureMessage() {
        String rc   = getResponseCode();
        String narr = getNarrative();
        if (narr != null && !narr.isBlank()) return "[" + rc + "] " + narr;
        return switch (rc != null ? rc : "") {
            case "05" -> "General processing error";
            case "09" -> "Transaction still in progress";
            case "12" -> "Amount cannot cover debt or is outside limits";
            case "14" -> "Invalid or blocked meter/account number";
            case "51" -> "Insufficient vendor funds";
            case "57" -> "Customer account has a reversal in progress";
            case "63" -> "Authentication failure";
            case "68" -> "Transaction timed out";
            case "80" -> "Currency code mismatch";
            case "94" -> "Duplicate transaction";
            default   -> "Transaction failed (code: " + rc + ")";
        };
    }

    /**
     * Parse vendor balance from balance enquiry response
     * Format: "accountName|accountNumber|currency|balance"
     * Balance is in cents
     *
     * @return VendorBalance object or null if not present
     */
    public VendorBalance parseVendorBalance() {
        String vendorBalance = getVendorBalance();
        if (vendorBalance == null || vendorBalance.isBlank()) {
            return null;
        }
        String[] parts = vendorBalance.split("\\|");
        if (parts.length >= 4) {
            return new VendorBalance(
                    parts[0].trim(),                    // account name
                    parts[1].trim(),                    // account number
                    parts[2].trim(),                    // currency
                    safeLong(parts[3])                  // balance in cents
            );
        }
        return null;
    }

    // ─── ZESA token parsing ───────────────────────────────────────────────────

    /**
     * Parse token field into structured ZesaToken objects.
     * Token format per spec: token|units|rate|merchantReceiptNumber|netAmount|taxAmount|taxRate
     * Multiple tokens separated by '#' (e.g. KCT + credit token = 3 tokens)
     *
     * CRITICAL: Order of tokens matters. Customer must load in order given.
     *           First 2 of 3 tokens are KCTs, last is credit token.
     */
    public java.util.List<ZesaToken> parseTokens() {
        String tokenField = getToken();
        if (tokenField == null || tokenField.isBlank()) return java.util.List.of();
        java.util.List<ZesaToken> result = new java.util.ArrayList<>();
        for (String record : tokenField.split("#")) {
            String[] parts = record.trim().split("\\|", -1);
            result.add(new ZesaToken(
                    parts.length > 0 ? parts[0].trim() : "",   // token number
                    parts.length > 1 ? parts[1].trim() : "",   // units (kWh / kL)
                    parts.length > 2 ? parts[2].trim() : "",   // rate description
                    parts.length > 3 ? parts[3].trim() : "",   // merchant receipt number
                    parts.length > 4 ? safeLong(parts[4])  : 0L,  // net amount cents
                    parts.length > 5 ? safeLong(parts[5])  : 0L,  // tax amount cents
                    parts.length > 6 ? parts[6].trim() : ""    // tax rate e.g. "15%"
            ));
        }
        return result;
    }

    /** Parse arrears field: description|receiptNumber|amount|tax|outstandingBalance */
    public java.util.List<Arrear> parseArrears() {
        String arrField = getArrears();
        if (arrField == null || arrField.isBlank()) return java.util.List.of();
        java.util.List<Arrear> result = new java.util.ArrayList<>();
        for (String record : arrField.split("#")) {
            String[] parts = record.trim().split("\\|", -1);
            result.add(new Arrear(
                    parts.length > 0 ? parts[0].trim() : "",
                    parts.length > 1 ? parts[1].trim() : "",
                    parts.length > 2 ? safeLong(parts[2]) : 0L,
                    parts.length > 3 ? safeLong(parts[3]) : 0L,
                    parts.length > 4 ? safeLong(parts[4]) : 0L
            ));
        }
        return result;
    }

    /** Parse fixedCharges: description|receiptNumber|amount|taxAmount|rate */
    public java.util.List<FixedCharge> parseFixedCharges() {
        String fcField = getFixedCharges();
        if (fcField == null || fcField.isBlank()) return java.util.List.of();
        java.util.List<FixedCharge> result = new java.util.ArrayList<>();
        for (String record : fcField.split("#")) {
            String[] parts = record.trim().split("\\|", -1);
            result.add(new FixedCharge(
                    parts.length > 0 ? parts[0].trim() : "",
                    parts.length > 1 ? parts[1].trim() : "",
                    parts.length > 2 ? safeLong(parts[2]) : 0L,
                    parts.length > 3 ? safeLong(parts[3]) : 0L,
                    parts.length > 4 ? parts[4].trim() : ""
            ));
        }
        return result;
    }

    // ─── Inner types ──────────────────────────────────────────────────────────

    public record ZesaToken(
            String tokenNumber,
            String units,
            String rateDescription,
            String merchantReceiptNumber,
            long netAmountCents,
            long taxAmountCents,
            String taxRate
    ) {
        /** Format token number with spaces every 4 digits: 1234 5678 9012 3456 7890 */
        public String getFormattedToken() {
            String digits = tokenNumber.replaceAll("\\s+", "");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digits.length(); i++) {
                if (i > 0 && i % 4 == 0) sb.append(' ');
                sb.append(digits.charAt(i));
            }
            return sb.toString();
        }
    }

    public record Arrear(
            String description,
            String receiptNumber,
            long amountCents,
            long taxCents,
            long outstandingBalanceCents
    ) {}

    public record FixedCharge(
            String description,
            String receiptNumber,
            long amountCents,
            long taxAmountCents,
            String rate
    ) {}

    /**
     * Vendor balance record for balance enquiry response
     * Format: accountName|accountNumber|currency|balance
     */
    public record VendorBalance(
            String accountName,
            String accountNumber,
            String currency,
            long balanceCents
    ) {
        public double getBalance() {
            return balanceCents / 100.0;
        }

        public String getBalanceFormatted() {
            return String.format("%.2f", getBalance());
        }

        @Override
        public String toString() {
            return String.format("%s|%s|%s|%d", accountName, accountNumber, currency, balanceCents);
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String str(String key) {
        Object v = data.get(key);
        return v != null ? v.toString() : null;
    }

    private long safeLong(String s) {
        if (s == null || s.isBlank()) return 0L;
        try { return Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { return 0L; }
    }

    @Override
    public String toString() {
        return "ESolutionsResponse{rc=" + getResponseCode() + ", narrative=" + getNarrative() + "}";
    }
}