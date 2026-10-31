package zw.co.payport.gateway.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A stand-in for Payport Commerce's channel API: one food store, quotes, and the orders the channels place. */
class FakeCommerce {

    static final String KEY = "commerce-key-for-tests-0123456789";
    final HttpServer server;
    final ObjectMapper json = new ObjectMapper();
    final List<JsonNode> orders = new ArrayList<>();

    FakeCommerce() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void handle(HttpExchange x) throws IOException {
        if (!KEY.equals(x.getRequestHeaders().getFirst("X-PayPort-Commerce-Key"))) { send(x, 403, Map.of("success", false)); return; }
        String path = x.getRequestURI().getPath(), method = x.getRequestMethod();
        Map<String, Object> order = Map.of("orderRef", "CO-TEST0001", "store", "Mai's Kitchen", "total", "USD 12.00", "paymentStatus", "ON_DELIVERY",
                "orderStatus", "OPEN", "deliveryStatus", "PREPARING");
        if (method.equals("GET") && path.equals("/api/channel/stores")) {
            send(x, 200, Map.of("success", true, "data", Map.of("stores", List.of(Map.of("slug", "mais-kitchen", "name", "Mai's Kitchen", "items", 1)),
                    "types", List.of(Map.of("code", "FOOD", "name", "Food", "stores", 1)))));
        } else if (method.equals("GET") && path.equals("/api/channel/stores/mais-kitchen")) {
            send(x, 200, Map.of("success", true, "data", Map.of("slug", "mais-kitchen", "name", "Mai's Kitchen", "open", true, "pickup", true, "delivery", false,
                    "payOnDelivery", true, "methods", List.of(), "bookings", false, "zones", List.of(),
                    "items", List.of(Map.of("id", 11, "name", "Sadza and beef", "price", "USD 6.00", "service", false, "soldOut", false)))));
        } else if (method.equals("POST") && path.equals("/api/channel/quote")) {
            send(x, 200, Map.of("success", true, "data", Map.of("currency", "USD", "totalMinor", 1200, "total", "USD 12.00",
                    "lines", List.of(Map.of("name", "Sadza and beef", "quantity", 2)))));
        } else if (method.equals("POST") && path.equals("/api/channel/orders")) {
            synchronized (this) { orders.add(json.readTree(x.getRequestBody())); }
            send(x, 200, Map.of("success", true, "data", order));
        } else if (method.equals("GET") && path.equals("/api/channel/orders")) {
            send(x, 200, Map.of("success", true, "data", orders.isEmpty() ? List.of() : List.of(order)));
        } else {
            send(x, 404, Map.of("success", false, "error", Map.of("message", "No such store")));
        }
    }

    void send(HttpExchange x, int status, Object body) throws IOException {
        byte[] b = json.writeValueAsBytes(body);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(status, b.length);
        x.getResponseBody().write(b);
        x.close();
    }

    @SuppressWarnings("unused")
    static String utf8(byte[] b) { return new String(b, StandardCharsets.UTF_8); }
}
