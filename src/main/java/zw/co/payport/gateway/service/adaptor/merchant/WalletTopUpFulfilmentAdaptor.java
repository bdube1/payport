package zw.co.payport.gateway.service.adaptor.merchant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.service.adaptor.FulfilmentProviderAdaptor;

/**
 * Fulfilment for WALLET_TOPUP products (provider PAYPORT_WALLET).
 *
 * Like a merchant checkout, a wallet top-up has nothing to deliver to a third
 * party. The wallet credit itself is a ledger posting made by the PSP
 * (WalletTopUpPoster) when the payment-success event is processed, so it stays
 * exactly-once and balanced even if this step is retried. If no wallet exists
 * for the number, the poster raises a reconciliation break, so the money is
 * never silently lost.
 */
@Slf4j
@Component
public class WalletTopUpFulfilmentAdaptor implements FulfilmentProviderAdaptor {

    @Override
    public String getProviderCode() {
        return "PAYPORT_WALLET";
    }

    @Override
    public FulfilmentResult deliver(FulfilmentRequest request) {
        log.info("[WALLET_TOPUP] Payment collected; wallet credit is posted by the PSP ledger: {}", request.getPayportReference());
        return FulfilmentResult.builder()
                .success(true)
                .fulfilmentReference(request.getPayportReference())
                .message("Wallet top-up collected -- credit posted to the e-money ledger")
                .build();
    }

    @Override
    public FulfilmentStatusResult queryDelivery(String fulfilmentReference) {
        return FulfilmentStatusResult.builder()
                .status("DELIVERED")
                .fulfilmentReference(fulfilmentReference)
                .message("Wallet top-ups are credited by the ledger; see the wallet statement")
                .build();
    }
}
