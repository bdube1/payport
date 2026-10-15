package zw.co.payport.gateway.psp.ledger.domain;

/** A posting would take a non-negative account (e.g. MERCHANT_AVAILABLE) below zero. */
public class InsufficientFundsException extends LedgerException {
    private final String accountCode;
    private final long balanceMinor;
    private final long requiredMinor;

    public InsufficientFundsException(String accountCode, long balanceMinor, long requiredMinor) {
        super("Insufficient funds in " + accountCode + ": balance " + balanceMinor + ", posting needs " + requiredMinor);
        this.accountCode = accountCode;
        this.balanceMinor = balanceMinor;
        this.requiredMinor = requiredMinor;
    }

    public String accountCode() { return accountCode; }
    public long balanceMinor() { return balanceMinor; }
    public long requiredMinor() { return requiredMinor; }
}
