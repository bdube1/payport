package zw.co.payport.gateway.psp.ledger.domain;

/**
 * Pure fee arithmetic. Deterministic, integer-only, half-up rounding.
 *
 * Deductions can never exceed the gross amount: if a fixed fee is larger than
 * a tiny payment, the merchant nets zero rather than going negative. That
 * case is also blocked up-front by the minimum-amount check on checkout
 * creation, but this function must never throw on a payment that has already
 * been collected.
 */
public final class FeeCalculator {

    private FeeCalculator() {}

    public static FeeQuote quote(FeeSchedule s, long grossMinor) {
        if (grossMinor < 0) throw new IllegalArgumentException("gross must be >= 0");

        long fee = Math.addExact(Money.applyBps(grossMinor, s.percentBps()), s.fixedMinor());
        if (s.minFeeMinor() > 0) fee = Math.max(fee, s.minFeeMinor());
        if (s.maxFeeMinor() > 0) fee = Math.min(fee, s.maxFeeMinor());
        fee = Math.min(fee, grossMinor);

        long vat = Math.min(Money.applyBps(fee, s.vatOnFeeBps()), grossMinor - fee);

        long tax = Money.applyBps(grossMinor, s.transferTaxBps());
        if (s.transferTaxCapMinor() > 0) tax = Math.min(tax, s.transferTaxCapMinor());
        tax = Math.min(tax, grossMinor - fee - vat);

        long net = grossMinor - fee - vat - tax;
        return new FeeQuote(grossMinor, fee, vat, tax, net, s.agreementRef());
    }
}
