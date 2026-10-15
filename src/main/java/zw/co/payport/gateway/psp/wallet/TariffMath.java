package zw.co.payport.gateway.psp.wallet;

import zw.co.payport.gateway.psp.ledger.domain.Money;

import java.util.List;

/** Banded tariff lookup. Pure, unit-tested. */
public final class TariffMath {

    public record Band(long minMinor, long maxMinor, long feeFixed, int feeBps, long commissionFixed, int commissionBps) {}
    public record Charges(long customerFeeMinor, long agentCommissionMinor) {}

    private TariffMath() {}

    /** Finds the band containing the amount (max 0 = open-ended) and computes fee and commission. No band = free, no commission. */
    public static Charges charges(List<Band> bands, long amountMinor) {
        for (Band b : bands) {
            if (amountMinor >= b.minMinor() && (b.maxMinor() == 0 || amountMinor <= b.maxMinor())) {
                long fee = Math.addExact(b.feeFixed(), Money.applyBps(amountMinor, b.feeBps()));
                long com = Math.addExact(b.commissionFixed(), Money.applyBps(amountMinor, b.commissionBps()));
                return new Charges(fee, com);
            }
        }
        return new Charges(0, 0);
    }

    /** IMTT-style transfer tax: rate on the amount, nothing at or below the threshold, capped. */
    public static long transferTax(long amountMinor, int rateBps, long thresholdMinor, long capMinor) {
        if (rateBps <= 0 || amountMinor <= thresholdMinor) return 0;
        long t = Money.applyBps(amountMinor, rateBps);
        return capMinor > 0 ? Math.min(t, capMinor) : t;
    }
}
