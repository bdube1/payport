package zw.co.payport.gateway.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import zw.co.payport.gateway.service.adaptor.PaymentProviderAdaptor;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * A scriptable payment provider for lifecycle tests. Each payment reference gets a Script
 * that decides how submit and status queries behave, and counts how often each was called.
 *
 * Callback trust: callbacks must carry the header x-sim-signature: valid (signature-trusted
 * path), unless the provider code is listed in payport.payments.query-providers, in which
 * case the inbox ignores the content and asks queryStatus instead.
 */
public class SimulatorPaymentAdaptor implements PaymentProviderAdaptor {

    public enum Submit { ACCEPT, REJECT_4XX, CONNECT_REFUSED, READ_TIMEOUT, SERVER_ERROR }
    public enum Query { SUCCESS, FAILED, PENDING, DOWN }

    public static final class Script {
        public volatile Submit submit = Submit.ACCEPT;
        public volatile Query query = Query.PENDING;
        public volatile Long queryAmountCents;            // null = report the requested amount
        public volatile String queryMessage;              // the provider's wording on a query answer
        public volatile Consumer<String> duringSubmit;    // runs after the provider "received" the request, before it answers
        public final AtomicInteger submits = new AtomicInteger();
        public final AtomicInteger queries = new AtomicInteger();
        volatile long requestedAmountCents;
    }

    private static final Map<String, Script> SCRIPTS = new ConcurrentHashMap<>();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String code;

    public SimulatorPaymentAdaptor(String code) {
        this.code = code;
    }

    public static Script script(String reference) {
        return SCRIPTS.computeIfAbsent(reference, r -> new Script());
    }

    /** A provider callback body. */
    public static String callbackBody(String reference, String status, long amountCents, String providerId) {
        return callbackBody(reference, status, amountCents, providerId, null);
    }

    public static String callbackBody(String reference, String status, long amountCents, String providerId, String message) {
        return "{\"ref\":\"" + reference + "\",\"status\":\"" + status + "\",\"amount\":" + amountCents + ",\"id\":\"" + providerId + "\""
                + (message == null ? "" : ",\"message\":\"" + message + "\"") + "}";
    }

    @Override
    public String getProviderCode() {
        return code;
    }

    @Override
    public PaymentSubmitResult submitPayment(PaymentRequest request) throws Exception {
        Script s = script(request.getPayportReference());
        s.requestedAmountCents = request.getAmountCents();
        s.submits.incrementAndGet();
        switch (s.submit) {
            case CONNECT_REFUSED -> throw new ConnectException("Connection refused");
            case REJECT_4XX -> {
                return PaymentSubmitResult.builder().submitted(false).message("Insufficient funds in your wallet").rawResponse("{\"error\":\"INSUFFICIENT_FUNDS\"}").build();
            }
            default -> { }
        }
        if (s.duringSubmit != null) s.duringSubmit.accept(request.getPayportReference());
        return switch (s.submit) {
            case READ_TIMEOUT -> throw new SocketTimeoutException("Read timed out");
            case SERVER_ERROR -> throw new IllegalStateException("502 Bad Gateway from provider");
            default -> PaymentSubmitResult.builder().submitted(true).providerReference("SIM-" + request.getPayportReference())
                    .message("push sent").rawResponse("{\"ok\":true}").build();
        };
    }

    @Override
    public CallbackValidationResult validateCallback(Map<String, String> headers, String rawBody) {
        boolean ok = headers != null && "valid".equals(headers.get("x-sim-signature"));
        return CallbackValidationResult.builder().valid(ok).failureReason(ok ? null : "bad signature").build();
    }

    @Override
    public CallbackEvent parseCallback(String rawBody) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = JSON.readValue(rawBody, Map.class);
        return CallbackEvent.builder()
                .payportReference((String) m.get("ref"))
                .status((String) m.get("status"))
                .amountCents(m.get("amount") == null ? 0 : ((Number) m.get("amount")).longValue())
                .providerReference((String) m.get("id"))
                .failureMessage((String) m.get("message"))
                .currency("USD")
                .rawPayload(rawBody)
                .build();
    }

    @Override
    public PaymentStatusResult queryStatus(String providerReference) throws Exception {
        // The simulator's provider reference is "SIM-" + Payport reference; fall back to the reference itself.
        String ref = providerReference != null && providerReference.startsWith("SIM-") ? providerReference.substring(4) : providerReference;
        Script s = script(ref);
        s.queries.incrementAndGet();
        if (s.query == Query.DOWN) throw new SocketTimeoutException("Read timed out");
        long amount = s.queryAmountCents != null ? s.queryAmountCents : s.requestedAmountCents;
        return PaymentStatusResult.builder().status(s.query.name()).amountCents(amount)
                .providerReference("SIM-" + ref).message(s.queryMessage).build();
    }
}
