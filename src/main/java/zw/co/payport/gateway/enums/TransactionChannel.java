package zw.co.payport.gateway.enums;

public enum TransactionChannel {
    /** WEB, MOBile app, USD = USSD, merchant API; Phase 8: WhatsApp, point of sale, agent. */
    WEB, MOB, USD, API, WHATSAPP, POS, AGENT;

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
