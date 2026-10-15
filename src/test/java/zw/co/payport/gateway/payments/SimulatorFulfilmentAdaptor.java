package zw.co.payport.gateway.payments;

import zw.co.payport.gateway.service.adaptor.FulfilmentProviderAdaptor;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Scriptable fulfilment provider for delivery tests; counts every delivery it receives. */
public class SimulatorFulfilmentAdaptor implements FulfilmentProviderAdaptor {

    public static final String CODE = "SIMFUL";

    public enum Deliver { OK, REJECT, CONNECT_REFUSED, READ_TIMEOUT }
    public enum Query { DELIVERED, FAILED, PENDING, DOWN }

    public static final class Script {
        public volatile Deliver deliver = Deliver.OK;
        public volatile Query query = Query.PENDING;
        public final AtomicInteger deliveries = new AtomicInteger();
        public final AtomicInteger queries = new AtomicInteger();
    }

    private static final Map<String, Script> SCRIPTS = new ConcurrentHashMap<>();

    public static Script script(String reference) {
        return SCRIPTS.computeIfAbsent(reference, r -> new Script());
    }

    @Override
    public String getProviderCode() {
        return CODE;
    }

    @Override
    public FulfilmentResult deliver(FulfilmentRequest request) throws Exception {
        Script s = script(request.getPayportReference());
        s.deliveries.incrementAndGet();
        return switch (s.deliver) {
            case OK -> FulfilmentResult.builder().success(true).fulfilmentReference("DLV-" + request.getPayportReference())
                    .fulfilmentData("{\"token\":\"1111-2222\"}").message("delivered").rawResponse("{\"ok\":true}").build();
            case REJECT -> FulfilmentResult.builder().success(false).message("Invalid meter number").rawResponse("{\"code\":\"E12\"}").build();
            case CONNECT_REFUSED -> throw new ConnectException("Connection refused");
            case READ_TIMEOUT -> throw new SocketTimeoutException("Read timed out");
        };
    }

    @Override
    public FulfilmentStatusResult queryDelivery(String fulfilmentReference) throws Exception {
        String ref = fulfilmentReference.startsWith("DLV-") ? fulfilmentReference.substring(4) : fulfilmentReference;
        Script s = script(ref);
        s.queries.incrementAndGet();
        if (s.query == Query.DOWN) throw new SocketTimeoutException("Read timed out");
        return FulfilmentStatusResult.builder().status(s.query.name()).fulfilmentReference("DLV-" + ref).message("simulated").build();
    }
}
