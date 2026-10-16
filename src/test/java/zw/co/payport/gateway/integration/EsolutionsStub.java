package zw.co.payport.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A local stand-in for the eSolutions /billpayments/vend endpoint, answering in the shapes of the
 * eSolutions spec. Records every request (headers and JSON body) so tests can assert the wire contract.
 */
public final class EsolutionsStub implements AutoCloseable {

    public record Request(String authorization, Map<String, Object> body) {}

    public final List<Request> requests = new CopyOnWriteArrayList<>();
    public volatile Function<Map<String, Object>, Object> responder = b -> Map.of("mti", "0210", "responseCode", "05");
    private final HttpServer server;
    private static final ObjectMapper JSON = new ObjectMapper();

    @SuppressWarnings("unchecked")
    public EsolutionsStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/billpayments/vend", ex -> {
            Map<String, Object> body = JSON.readValue(ex.getRequestBody().readAllBytes(), Map.class);
            requests.add(new Request(ex.getRequestHeaders().getFirst("Authorization"), body));
            Object answer = responder.apply(body);
            if (answer instanceof Integer sleepMs) {          // simulate a provider that never answers in time
                try { Thread.sleep(sleepMs); } catch (InterruptedException ignored) { }
                answer = Map.of("mti", "0210", "responseCode", "00");
            }
            byte[] out = (answer instanceof String s ? s : JSON.writeValueAsString(answer)).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Map<String, Object>> bodies(String processingCode, String mti) {
        return requests.stream().map(Request::body)
                .filter(b -> processingCode == null || processingCode.equals(b.get("processingCode")))
                .filter(b -> mti == null || mti.equals(b.get("mti"))).toList();
    }

    /** A successful ZESA vend in the spec's format (token|units|rate|receipt|net|tax|taxRate). */
    public static Map<String, Object> zesaSuccess(String vendorRef, String currency) {
        return Map.ofEntries(Map.entry("mti", "0210"), Map.entry("responseCode", "00"), Map.entry("vendorReference", vendorRef),
                Map.entry("transactionReference", "ES" + vendorRef), Map.entry("currencyCode", currency),
                Map.entry("merchantName", "ZETDC"), Map.entry("utilityAccount", "37132587712"), Map.entry("receiptNumber", "R-778812"),
                Map.entry("token", "12345678901234567890|25.4|Domestic|R-778812|870|130|15%"), Map.entry("narrative", "Transaction Successful"));
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
