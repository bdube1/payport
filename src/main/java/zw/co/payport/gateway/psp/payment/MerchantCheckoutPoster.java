package zw.co.payport.gateway.psp.payment;

import org.springframework.stereotype.Component;
import zw.co.payport.gateway.psp.common.BreakReporter;
import zw.co.payport.gateway.psp.fees.FeeService;
import zw.co.payport.gateway.psp.ledger.MerchantFundsService;
import zw.co.payport.gateway.psp.ledger.domain.FeeQuote;
import zw.co.payport.gateway.psp.legacy.LegacyGateway;
import zw.co.payport.gateway.psp.merchant.MerchantControlService;

import java.util.Optional;

/**
 * A customer paid a merchant (hosted checkout, payment link, QR, POS):
 * gross from the rail, fee + VAT to PayPort, net to the merchant's PENDING
 * balance on the merchant's T+N hold.
 */
@Component
public class MerchantCheckoutPoster implements CollectionPoster {

    private final FeeService fees;
    private final MerchantFundsService funds;
    private final MerchantControlService merchants;
    private final BreakReporter breaks;

    public MerchantCheckoutPoster(FeeService fees, MerchantFundsService funds, MerchantControlService merchants, BreakReporter breaks) {
        this.fees = fees;
        this.funds = funds;
        this.merchants = merchants;
        this.breaks = breaks;
    }

    @Override public int order() { return 10; }

    @Override
    public boolean supports(LegacyGateway.TransactionSnapshot tx, Optional<LegacyGateway.CheckoutLink> checkout) {
        return checkout.isPresent();
    }

    @Override
    public void postCollection(LegacyGateway.TransactionSnapshot tx, Optional<LegacyGateway.CheckoutLink> checkout) {
        LegacyGateway.CheckoutLink c = checkout.orElseThrow();
        String method = c.paymentMethodCode() != null ? c.paymentMethodCode() : tx.paymentProviderCode();
        FeeQuote q = fees.quote(c.merchantId(), method, tx.currency(), tx.amountCents());
        funds.recordCollection(tx.reference(), c.merchantId(), tx.paymentProviderCode(), tx.currency(), q,
                merchants.fundsPolicy(c.merchantId()), "SYSTEM:payment-ledger");
    }

    /**
     * Merchant payments must be refunded through the merchant refund flow (which debits the
     * merchant's balance). A manual ops refund on the underlying transaction bypasses that,
     * so it is escalated for finance to decide who bears it.
     */
    @Override
    public void postRefund(LegacyGateway.TransactionSnapshot tx, Optional<LegacyGateway.CheckoutLink> checkout) {
        breaks.report("PAYMENT", "MANUAL_REFUND_ON_MERCHANT_PAYMENT", tx.reference(), tx.currency(), tx.amountCents(), null,
                "Ops marked a merchant checkout payment REFUNDED outside the merchant refund flow; decide whether to debit merchant "
                        + checkout.map(LegacyGateway.CheckoutLink::merchantId).orElse(null) + " via an adjustment");
    }
}
