package zw.co.payport.gateway.psp.payment;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.psp.ledger.LedgerService;
import zw.co.payport.gateway.psp.ledger.domain.PostingRules;
import zw.co.payport.gateway.psp.legacy.LegacyGateway;

import java.util.Optional;

/**
 * Fallback poster: a consumer bought airtime, data, ZESA tokens or paid a
 * bill. Money sits in CUSTOMER_FUNDS_IN_TRANSIT until the biller delivers,
 * then consumes the biller float (minus PayPort's commission).
 *
 * Biller commission rates are configuration (payport.psp.biller-commission-bps.ESOLUTIONS=...),
 * because they come from each biller's contract.
 */
@Component
public class ConsumerPurchasePoster implements CollectionPoster {

    private final LedgerService ledger;
    private final Environment env;

    public ConsumerPurchasePoster(LedgerService ledger, Environment env) {
        this.ledger = ledger;
        this.env = env;
    }

    @Override public int order() { return 1000; }

    @Override
    public boolean supports(LegacyGateway.TransactionSnapshot tx, Optional<LegacyGateway.CheckoutLink> checkout) {
        return checkout.isEmpty();
    }

    @Override
    public void postCollection(LegacyGateway.TransactionSnapshot tx, Optional<LegacyGateway.CheckoutLink> checkout) {
        ledger.post(PostingRules.consumerCollection(tx.reference(), tx.paymentProviderCode(), tx.currency(), tx.amountCents()),
                "SYSTEM:payment-ledger");
    }

    @Override
    public void postCompletion(LegacyGateway.TransactionSnapshot tx, Optional<LegacyGateway.CheckoutLink> checkout) {
        String biller = tx.fulfilmentProviderCode() != null ? tx.fulfilmentProviderCode() : "UNKNOWN_BILLER";
        int bps = Integer.parseInt(env.getProperty("payport.psp.biller-commission-bps." + biller.toUpperCase(), "0"));
        ledger.post(PostingRules.consumerFulfilment(tx.reference(), biller, tx.currency(), tx.amountCents(), bps),
                "SYSTEM:payment-ledger");
    }

    @Override
    public void postRefund(LegacyGateway.TransactionSnapshot tx, Optional<LegacyGateway.CheckoutLink> checkout) {
        boolean delivered = ledger.entryByKey("FULFIL:" + tx.reference()).isPresent();
        String cashAccount = tx.refundMethod() != null && !tx.refundMethod().isBlank()
                ? tx.refundMethod().toUpperCase().replaceAll("[^A-Z0-9_]", "_") : "MANUAL_REFUNDS";
        ledger.post(PostingRules.consumerRefundPaid(tx.reference(), tx.currency(), tx.amountCents(), delivered, cashAccount),
                "SYSTEM:payment-ledger");
    }
}
