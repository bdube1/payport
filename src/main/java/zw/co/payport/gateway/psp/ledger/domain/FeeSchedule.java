package zw.co.payport.gateway.psp.ledger.domain;

/**
 * The commercial terms applied to one collection, frozen at payment time.
 *
 * Tax rates are deliberately NOT hard-coded anywhere in the codebase: VAT on
 * fees and any merchant-borne transfer tax (IMTT) are configured per fee
 * agreement / platform setting, so a change in the Finance Act is a config
 * change signed off by finance, not a code release.
 *
 * @param percentBps         percentage fee in basis points (250 = 2.5%)
 * @param fixedMinor         fixed fee per transaction in minor units
 * @param minFeeMinor        floor on the fee (0 = none)
 * @param maxFeeMinor        cap on the fee (0 = none)
 * @param vatOnFeeBps        VAT charged on top of the fee, borne by the merchant (0 = not applicable)
 * @param transferTaxBps     merchant-borne transfer tax on the gross amount (0 = rail/payer bears it)
 * @param transferTaxCapMinor cap on the transfer tax per transaction (0 = none)
 * @param agreementRef       which fee agreement produced this schedule, kept for audit
 */
public record FeeSchedule(
        int percentBps,
        long fixedMinor,
        long minFeeMinor,
        long maxFeeMinor,
        int vatOnFeeBps,
        int transferTaxBps,
        long transferTaxCapMinor,
        String agreementRef) {

    public FeeSchedule {
        checkBps(percentBps, "percentBps");
        checkBps(vatOnFeeBps, "vatOnFeeBps");
        checkBps(transferTaxBps, "transferTaxBps");
        if (fixedMinor < 0 || minFeeMinor < 0 || maxFeeMinor < 0 || transferTaxCapMinor < 0) {
            throw new IllegalArgumentException("Fee amounts must be >= 0");
        }
        if (maxFeeMinor > 0 && minFeeMinor > maxFeeMinor) {
            throw new IllegalArgumentException("minFee exceeds maxFee");
        }
    }

    public static FeeSchedule percentOnly(int bps, String agreementRef) {
        return new FeeSchedule(bps, 0, 0, 0, 0, 0, 0, agreementRef);
    }

    private static void checkBps(int bps, String name) {
        if (bps < 0 || bps > 10_000) throw new IllegalArgumentException(name + " must be 0..10000");
    }
}
