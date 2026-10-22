package zw.co.payport.gateway.ledger;

public enum Direction { DEBIT, CREDIT;

    public Direction opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
