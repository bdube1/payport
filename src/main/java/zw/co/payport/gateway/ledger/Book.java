package zw.co.payport.gateway.ledger;

/** Separate books so test-mode money can never mix with live money (ADR-0007). */
public enum Book { LIVE, TEST;

    public static Book of(boolean livemode) {
        return livemode ? LIVE : TEST;
    }
}
