package zw.co.payport.gateway.psp.payment;

import zw.co.payport.gateway.psp.legacy.LegacyGateway;

import java.util.Optional;

/**
 * Decides how a confirmed payment hits the ledger. Implementations are
 * tried in {@link #order()} order and the first that {@link #supports}
 * the payment posts it: merchant checkout, wallet top-up, agent float
 * purchase, and finally consumer bill/airtime purchase as the fallback.
 * Every implementation must be idempotent (PostingRules keys guarantee this).
 */
public interface CollectionPoster {

    int order();

    boolean supports(LegacyGateway.TransactionSnapshot tx, Optional<LegacyGateway.CheckoutLink> checkout);

    void postCollection(LegacyGateway.TransactionSnapshot tx, Optional<LegacyGateway.CheckoutLink> checkout);

    /** Called when the product was delivered. Most posters have nothing to do. */
    default void postCompletion(LegacyGateway.TransactionSnapshot tx, Optional<LegacyGateway.CheckoutLink> checkout) {}

    /** Called when ops recorded a manual refund on the transaction. */
    default void postRefund(LegacyGateway.TransactionSnapshot tx, Optional<LegacyGateway.CheckoutLink> checkout) {}
}
