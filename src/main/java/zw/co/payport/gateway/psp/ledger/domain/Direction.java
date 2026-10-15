package zw.co.payport.gateway.psp.ledger.domain;

/** Side of a journal line. Every journal entry has equal DEBIT and CREDIT totals. */
public enum Direction {
    DEBIT,
    CREDIT;

    public Direction opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
