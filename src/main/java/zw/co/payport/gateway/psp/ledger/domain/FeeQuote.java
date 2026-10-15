package zw.co.payport.gateway.psp.ledger.domain;

/**
 * Breakdown of one collection. Always satisfies
 * {@code gross == fee + vatOnFee + transferTax + netToMerchant}.
 */
public record FeeQuote(long grossMinor, long feeMinor, long vatOnFeeMinor, long transferTaxMinor,
                       long netToMerchantMinor, String agreementRef) {

    public FeeQuote {
        if (grossMinor < 0 || feeMinor < 0 || vatOnFeeMinor < 0 || transferTaxMinor < 0 || netToMerchantMinor < 0) {
            throw new IllegalArgumentException("Fee quote components must be >= 0");
        }
        long sum = Math.addExact(Math.addExact(feeMinor, vatOnFeeMinor), Math.addExact(transferTaxMinor, netToMerchantMinor));
        if (sum != grossMinor) {
            throw new IllegalArgumentException("Fee quote does not add up: " + sum + " != " + grossMinor);
        }
    }

    public long totalDeductionsMinor() {
        return feeMinor + vatOnFeeMinor + transferTaxMinor;
    }

    /** Effective fee in basis points, for reporting only. */
    public int effectiveBps() {
        return grossMinor == 0 ? 0 : (int) Money.proportion(10_000, Math.min(totalDeductionsMinor(), grossMinor), grossMinor);
    }
}
