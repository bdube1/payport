package zw.co.payport.commerce;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

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
 * The PSP's Commerce API, the only way Commerce reaches Payport Payments (server to server, service key). Short
 * timeouts: a slow or absent PSP makes Commerce say "payments are unavailable, try again", never hang a store page.
 */
@Component
public class Psp {

    /** The PSP did not answer (down, unreachable or timed out): nothing happened there that Commerce knows of. */
    public static class Unavailable extends RuntimeException {
        public Unavailable(String m) { super(m); }
    }

    /** The PSP answered and refused (the message is written for the person reading it). */
    public static class Refused extends RuntimeException {
        public final int status;
        public Refused(int status, String m) { super(m); this.status = status; }
    }

    private final Settings settings;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public Psp(Settings settings) {
        this.settings = settings;
    }

    JsonNode call(String method, String path, Object body, String bearer, Duration timeout) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(settings.pspUrl + path)).timeout(timeout)
                    .header("Content-Type", "application/json").header("Accept", "application/json");
            if (bearer != null) b.header("Authorization", "Bearer " + bearer);
            else b.header("X-PayPort-Commerce-Key", settings.pspKey);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode n;
            try {
                n = r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
            } catch (IOException e) {
                n = json.createObjectNode();
            }
            if (r.statusCode() >= 500) throw new Unavailable("Payport Payments answered " + r.statusCode());
            if (r.statusCode() >= 400) {
                String m = n.path("error").path("message").asText("");
                if (m.isBlank()) m = n.path("message").asText("The request was refused");
                throw new Refused(r.statusCode(), m);
            }
            return n.has("data") ? n.path("data") : n;
        } catch (IOException e) {
            throw new Unavailable("Payport Payments cannot be reached");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Interrupted");
        }
    }

    public JsonNode checkout(Map<String, Object> body) {
        return call("POST", "/api/v1/commerce/checkouts", body, null, Duration.ofSeconds(8));
    }

    public JsonNode status(long merchantId, String reference) {
        return call("GET", "/api/v1/commerce/checkouts/" + merchantId + "/" + URLEncoder.encode(reference, StandardCharsets.UTF_8), null, null, Duration.ofSeconds(5));
    }

    public JsonNode refund(Map<String, Object> body) {
        return call("POST", "/api/v1/commerce/refunds", body, null, Duration.ofSeconds(8));
    }

    public JsonNode events(long after, int limit) {
        return call("GET", "/api/v1/commerce/events?after=" + after + "&limit=" + limit, null, null, Duration.ofSeconds(10));
    }

    public JsonNode merchant(long merchantId) {
        return call("GET", "/api/v1/commerce/merchants/" + merchantId, null, null, Duration.ofSeconds(5));
    }

    public JsonNode message(Map<String, Object> body) {
        return call("POST", "/api/v1/commerce/messages", body, null, Duration.ofSeconds(5));
    }

    /** Who a merchant portal login belongs to (the portal's own token, forwarded once at sign-in). */
    public JsonNode identity(String merchantPortalToken) {
        return call("GET", "/api/v1/merchant/commerce/identity", null, merchantPortalToken, Duration.ofSeconds(5));
    }
}
