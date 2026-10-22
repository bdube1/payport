package zw.co.payport.gateway.billers;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The biller framework (Phase 5). A biller is a provider customers pay an account with: a
 * meter, a policy, a council account. Every biller declares how its accounts look and what it
 * accepts, and can look an account up before any money is taken:
 *
 *   profile()  name, account label and format, accepted currencies, amount limits
 *   validateAccount()  format check, run when a payment is initiated (before the customer pays)
 *   lookup()  the account holder as the biller knows them (read-only, no money moves)
 *
 * Delivery itself stays in the provider's FulfilmentProviderAdaptor (same provider code), so the
 * payment lifecycle, unknown-outcome resolution and reconciliation are shared by all billers.
 */
public interface BillerAdaptor {

    record Profile(String code, String name, String category, String accountLabel, String accountPattern, String accountHint,
                   List<String> currencies, long minAmountMinor, long maxAmountMinor, boolean lookupBeforePayment) {}

    record Account(String accountNumber, String holderName, String currency, List<String> acceptedCurrencies,
                   boolean payable, String notPayableReason, Map<String, Object> details) {}

    Profile profile();

    /** Null if the account number is acceptable, otherwise a message for the customer. */
    default String validateAccount(String account) {
        Profile p = profile();
        if (account == null || account.isBlank()) return p.accountLabel() + " is required";
        String clean = account.trim().replaceAll("\\s+", "");
        if (!Pattern.matches(p.accountPattern(), clean)) return p.accountLabel() + " is not valid. " + p.accountHint();
        return null;
    }

    /** Null if the amount is acceptable for this biller, otherwise a message for the customer. */
    default String validateAmount(long amountMinor, String currency) {
        Profile p = profile();
        if (currency != null && !p.currencies().contains(currency.toUpperCase())) {
            return p.name() + " does not accept " + currency.toUpperCase();
        }
        if (amountMinor < p.minAmountMinor()) return "The minimum " + p.name() + " payment is " + money(p.minAmountMinor(), currency);
        if (p.maxAmountMinor() > 0 && amountMinor > p.maxAmountMinor()) return "The maximum " + p.name() + " payment is " + money(p.maxAmountMinor(), currency);
        return null;
    }

    Account lookup(String accountNumber, String currency, String sourcePhone) throws Exception;

    private static String money(long minor, String currency) {
        return (currency == null ? "" : currency.toUpperCase() + " ") + String.format("%d.%02d", minor / 100, minor % 100);
    }
}
