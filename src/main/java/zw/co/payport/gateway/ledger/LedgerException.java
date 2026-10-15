package zw.co.payport.gateway.ledger;

/**
 * A ledger rule refused the operation. code is the stable prefix raised by the database
 * procedures (LEDGER_UNBALANCED, LEDGER_INSUFFICIENT_FUNDS, ...) or LEDGER_ERROR.
 */
public class LedgerException extends RuntimeException {

    private final String code;

    public LedgerException(String code, String message, Throwable cause) {
        super(code + ": " + message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** Parses "LEDGER_X: message" as raised by SIGNAL in V34. */
    static LedgerException fromDatabaseMessage(String message, Throwable cause) {
        if (message != null) {
            int start = message.indexOf("LEDGER_");
            if (start >= 0) {
                String rest = message.substring(start);
                int colon = rest.indexOf(':');
                if (colon > 0) {
                    return new LedgerException(rest.substring(0, colon).trim(), rest.substring(colon + 1).trim(), cause);
                }
            }
        }
        return new LedgerException("LEDGER_ERROR", message == null ? "database error" : message, cause);
    }
}
