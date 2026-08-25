package zw.co.payport.gateway.service.assistant;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Thin REST client for Google's Gemini API (generateContent).
 *
 * Deliberately provider-specific and isolated behind AssistantService's
 * own interface usage -- if Payport ever moves off the free Gemini tier
 * (to a paid tier, or a different provider entirely), this is the only
 * class that needs to change. Same isolation principle as every payment
 * provider adaptor in this codebase.
 *
 * REST shape confirmed directly against Google's current API docs
 * (generativelanguage.googleapis.com/v1beta), not assumed from memory:
 *   POST /v1beta/models/{model}:generateContent
 *   header: x-goog-api-key
 *   body: { "system_instruction": {...}, "contents": [ {role, parts[]}, ... ] }
 */
@Slf4j
@Component
public class GeminiClient {

    @Value("${payport.assistant.gemini.api-key:}")
    private String apiKey;

    @Value("${payport.assistant.gemini.model:gemini-2.5-flash-lite}")
    private String model;

    @Value("${payport.assistant.gemini.base-url:https://generativelanguage.googleapis.com/v1beta}")
    private String baseUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * @param systemInstruction the full knowledge-base system prompt
     * @param turns             conversation history + latest user message,
     *                          oldest first, each as {role: "user"|"model", text: "..."}
     */
    public String generateReply(String systemInstruction, List<ConversationTurn> turns) throws GeminiRateLimitException, GeminiCallException {
        if (!isConfigured()) {
            throw new GeminiCallException("Gemini API key is not configured (payport.assistant.gemini.api-key / GEMINI_API_KEY)");
        }

        String url = baseUrl + "/models/" + model + ":generateContent";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("system_instruction", Map.of("parts", List.of(Map.of("text", systemInstruction))));

        List<Map<String, Object>> contents = new ArrayList<>();
        for (ConversationTurn turn : turns) {
            contents.add(Map.of(
                    "role", turn.role(),
                    "parts", List.of(Map.of("text", turn.text()))
            ));
        }
        body.put("contents", contents);

        // Keep replies conversational, not essay-length -- this is a
        // chat widget. maxOutputTokens is a hard ceiling, not a target;
        // the system prompt's own "keep replies concise" instruction
        // does the actual length-shaping.
        body.put("generationConfig", Map.of(
                "temperature", 0.4,
                "maxOutputTokens", 500
        ));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", apiKey);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
            return extractText(response.getBody());

        } catch (HttpClientErrorException.TooManyRequests e) {
            // HTTP 429 / RESOURCE_EXHAUSTED -- the free tier's RPM/RPD/TPM
            // limit has been hit. This is expected, routine behaviour for
            // a free-tier integration under real load, not a bug to log
            // as an error every time.
            log.warn("[GEMINI] Rate limited (429) -- free tier quota likely exhausted for this window");
            throw new GeminiRateLimitException("Gemini API rate limit hit");

        } catch (HttpClientErrorException e) {
            log.error("[GEMINI] API call failed: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new GeminiCallException("Gemini API error: " + e.getStatusCode());

        } catch (Exception e) {
            log.error("[GEMINI] Unexpected error calling Gemini API: {}", e.getMessage(), e);
            throw new GeminiCallException("Unexpected error calling Gemini API: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String extractText(Map<String, Object> responseBody) throws GeminiCallException {
        if (responseBody == null) {
            throw new GeminiCallException("Empty response body from Gemini API");
        }

        // Response finished for a non-content reason (e.g. safety filter
        // blocked it) -- candidates may be present with no usable text,
        // or promptFeedback may explain why nothing came back at all.
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) responseBody.get("candidates");
        if (candidates == null || candidates.isEmpty()) {
            Map<String, Object> promptFeedback = (Map<String, Object>) responseBody.get("promptFeedback");
            log.warn("[GEMINI] No candidates in response. promptFeedback={}", promptFeedback);
            throw new GeminiCallException("No candidates returned by Gemini API");
        }

        Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
        if (content == null) {
            throw new GeminiCallException("No content in Gemini API candidate");
        }

        List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
        if (parts == null || parts.isEmpty()) {
            throw new GeminiCallException("No parts in Gemini API content");
        }

        StringBuilder text = new StringBuilder();
        for (Map<String, Object> part : parts) {
            Object t = part.get("text");
            if (t != null) text.append(t);
        }

        if (text.isEmpty()) {
            throw new GeminiCallException("Gemini API returned no text content");
        }

        return text.toString();
    }

    public record ConversationTurn(String role, String text) {}

    public static class GeminiRateLimitException extends Exception {
        public GeminiRateLimitException(String message) { super(message); }
    }

    public static class GeminiCallException extends Exception {
        public GeminiCallException(String message) { super(message); }
    }
}
