package zw.co.payport.gateway.psp.ledger.domain;

/** Integer minor-unit arithmetic. No doubles anywhere near money. */
public final class Money {

    private Money() {}

    /** amount * bps / 10_000, rounded half-up. Throws on overflow rather than wrapping. */
    public static long applyBps(long amountMinor, int bps) {
        if (amountMinor < 0) throw new IllegalArgumentException("amount must be >= 0");
        if (bps < 0 || bps > 10_000) throw new IllegalArgumentException("bps must be 0..10000, got " + bps);
        long product = Math.multiplyExact(amountMinor, (long) bps);
        return Math.floorDiv(Math.addExact(product, 5_000L), 10_000L);
    }

    /** Splits {@code total} proportionally to {@code part/whole}, rounded half-up. */
    public static long proportion(long total, long part, long whole) {
        if (whole <= 0) return 0;
        if (part < 0 || part > whole) throw new IllegalArgumentException("part must be within 0..whole");
        long product = Math.multiplyExact(total, part);
        return Math.floorDiv(Math.addExact(product, whole / 2), whole);
    }
}
