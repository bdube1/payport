package zw.co.payport.gateway.service.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Claude (Anthropic Messages API) for Payport Assistant, used when ASSISTANT_AI_API_KEY (or the store's
 * COMMERCE_AI_API_KEY) is set. Returns null when it is off, slow or fails, so the caller answers another way.
 */
@Slf4j
@Component
public class ClaudeClient {

    private final String key, model, url;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final ObjectMapper json = new ObjectMapper();

    public ClaudeClient(@Value("${payport.assistant.ai.api-key:}") String key,
                        @Value("${payport.assistant.ai.model:claude-haiku-5-5}") String model,
                        @Value("${payport.assistant.ai.url:https://api.anthropic.com/v1/messages}") String url) {
        this.key = key == null ? "" : key.trim();
        this.model = model;
        this.url = url;
    }

    public boolean isConfigured() {
        return !key.isEmpty();
    }

    /** One reply for the conversation (turns: role user|model, oldest first, ending with the user's message). */
    public String reply(String system, List<GeminiClient.ConversationTurn> turns) {
        if (!isConfigured()) return null;
        try {
            List<Map<String, Object>> messages = new ArrayList<>();
            for (GeminiClient.ConversationTurn t : turns) {
                String role = "model".equals(t.role()) ? "assistant" : "user";
                // the API needs alternating roles starting with the user: merge repeats
                if (!messages.isEmpty() && role.equals(messages.get(messages.size() - 1).get("role"))) {
                    Map<String, Object> last = messages.get(messages.size() - 1);
                    last.put("content", last.get("content") + "\n\n" + t.text());
                } else if (messages.isEmpty() && "assistant".equals(role)) {
                    continue;
                } else {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("role", role);
                    m.put("content", t.text());
                    messages.add(m);
                }
            }
            if (messages.isEmpty()) return null;
            Map<String, Object> body = Map.of("model", model, "max_tokens", 600, "system", system, "messages", messages);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                    .header("content-type", "application/json").header("x-api-key", key).header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                log.warn("[ASSISTANT] Claude answered HTTP {}", res.statusCode());
                return null;
            }
            StringBuilder out = new StringBuilder();
            for (JsonNode c : json.readTree(res.body()).path("content")) if ("text".equals(c.path("type").asText())) out.append(c.path("text").asText());
            String text = out.toString().trim();
            return text.isEmpty() ? null : text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("[ASSISTANT] Claude call failed: {}", e.getMessage());
            return null;
        }
    }
}
