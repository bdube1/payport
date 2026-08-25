package zw.co.payport.gateway.service.adaptor.merchant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.service.adaptor.FulfilmentProviderAdaptor;

/**
 * Deliberate no-op. For a merchant checkout, successful PAYMENT is the
 * entire point of the transaction -- there is no airtime to credit, no
 * ZESA token to generate, nothing to "deliver" afterward. This adaptor
 * exists purely so a merchant checkout can flow through the same,
 * already-hardened TransactionOrchestratorService pipeline (payment
 * collection, audit logging, status transitions) as every other product,
 * instead of that logic being duplicated in a second, parallel path.
 */
@Slf4j
@Component
public class MerchantCheckoutFulfilmentAdaptor implements FulfilmentProviderAdaptor {

    @Override
    public String getProviderCode() {
        return "MERCHANT";
    }

    @Override
    public FulfilmentResult deliver(FulfilmentRequest request) {
        log.info("[MERCHANT_CHECKOUT] Payment collected, nothing to fulfil: {}", request.getPayportReference());
        return FulfilmentResult.builder()
                .success(true)
                .fulfilmentReference(request.getPayportReference())
                .message("Merchant checkout payment collected -- no fulfilment required")
                .build();
    }

    @Override
    public FulfilmentStatusResult queryDelivery(String fulfilmentReference) {
        return FulfilmentStatusResult.builder()
                .status("DELIVERED")
                .fulfilmentReference(fulfilmentReference)
                .message("Merchant checkouts have no delivery to track -- payment status is the source of truth")
                .build();
    }
}
