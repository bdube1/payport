package zw.co.payport.gateway.psp.ledger.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An unposted journal entry. Built by {@link PostingRules}, validated here,
 * then persisted atomically by LedgerService.
 *
 * Invariants (checked by {@link #validate()}):
 *  - idempotency key present: the same business event can never post twice
 *  - at least one debit and one credit line
 *  - every amount strictly positive (zero-value lines are dropped when built)
 *  - every line in the entry's currency (no cross-currency entries; FX is two entries via an FX position account)
 *  - total debits == total credits
 */
public final class JournalDraft {

    public record Line(AccountKey account, Direction direction, long amountMinor, String memo) {
        public Line {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(direction, "direction");
        }
    }

    private final EntryType entryType;
    private final String idempotencyKey;
    private final String currency;
    private final String description;
    private final String sourceType;
    private final String sourceRef;
    private final List<Line> lines;

    private JournalDraft(Builder b) {
        this.entryType = b.entryType;
        this.idempotencyKey = b.idempotencyKey;
        this.currency = b.currency;
        this.description = b.description;
        this.sourceType = b.sourceType;
        this.sourceRef = b.sourceRef;
        this.lines = Collections.unmodifiableList(new ArrayList<>(b.lines));
    }

    public static Builder builder(EntryType type, String idempotencyKey, String currency) {
        return new Builder(type, idempotencyKey, currency);
    }

    public JournalDraft validate() {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new LedgerException("Journal entry needs an idempotency key");
        }
        if (idempotencyKey.length() > 190) {
            throw new LedgerException("Idempotency key too long: " + idempotencyKey);
        }
        long debits = 0, credits = 0;
        boolean hasDebit = false, hasCredit = false;
        for (Line l : lines) {
            if (l.amountMinor() <= 0) {
                throw new LedgerException("Line amounts must be positive: " + l);
            }
            if (!currency.equals(l.account().currency())) {
                throw new LedgerException("Line " + l.account() + " is not in entry currency " + currency);
            }
            if (l.direction() == Direction.DEBIT) {
                debits = Math.addExact(debits, l.amountMinor());
                hasDebit = true;
            } else {
                credits = Math.addExact(credits, l.amountMinor());
                hasCredit = true;
            }
        }
        if (!hasDebit || !hasCredit) {
            throw new LedgerException("Entry " + idempotencyKey + " needs at least one debit and one credit");
        }
        if (debits != credits) {
            throw new LedgerException("Entry " + idempotencyKey + " is unbalanced: debits=" + debits + " credits=" + credits);
        }
        return this;
    }

    /** Mirror image of this entry: every debit becomes a credit and vice versa. Used for reversals. */
    public JournalDraft reversal(String reversalKey, String reason) {
        Builder b = builder(EntryType.REVERSAL, reversalKey, currency)
                .description("Reversal of " + idempotencyKey + (reason != null ? ": " + reason : ""))
                .source(sourceType, sourceRef);
        for (Line l : lines) {
            b.line(l.account(), l.direction().opposite(), l.amountMinor(), l.memo());
        }
        return b.build();
    }

    public long totalDebits() {
        return lines.stream().filter(l -> l.direction() == Direction.DEBIT).mapToLong(Line::amountMinor).sum();
    }

    public EntryType entryType() { return entryType; }
    public String idempotencyKey() { return idempotencyKey; }
    public String currency() { return currency; }
    public String description() { return description; }
    public String sourceType() { return sourceType; }
    public String sourceRef() { return sourceRef; }
    public List<Line> lines() { return lines; }

    public static final class Builder {
        private final EntryType entryType;
        private final String idempotencyKey;
        private final String currency;
        private String description;
        private String sourceType;
        private String sourceRef;
        private final List<Line> lines = new ArrayList<>();

        private Builder(EntryType entryType, String idempotencyKey, String currency) {
            this.entryType = Objects.requireNonNull(entryType, "entryType");
            this.idempotencyKey = idempotencyKey;
            this.currency = AccountKey.normaliseCurrency(currency);
        }

        public Builder description(String description) { this.description = description; return this; }

        public Builder source(String sourceType, String sourceRef) {
            this.sourceType = sourceType;
            this.sourceRef = sourceRef;
            return this;
        }

        public Builder debit(AccountKey account, long amountMinor) { return line(account, Direction.DEBIT, amountMinor, null); }
        public Builder credit(AccountKey account, long amountMinor) { return line(account, Direction.CREDIT, amountMinor, null); }
        public Builder debit(AccountKey account, long amountMinor, String memo) { return line(account, Direction.DEBIT, amountMinor, memo); }
        public Builder credit(AccountKey account, long amountMinor, String memo) { return line(account, Direction.CREDIT, amountMinor, memo); }

        /** Zero lines are skipped (e.g. a zero fee); negative amounts are a programming error. */
        public Builder line(AccountKey account, Direction direction, long amountMinor, String memo) {
            if (amountMinor < 0) {
                throw new LedgerException("Negative amount on " + account + ": " + amountMinor + " (use the opposite direction instead)");
            }
            if (amountMinor == 0) return this;
            lines.add(new Line(account, direction, amountMinor, memo));
            return this;
        }

        public JournalDraft build() {
            return new JournalDraft(this).validate();
        }
    }
}
