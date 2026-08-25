package zw.co.payport.gateway.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.service.adaptor.FulfilmentProviderAdaptor;
import zw.co.payport.gateway.service.adaptor.PaymentProviderAdaptor;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Adaptor Registry
 *
 * Auto-discovers all PaymentProviderAdaptor and FulfilmentProviderAdaptor beans
 * at startup. This means adding a new payment method or service provider
 * ONLY requires building the adaptor class - no registry changes needed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdaptorRegistry {

    private final List<PaymentProviderAdaptor>     paymentAdaptors;
    private final List<FulfilmentProviderAdaptor>  fulfilmentAdaptors;

    private Map<String, PaymentProviderAdaptor>    paymentAdaptorMap;
    private Map<String, FulfilmentProviderAdaptor> fulfilmentAdaptorMap;

    @PostConstruct
    public void init() {
        paymentAdaptorMap = paymentAdaptors.stream()
                .collect(Collectors.toMap(
                        a -> a.getProviderCode().toUpperCase(),
                        Function.identity()
                ));

        fulfilmentAdaptorMap = fulfilmentAdaptors.stream()
                .collect(Collectors.toMap(
                        a -> a.getProviderCode().toUpperCase(),
                        Function.identity()
                ));

        log.info("[GATEWAY] Payment adaptors registered:    {}", paymentAdaptorMap.keySet());
        log.info("[GATEWAY] Fulfilment adaptors registered: {}", fulfilmentAdaptorMap.keySet());
    }

    public PaymentProviderAdaptor getPaymentAdaptor(String providerCode) {
        PaymentProviderAdaptor adaptor = paymentAdaptorMap.get(providerCode.toUpperCase());
        if (adaptor == null) {
            throw new IllegalArgumentException(
                    "No payment adaptor found for provider: " + providerCode
                    + ". Available: " + paymentAdaptorMap.keySet());
        }
        return adaptor;
    }

    public FulfilmentProviderAdaptor getFulfilmentAdaptor(String providerCode) {
        FulfilmentProviderAdaptor adaptor = fulfilmentAdaptorMap.get(providerCode.toUpperCase());
        if (adaptor == null) {
            throw new IllegalArgumentException(
                    "No fulfilment adaptor found for provider: " + providerCode
                    + ". Available: " + fulfilmentAdaptorMap.keySet());
        }
        return adaptor;
    }

    public boolean hasPaymentAdaptor(String providerCode) {
        return providerCode != null && paymentAdaptorMap.containsKey(providerCode.toUpperCase());
    }

    public boolean hasFulfilmentAdaptor(String providerCode) {
        return providerCode != null && fulfilmentAdaptorMap.containsKey(providerCode.toUpperCase());
    }
}
