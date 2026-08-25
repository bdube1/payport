package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.service.assistant.AssistantService;

import java.util.Map;

/**
 * Payport Assistant chat endpoint — public, no auth required, used by
 * the website's chat widget (and any future client that wants the same
 * assistant). Rate-limited per-IP by RateLimitFilter; see AssistantService
 * for the separate global daily-quota guard that protects the free-tier
 * Gemini quota across all visitors combined.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private final AssistantService assistantService;

    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<Map<String, Object>>> chat(
            @RequestBody ChatRequest request,
            HttpServletRequest httpRequest) {

        String requestId = getRequestId(httpRequest);
        log.info("[ASSISTANT] Chat message received, conversationId={}", request.conversationId());

        AssistantService.ChatResult result = assistantService.handleMessage(request.conversationId(), request.message());

        Map<String, Object> data = Map.of(
                "conversationId", result.conversationId(),
                "reply", result.reply(),
                "wasFallback", result.wasFallback()
        );

        return ResponseEntity.ok(ApiResponse.success(data, requestId));
    }

    public record ChatRequest(String conversationId, String message) {}

    private String getRequestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : java.util.UUID.randomUUID().toString();
    }
}
