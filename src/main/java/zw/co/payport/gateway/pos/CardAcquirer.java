package zw.co.payport.gateway.pos;

/**
 * A card acquirer behind POS terminals (sponsor acquirer or switch). None is bundled: card
 * acquiring needs an acquiring licence or sponsor arrangement and PCI DSS scope, so with no
 * configured implementation every card request is refused (fails closed). An implementation is a
 * Spring bean of this type; terminals must send only point-to-point encrypted card data, which
 * Payport passes through and never stores.
 */
public interface CardAcquirer {

    record Authorisation(boolean approved, boolean outcomeUnknown, String authCode, String acquirerReference, String message) {}

    String name();

    boolean configured();

    Authorisation authorise(String saleReference, long amountMinor, String currency, String encryptedCardData) throws Exception;
}
