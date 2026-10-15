package zw.co.payport.gateway.psp.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Exact FX arithmetic on minor units. Rates are "units of quote per 1 unit
 * of base" (e.g. USD/ZWG 26.75 means 1 USD = 26.75 ZWG). Results are
 * rounded DOWN for the customer leg so PayPort never pays out more than the
 * rate implies, and HALF_EVEN for the mid leg.
 */
public final class FxMath {

    private FxMath() {}

    /** Converts {@code amountMinor} of {@code from} into {@code to}, given a USD/ZWG-style pair and rate. */
    public static long convert(long amountMinor, String from, String to, String base, String quote, BigDecimal rate, RoundingMode mode) {
        if (rate == null || rate.signum() <= 0) throw new IllegalArgumentException("Rate must be positive");
        BigDecimal amt = BigDecimal.valueOf(amountMinor);
        BigDecimal out;
        if (from.equals(base) && to.equals(quote)) out = amt.multiply(rate);
        else if (from.equals(quote) && to.equals(base)) out = amt.divide(rate, 10, RoundingMode.HALF_EVEN);
        else throw new IllegalArgumentException("Pair " + base + "/" + quote + " does not convert " + from + "->" + to);
        return out.setScale(0, mode).longValueExact();
    }

    /**
     * Customer rate with a spread: when the customer converts INTO the quote
     * currency they get the bid side; into the base currency, the ask side.
     */
    public static BigDecimal applySpread(BigDecimal mid, int spreadBps, boolean customerReceivesQuote) {
        BigDecimal f = BigDecimal.valueOf(spreadBps).divide(BigDecimal.valueOf(10_000), 10, RoundingMode.HALF_EVEN);
        return customerReceivesQuote ? mid.multiply(BigDecimal.ONE.subtract(f)) : mid.multiply(BigDecimal.ONE.add(f));
    }
}
