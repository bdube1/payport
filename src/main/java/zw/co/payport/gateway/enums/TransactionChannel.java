package zw.co.payport.gateway.enums;

public enum TransactionChannel {
    WEB, MOB, USD, API;

    public static TransactionChannel fromString(String value) {
        if (value == null) return WEB;
        try {
            return TransactionChannel.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return WEB;
        }
    }
}
