package zw.co.payport.gateway.psp.ledger.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Identifies one ledger account: category + owner + currency.
 * The code form, e.g. {@code MERCHANT_AVAILABLE:42:USD}, is stable and unique.
 */
public record AccountKey(AccountCategory category, String ownerRef, String currency) {

    public static final String PLATFORM = "PLATFORM";
    public static final Set<String> SUPPORTED_CURRENCIES = Set.of("USD", "ZWG");

    public AccountKey {
        Objects.requireNonNull(category, "category");
        if (ownerRef == null || ownerRef.isBlank()) {
            throw new IllegalArgumentException("ownerRef is required for " + category);
        }
        if (ownerRef.contains(":")) {
            throw new IllegalArgumentException("ownerRef must not contain ':' -- " + ownerRef);
        }
        currency = normaliseCurrency(currency);
        ownerRef = ownerRef.trim().toUpperCase(Locale.ROOT);
    }

    public static String normaliseCurrency(String currency) {
        if (currency == null) throw new IllegalArgumentException("currency is required");
        String c = currency.trim().toUpperCase(Locale.ROOT);
        // The platform historically used "ZWG"; accept "ZIG" as an alias so both spellings land on one account.
        if ("ZIG".equals(c)) c = "ZWG";
        if (!SUPPORTED_CURRENCIES.contains(c)) {
            throw new IllegalArgumentException("Unsupported ledger currency: " + currency);
        }
        return c;
    }

    public String code() {
        return category.name() + ":" + ownerRef + ":" + currency;
    }

    // ── Convenience factories ───────────────────────────────────────────────
    public static AccountKey merchant(AccountCategory category, long merchantId, String currency) {
        requireOwnerKind(category, AccountCategory.OwnerKind.MERCHANT);
        return new AccountKey(category, Long.toString(merchantId), currency);
    }

    public static AccountKey platform(AccountCategory category, String currency) {
        requireOwnerKind(category, AccountCategory.OwnerKind.PLATFORM);
        return new AccountKey(category, PLATFORM, currency);
    }

    public static AccountKey provider(AccountCategory category, String providerCode, String currency) {
        requireOwnerKind(category, AccountCategory.OwnerKind.PROVIDER);
        return new AccountKey(category, providerCode, currency);
    }

    public static AccountKey cash(String cashAccountKey, String currency) {
        return new AccountKey(AccountCategory.PLATFORM_CASH, cashAccountKey, currency);
    }

    public static AccountKey tax(String taxCode, String currency) {
        return new AccountKey(AccountCategory.TAX_PAYABLE, taxCode, currency);
    }

    public static AccountKey customer(String customerRef, String currency) {
        return new AccountKey(AccountCategory.CUSTOMER_WALLET, customerRef, currency);
    }

    public static AccountKey agent(AccountCategory category, long agentId, String currency) {
        requireOwnerKind(category, AccountCategory.OwnerKind.AGENT);
        return new AccountKey(category, Long.toString(agentId), currency);
    }

    private static void requireOwnerKind(AccountCategory category, AccountCategory.OwnerKind kind) {
        if (category.ownerKind() != kind) {
            throw new IllegalArgumentException(category + " is owned by " + category.ownerKind() + ", not " + kind);
        }
    }

    @Override
    public String toString() {
        return code();
    }
}
