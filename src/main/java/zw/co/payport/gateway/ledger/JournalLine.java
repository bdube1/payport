package zw.co.payport.gateway.ledger;

import java.util.Objects;

/** One debit or credit, in integer minor units of the account's currency. */
public record JournalLine(AccountKey account, Direction direction, long amountMinor, String memo) {

    public JournalLine {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(direction, "direction");
        if (amountMinor <= 0) throw new IllegalArgumentException("amount must be positive minor units: " + amountMinor);
    }

    public static JournalLine debit(AccountKey account, long amountMinor, String memo) {
        return new JournalLine(account, Direction.DEBIT, amountMinor, memo);
    }

    public static JournalLine credit(AccountKey account, long amountMinor, String memo) {
        return new JournalLine(account, Direction.CREDIT, amountMinor, memo);
    }
}
