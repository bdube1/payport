package zw.co.payport.gateway.commerce;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Payport Commerce's store for the app, WhatsApp and USSD (owner, 31 Oct 2026: the four channels are identical). The
 * gateway asks Commerce for stores, items and orders with the shared service key; Commerce places the order exactly as
 * on the website and returns the PSP checkout the channel then pays. If Commerce cannot be reached the store is
 * briefly unavailable on the channel; payments themselves are unaffected.
 */
@Service
public class StoreClient {

    public static class Unavailable extends zw.co.payport.gateway.channels.ChannelUnavailableException {
        public Unavailable() { super("STORE", "The store is not available at the moment. Please try again shortly."); }
    }

    private final String base, key;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json = new ObjectMapper();

    public StoreClient(@Value("${payport.commerce.url:http://127.0.0.1:8086}") String base, @Value("${payport.commerce.key:}") String key) {
        this.base = base == null ? "" : base.replaceAll("/+$", "");
        this.key = key == null ? "" : key.trim();
    }

    public boolean on() {
        return !key.isEmpty() && !base.isEmpty();
    }

    JsonNode call(String method, String path, Object body) {
        if (!on()) throw new Unavailable();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(12))
                    .header("Content-Type", "application/json").header("X-PayPort-Commerce-Key", key);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode n;
            try {
                n = r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
            } catch (IOException e) {
                n = json.createObjectNode();
            }
            if (r.statusCode() >= 500) throw new Unavailable();
            if (r.statusCode() >= 400) {
                String m = n.path("error").path("message").asText("");
                throw new IllegalArgumentException(m.isBlank() ? "The store could not do that" : m);
            }
            return n.has("data") ? n.path("data") : n;
        } catch (IOException e) {
            throw new Unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable();
        }
    }

    static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    public JsonNode stores(String type, String q) {
        return call("GET", "/api/channel/stores?type=" + enc(type) + "&q=" + enc(q), null);
    }

    public JsonNode store(String slug, String collection, String q) {
        return call("GET", "/api/channel/stores/" + enc(slug) + "?c=" + enc(collection) + "&q=" + enc(q), null);
    }

    public JsonNode slots(String slug, Object items) {
        return call("POST", "/api/channel/stores/" + enc(slug) + "/slots", Map.of("items", items));
    }

    public JsonNode quote(Map<String, Object> order) {
        return call("POST", "/api/channel/quote", order);
    }

    public JsonNode order(Map<String, Object> order) {
        return call("POST", "/api/channel/orders", order);
    }

    public JsonNode orders(String phone) {
        return call("GET", "/api/channel/orders?phone=" + enc(phone), null);
    }

    public JsonNode order(String ref, String phone) {
        return call("GET", "/api/channel/orders/" + enc(ref) + "?phone=" + enc(phone), null);
    }

    /** A customer's message to a store's seller (the same conversations as the website). */
    public JsonNode chat(String slug, String phone, String text, String channel, String name) {
        Map<String, Object> b = new java.util.LinkedHashMap<>();
        b.put("store", slug);
        b.put("phone", phone);
        b.put("text", text);
        b.put("channel", channel);
        b.put("name", name);
        return call("POST", "/api/channel/chat", b);
    }

    public JsonNode chat(String slug, String phone, long after) {
        return call("GET", "/api/channel/chat?store=" + enc(slug) + "&phone=" + enc(phone) + "&after=" + after, null);
    }

    /** A customer reports a store (to Payport customer care). */
    public JsonNode report(String slug, String phone, String reason, String details) {
        Map<String, Object> b = new java.util.LinkedHashMap<>();
        b.put("store", slug);
        b.put("phone", phone);
        b.put("reason", reason);
        b.put("details", details);
        return call("POST", "/api/channel/report", b);
    }

    public JsonNode reportReasons() {
        return call("GET", "/api/channel/report/reasons", null);
    }
}
