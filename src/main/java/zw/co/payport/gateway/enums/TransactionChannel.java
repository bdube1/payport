package zw.co.payport.gateway.enums;

public enum TransactionChannel {
    /** WEB, MOBile app, USD = USSD, merchant API. */
    WEB, MOB, USD, API;

    public static TransactionChannel fromString(String value) {
        if (value == null) return WEB;
        if ("USSD".equalsIgnoreCase(value.trim())) return USD; // USD is the USSD channel (reference prefix PP-USD-...)
        try {
            return TransactionChannel.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return WEB;
        }
    }
}
