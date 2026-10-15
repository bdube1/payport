package zw.co.payport.gateway.psp.wallet;

import org.springframework.stereotype.Component;
import zw.co.payport.gateway.psp.common.BreakReporter;
import zw.co.payport.gateway.psp.kyc.CustomerProfile;
import zw.co.payport.gateway.psp.kyc.KycService;
import zw.co.payport.gateway.psp.ledger.LedgerService;
import zw.co.payport.gateway.psp.ledger.domain.WalletPostingRules;
import zw.co.payport.gateway.psp.legacy.LegacyGateway;
import zw.co.payport.gateway.psp.payment.CollectionPoster;

import java.util.Optional;

/**
 * A customer topped up their PayPort wallet through a rail. Uses the gateway's
 * normal payment flow with product code WALLET_TOPUP and the wallet's phone
 * number as recipient, so all lifecycle protections (inbox, verify-by-query,
 * uncertain-outcome resolver) apply to top-ups too.
 */
@Component
public class WalletTopUpPoster implements CollectionPoster {

    public static final String PRODUCT = "WALLET_TOPUP";

    private final LedgerService ledger;
    private final KycService kyc;
    private final BreakReporter breaks;

    public WalletTopUpPoster(LedgerService ledger, KycService kyc, BreakReporter breaks) {
        this.ledger = ledger;
        this.kyc = kyc;
        this.breaks = breaks;
    }

    @Override public int order() { return 20; }

    @Override
    public boolean supports(LegacyGateway.TransactionSnapshot tx, Optional<LegacyGateway.CheckoutLink> checkout) {
        return checkout.isEmpty() && tx.productCode() != null && tx.productCode().startsWith(PRODUCT);
    }

    @Override
    public void postCollection(LegacyGateway.TransactionSnapshot tx, Optional<LegacyGateway.CheckoutLink> checkout) {
        Optional<CustomerProfile> c = kyc.byMsisdn(tx.recipientIdentifier());
        if (c.isEmpty()) {
            // Money arrived but there is no wallet to credit: park it as a customer refund liability for ops.
            breaks.report("WALLET", "TOPUP_NO_WALLET", tx.reference(), tx.currency(), tx.amountCents(), null,
                    "Top-up for " + tx.recipientIdentifier() + " has no matching wallet");
            throw new IllegalStateException("No wallet for top-up " + tx.reference());
        }
        ledger.post(WalletPostingRules.topUp(tx.reference(), c.get().getCustomerRef(), tx.paymentProviderCode(), tx.currency(),
                tx.amountCents()), "SYSTEM:wallet-topup");
    }
}
