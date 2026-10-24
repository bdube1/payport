package zw.co.payport.gateway.cards;

import java.util.Map;
import java.util.Optional;

/** A card issuer's API (VISA issuing processor, ZimSwitch). PayPort keeps the card record; the issuer holds the card. */
public interface CardIssuer {

    /** VISA or ZIMSWITCH. */
    String scheme();

    /** True once the issuing contract and credentials are in place. */
    boolean connected();

    /** Issue the card; returns the issuer's view of it. */
    Issued issue(Map<String, Object> card);

    /** Change status at the issuer: FROZEN, ACTIVE (unfreeze), BLOCKED or CANCELLED. */
    Issued setStatus(Map<String, Object> card, String status);

    /** The issuer's current view of the card. */
    Optional<Issued> fetch(Map<String, Object> card);

    /** The full card number, expiry and CVV, fetched from the issuer for the holder only, never stored. */
    default Sensitive reveal(Map<String, Object> card) {
        throw new IllegalStateException("Card details are shown once the card is issued");
    }

    record Issued(String issuerRef, String status, String maskedPan, String last4, String expiry, String note) {}

    record Sensitive(String pan, String expiry, String cvv) {}

    /** The issuer for a scheme with no issuing contract yet: requests are recorded and wait. */
    static CardIssuer unconnected(String scheme) {
        return new CardIssuer() {
            public String scheme() { return scheme; }
            public boolean connected() { return false; }
            public Issued issue(Map<String, Object> card) { throw new IllegalStateException(scheme + " cards are not connected yet"); }
            public Issued setStatus(Map<String, Object> card, String status) { throw new IllegalStateException(scheme + " cards are not connected yet"); }
            public Optional<Issued> fetch(Map<String, Object> card) { return Optional.empty(); }
        };
    }
}
