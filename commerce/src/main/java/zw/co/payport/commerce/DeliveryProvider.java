package zw.co.payport.commerce;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * How an order reaches the customer. Checkout and order management only talk to this interface, so a courier company
 * can be added later as one more adapter (quote from its price list or API, steps from its tracking) without changing
 * either. MANUAL is the merchant's own delivery: its fee, its free-delivery amount, steps set by the merchant.
 */
public interface DeliveryProvider {

    String code();

    /** The fee for these items to this address (in the store's currency), or why it cannot deliver there. */
    Quote quote(Map<String, Object> store, long itemsMinor, String address);

    /** The delivery steps after payment, in order (the last one fulfils the order). */
    List<String> steps();

    record Quote(long feeMinor, String problem) {}

    /** The merchant's own delivery (the only provider until a courier company is connected). */
    @Component
    class Manual implements DeliveryProvider {
        public String code() { return "MANUAL"; }

        public Quote quote(Map<String, Object> st, long itemsMinor, String address) {
            long fee = st.get("delivery_fee_minor") == null ? 0 : ((Number) st.get("delivery_fee_minor")).longValue();
            Object free = st.get("free_delivery_from_minor");
            return new Quote(free != null && itemsMinor >= ((Number) free).longValue() ? 0 : fee, null);
        }

        public List<String> steps() { return List.of("PREPARING", "OUT", "DELIVERED"); }
    }

    /** The adapters available, by code. */
    @Component
    class Registry {
        private final Map<String, DeliveryProvider> byCode;

        public Registry(List<DeliveryProvider> providers) {
            this.byCode = providers.stream().collect(java.util.stream.Collectors.toMap(DeliveryProvider::code, p -> p));
        }

        public DeliveryProvider of(Object code) {
            DeliveryProvider p = byCode.get(code == null ? "MANUAL" : String.valueOf(code));
            return p != null ? p : byCode.get("MANUAL");
        }
    }
}
