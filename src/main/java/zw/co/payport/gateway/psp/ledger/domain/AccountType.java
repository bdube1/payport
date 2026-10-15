package zw.co.payport.gateway.psp.ledger.domain;

/**
 * Standard accounting classes. The normal side decides which direction
 * increases the account's balance:
 * assets and expenses grow with debits; liabilities, equity and revenue grow with credits.
 */
public enum AccountType {
    ASSET(Direction.DEBIT),
    EXPENSE(Direction.DEBIT),
    LIABILITY(Direction.CREDIT),
    EQUITY(Direction.CREDIT),
    REVENUE(Direction.CREDIT);

    private final Direction normalSide;

    AccountType(Direction normalSide) {
        this.normalSide = normalSide;
    }

    public Direction normalSide() {
        return normalSide;
    }

    /**
     * Balance expressed in the account's normal direction: a positive number
     * means "the account holds value the way it is supposed to".
     */
    public long balanceOf(long totalDebits, long totalCredits) {
        return normalSide == Direction.DEBIT
                ? Math.subtractExact(totalDebits, totalCredits)
                : Math.subtractExact(totalCredits, totalDebits);
    }
}
