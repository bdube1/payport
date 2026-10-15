package zw.co.payport.gateway.psp.ledger.domain;

/** Raised when a posting would break a ledger invariant. Never caught-and-ignored. */
public class LedgerException extends RuntimeException {
    public LedgerException(String message) {
        super(message);
    }
}
