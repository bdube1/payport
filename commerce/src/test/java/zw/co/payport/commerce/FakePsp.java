package zw.co.payport.commerce;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** A stand-in for PayPort Payments' Commerce API: checkouts, status, refunds, events, identity; can be switched off. */
class FakePsp {

    static final String KEY = "fake-psp-commerce-key-0123456789";
    final ObjectMapper json = new ObjectMapper();
    final HttpServer server;
    volatile boolean down;
    final AtomicInteger creates = new AtomicInteger();
    final AtomicLong ids = new AtomicLong(500);
    final Map<String, Map<String, Object>> sessions = new ConcurrentHashMap<>();   // merchant:reference
    final List<Map<String, Object>> events = new ArrayList<>();
    final Map<String, Long> users = Map.of("portal-token-A", 1001L, "portal-token-B", 2002L, "portal-token-C", 3003L);
    volatile String lastMethod;
    final java.util.Set<String> usedCodes = java.util.concurrent.ConcurrentHashMap.newKeySet();

    FakePsp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    synchronized void event(String type, long merchant, String reference) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", (long) events.size() + 1);
        e.put("eventId", UUID.randomUUID().toString());
        e.put("type", type);
        e.put("merchantId", merchant);
        e.put("payload", Map.of("reference", reference));
        events.add(e);
    }

    /** The PSP's side of a payment: status and outcome of one checkout, and an event about it. */
    void set(long merchant, String reference, String status, String outcome) {
        Map<String, Object> s = sessions.get(merchant + ":" + reference);
        s.put("status", status);
        s.put("outcome", outcome);
        event("checkout." + status.toLowerCase(), merchant, reference);
    }

    void refundPaid(long merchant, String reference) {
        Map<String, Object> s = sessions.get(merchant + ":" + reference);
        @SuppressWarnings("unchecked") List<Map<String, Object>> r = (List<Map<String, Object>>) s.get("refunds");
        for (Map<String, Object> x : r) x.put("payoutStatus", "EXECUTED");
        event("refund.completed", merchant, reference);
    }

    /** The methods each merchant accepts (tests switch them). */
    final Map<Long, List<String>> accepted = new ConcurrentHashMap<>();
    final List<String> messages = java.util.Collections.synchronizedList(new ArrayList<>());

    List<Map<String, Object>> methods(long m) {
        List<String> on = accepted.getOrDefault(m, List.of());
        List<Map<String, Object>> out = new ArrayList<>();
        for (String c : List.of("ECOCASH", "ONEMONEY", "OMARI", "INNBUCKS", "ZIMSWITCH")) out.add(Map.of("code", c, "name", c, "enabled", on.contains(c)));
        return out;
    }

    void handle(HttpExchange x) throws IOException {
        try {
            if (down) {
                x.close();   // no answer at all
                return;
            }
            String path = x.getRequestURI().getPath(), method = x.getRequestMethod();
            if (path.equals("/api/v1/merchant/commerce/identity")) {
                String auth = x.getRequestHeaders().getFirst("Authorization");
                Long m = auth == null ? null : users.get(auth.replace("Bearer ", ""));
                if (m == null) { send(x, 401, Map.of("success", false, "error", Map.of("message", "Sign in"))); return; }
                send(x, 200, Map.of("success", true, "data", Map.of("merchantId", m, "businessName", m == 1001L ? "Alpha Crafts" : m == 2002L ? "Bravo Foods" : "Charlie Salon", "status", "ACTIVE",
                        "currencies", List.of("USD", "ZWG"), "userId", 7, "userName", "Owner", "role", "OWNER", "methods", methods(m))));
                return;
            }
            if (!KEY.equals(x.getRequestHeaders().getFirst("X-PayPort-Commerce-Key"))) { send(x, 403, Map.of("success", false, "error", Map.of("message", "Wrong service key"))); return; }
            if (method.equals("POST") && path.equals("/api/v1/commerce/checkouts")) {
                JsonNode b = json.readTree(x.getRequestBody());
                String k = b.path("merchantId").asLong() + ":" + b.path("reference").asText();
                Map<String, Object> s = sessions.computeIfAbsent(k, kk -> {
                    creates.incrementAndGet();
                    long id = ids.incrementAndGet();
                    Map<String, Object> n = new ConcurrentHashMap<>();
                    n.put("checkoutSessionId", id);
                    n.put("reference", b.path("reference").asText());
                    n.put("status", "CREATED");
                    n.put("outcome", "NOT_STARTED");
                    n.put("amountMinor", b.path("amountMinor").asLong());
                    n.put("currency", b.path("currency").asText());
                    n.put("checkoutUrl", "https://app.test/checkout/" + id);
                    lastMethod = b.path("method").asText(null);
                    n.put("refunds", new ArrayList<Map<String, Object>>());
                    return n;
                });
                send(x, 200, Map.of("success", true, "data", s));
                return;
            }
            if (method.equals("GET") && path.startsWith("/api/v1/commerce/checkouts/")) {
                String[] p = path.split("/");
                Map<String, Object> s = sessions.get(p[5] + ":" + p[6]);
                if (s == null) { send(x, 400, Map.of("success", false, "error", Map.of("message", "No such checkout"))); return; }
                send(x, 200, Map.of("success", true, "data", s));
                return;
            }
            if (method.equals("POST") && path.equals("/api/v1/commerce/refunds")) {
                JsonNode b = json.readTree(x.getRequestBody());
                Map<String, Object> s = sessions.get(b.path("merchantId").asLong() + ":" + b.path("reference").asText());
                if (s == null) { send(x, 400, Map.of("success", false, "error", Map.of("message", "No such checkout"))); return; }
                @SuppressWarnings("unchecked") List<Map<String, Object>> r = (List<Map<String, Object>>) s.get("refunds");
                long id = ids.incrementAndGet();
                Map<String, Object> rf = new ConcurrentHashMap<>(Map.of("id", id, "amountMinor", b.path("amountMinor").asLong(), "payoutStatus", "AWAITING_PAYOUT"));
                r.add(rf);
                Map<String, Object> out = new LinkedHashMap<>(s);
                out.put("refundId", id);
                send(x, 200, Map.of("success", true, "data", out));
                return;
            }
            if (method.equals("POST") && path.equals("/api/v1/commerce/customer-code")) {
                JsonNode b = json.readTree(x.getRequestBody());
                String code = b.path("code").asText();
                Map<String, Object> who = "c0de".equals(code) ? Map.of("userId", 501, "name", "Rudo Moyo", "phone", "+263776000333")
                        : "c0de-loyal".equals(code) ? Map.of("userId", 502, "name", "Tino Dube", "phone", "+263777000444") : null;
                if (who == null || !usedCodes.add(code)) { send(x, 400, Map.of("success", false, "error", Map.of("message", "Sign in again"))); return; }
                send(x, 200, Map.of("success", true, "data", who));
                return;
            }
            if (method.equals("GET") && path.startsWith("/api/v1/commerce/merchants/")) {
                long m = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
                send(x, 200, Map.of("success", true, "data", Map.of("merchantId", m, "status", "ACTIVE", "methods", methods(m))));
                return;
            }
            if (method.equals("POST") && path.equals("/api/v1/commerce/messages")) {
                JsonNode b = json.readTree(x.getRequestBody());
                messages.add(b.path("to").asText() + "|" + b.path("text").asText());
                send(x, 200, Map.of("success", true, "data", Map.of("queued", true)));
                return;
            }
            if (method.equals("GET") && path.equals("/api/v1/commerce/events")) {
                long after = Long.parseLong(x.getRequestURI().getQuery().replaceAll(".*after=(\\d+).*", "$1"));
                List<Map<String, Object>> out = new ArrayList<>();
                synchronized (this) {
                    for (Map<String, Object> e : events) if ((Long) e.get("id") > after) out.add(e);
                }
                send(x, 200, Map.of("success", true, "data", out));
                return;
            }
            send(x, 404, Map.of("success", false));
        } catch (Exception e) {
            send(x, 500, Map.of("success", false, "error", Map.of("message", String.valueOf(e.getMessage()))));
        }
    }

    void send(HttpExchange x, int status, Object body) throws IOException {
        byte[] b = json.writeValueAsBytes(body);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(status, b.length);
        x.getResponseBody().write(b);
        x.close();
    }
}
