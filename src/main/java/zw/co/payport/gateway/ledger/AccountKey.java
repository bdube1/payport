package zw.co.payport.gateway.ledger;

import java.util.Locale;
import java.util.Objects;

/**
 * Identifies a ledger account: legal entity, book, category, owner and currency.
 * The code is entity:book:category:owner:currency (owner may not contain ':').
 */
public record AccountKey(String entity, Book book, LedgerCategory category, String owner, String currency) {

    public AccountKey {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(book, "book");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(currency, "currency");
        if (entity.isBlank() || entity.contains(":")) throw new IllegalArgumentException("invalid entity: " + entity);
        if (owner.isBlank() || owner.contains(":")) throw new IllegalArgumentException("invalid owner: " + owner);
        currency = currency.trim().toUpperCase(Locale.ROOT);
        if (!currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("invalid currency: " + currency);
    }

    public static AccountKey of(String entity, Book book, LedgerCategory category, Object owner, String currency) {
        return new AccountKey(entity, book, category, String.valueOf(owner), currency);
    }

    /** Inverse of code(): entity:book:category:owner:currency. */
    public static AccountKey parse(String code) {
        String[] p = code == null ? new String[0] : code.split(":");
        if (p.length != 5) throw new IllegalArgumentException("account code must be entity:book:category:owner:currency");
        return new AccountKey(p[0], Book.valueOf(p[1]), LedgerCategory.valueOf(p[2]), p[3], p[4]);
    }

    public String code() {
        return String.join(":", entity, book.name(), category.name(), owner, currency);
    }

    @Override
    public String toString() {
        return code();
    }
}
