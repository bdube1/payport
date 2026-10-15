package zw.co.payport.gateway.psp.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.psp.legacy.LegacyGateway;
import zw.co.payport.gateway.psp.platform.outbox.EventTypes;
import zw.co.payport.gateway.psp.platform.outbox.PspEvent;
import zw.co.payport.gateway.psp.platform.outbox.PspEventHandler;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Posts every payment lifecycle event to the ledger through the matching {@link CollectionPoster}. */
@Component
public class PaymentLedgerHandler implements PspEventHandler {

    private static final Set<String> TYPES = Set.of(EventTypes.PAYMENT_CONFIRMED, EventTypes.PAYMENT_COMPLETED, EventTypes.PAYMENT_REFUNDED);

    private final LegacyGateway gateway;
    private final List<CollectionPoster> posters;
    private final ObjectMapper om;

    public PaymentLedgerHandler(LegacyGateway gateway, List<CollectionPoster> posters, ObjectMapper om) {
        this.gateway = gateway;
        this.posters = posters.stream().sorted(Comparator.comparingInt(CollectionPoster::order)).toList();
        this.om = om;
    }

    @Override public String name() { return "ledger.payments"; }
    @Override public boolean supports(String eventType) { return TYPES.contains(eventType); }

    @Override
    public void handle(PspEvent event) throws Exception {
        String ref = om.readTree(event.payloadJson()).path("reference").asText();
        LegacyGateway.TransactionSnapshot tx = gateway.transaction(ref)
                .orElseThrow(() -> new IllegalStateException("Transaction " + ref + " not found"));
        if (isTest(tx)) return;
        Optional<LegacyGateway.CheckoutLink> checkout = gateway.checkoutForTransaction(ref);
        CollectionPoster poster = posters.stream().filter(p -> p.supports(tx, checkout)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No ledger poster for " + ref));
        switch (event.eventType()) {
            case EventTypes.PAYMENT_CONFIRMED -> poster.postCollection(tx, checkout);
            case EventTypes.PAYMENT_COMPLETED -> poster.postCompletion(tx, checkout);
            case EventTypes.PAYMENT_REFUNDED -> poster.postRefund(tx, checkout);
            default -> { }
        }
    }

    /** Merchant TEST-mode keys and the gateway's test bypass never touch the live ledger. */
    static boolean isTest(LegacyGateway.TransactionSnapshot tx) {
        String p = tx.paymentProviderCode();
        return p != null && (p.startsWith("TEST") || p.equals("UNKNOWN"));
    }
}
