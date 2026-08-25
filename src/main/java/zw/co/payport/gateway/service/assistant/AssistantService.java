package zw.co.payport.gateway.service.assistant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.entity.AssistantConversation;
import zw.co.payport.gateway.entity.AssistantMessage;
import zw.co.payport.gateway.repository.AssistantConversationRepository;
import zw.co.payport.gateway.repository.AssistantMessageRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates a single assistant chat turn: loads conversation history,
 * calls Gemini, persists both sides, and — critically — degrades
 * gracefully to a real, honest, on-brand reply rather than a broken
 * error whenever the free tier's quota is hit or the call fails for any
 * other reason. The chat widget should never show a raw error state;
 * it should show a normal-looking reply that says how to reach a human.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantService {

    private final AssistantKnowledgeBaseService knowledgeBaseService;
    private final GeminiClient geminiClient;
    private final AssistantConversationRepository conversationRepository;
    private final AssistantMessageRepository messageRepository;

    @Value("${payport.assistant.max-message-length:2000}")
    private int maxMessageLength;

    @Value("${payport.assistant.max-history-turns:20}")
    private int maxHistoryTurns;

    // Gemini 2.5 Flash-Lite's free tier is 1,000 requests/day, shared
    // across every visitor to the site, not per-user. Capping just
    // under that (rather than exactly at it) leaves headroom against
    // any undercounting and anything else sharing the same API key, and
    // means Payport Assistant proactively switches to its fallback
    // reply before Gemini itself starts rejecting calls with a raw 429.
    @Value("${payport.assistant.daily-quota:900}")
    private int dailyQuota;

    private final AtomicInteger dailyRequestCount = new AtomicInteger(0);
    private volatile LocalDate quotaDate = LocalDate.now();

    public ChatResult handleMessage(String conversationId, String rawMessage) {
        String message = sanitiseMessage(rawMessage);
        if (message == null) {
            return new ChatResult(conversationId, "Could you send that again? I didn't get an actual message.", true);
        }

        String convId = (conversationId == null || conversationId.isBlank())
                ? UUID.randomUUID().toString()
                : conversationId;

        AssistantConversation conversation = getOrCreateConversation(convId);

        // Persist the user's message immediately, regardless of what
        // happens next -- even a fallback path should leave a complete,
        // honest record of what was actually asked.
        saveMessage(convId, "USER", message, false);

        if (isOverDailyQuota()) {
            log.warn("[ASSISTANT] Daily Gemini quota ({}) reached -- serving fallback reply without calling the model", dailyQuota);
            return respondWithFallback(conversation, convId, FALLBACK_HIGH_DEMAND);
        }

        if (!geminiClient.isConfigured()) {
            // Genuinely not set up yet (no API key) -- distinct from a
            // quota/outage fallback, but the person asking still
            // deserves a real answer, not a broken widget.
            log.error("[ASSISTANT] Gemini API key not configured -- serving fallback reply");
            return respondWithFallback(conversation, convId, FALLBACK_HIGH_DEMAND);
        }

        try {
            List<GeminiClient.ConversationTurn> turns = buildTurns(convId, message);
            String reply = geminiClient.generateReply(knowledgeBaseService.getSystemPrompt(), turns);

            dailyRequestCount.incrementAndGet();
            saveMessage(convId, "ASSISTANT", reply, false);
            touchConversation(conversation);

            return new ChatResult(convId, reply, false);

        } catch (GeminiClient.GeminiRateLimitException e) {
            return respondWithFallback(conversation, convId, FALLBACK_HIGH_DEMAND);

        } catch (GeminiClient.GeminiCallException e) {
            log.error("[ASSISTANT] Gemini call failed: {}", e.getMessage());
            return respondWithFallback(conversation, convId, FALLBACK_ERROR);
        }
    }

    private ChatResult respondWithFallback(AssistantConversation conversation, String convId, String fallbackText) {
        saveMessage(convId, "ASSISTANT", fallbackText, true);
        touchConversation(conversation);
        return new ChatResult(convId, fallbackText, true);
    }

    private boolean isOverDailyQuota() {
        LocalDate today = LocalDate.now();
        if (!today.equals(quotaDate)) {
            // New day -- reset. Deliberately simple (in-memory, resets
            // on restart too) to match this whole feature's "lightweight
            // free-tier integration" scope, not a distributed quota
            // service.
            quotaDate = today;
            dailyRequestCount.set(0);
        }
        return dailyRequestCount.get() >= dailyQuota;
    }

    private List<GeminiClient.ConversationTurn> buildTurns(String conversationId, String latestMessage) {
        List<AssistantMessage> history = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);

        // Cap history length sent back to the model -- bounds token
        // usage per call and keeps the free tier's TPM limit safe even
        // in an unusually long-running conversation. The oldest turns
        // in a long conversation matter far less than the recent ones.
        int fromIndex = Math.max(0, history.size() - maxHistoryTurns);
        List<AssistantMessage> bounded = history.subList(fromIndex, history.size());

        List<GeminiClient.ConversationTurn> turns = new ArrayList<>();
        for (AssistantMessage m : bounded) {
            // Fallback replies were never actually generated by the
            // model -- replaying them back as if they were "model" turns
            // would teach it a false conversational history.
            if (Boolean.TRUE.equals(m.getWasFallback())) continue;
            String role = "USER".equals(m.getRole()) ? "user" : "model";
            turns.add(new GeminiClient.ConversationTurn(role, m.getContent()));
        }
        turns.add(new GeminiClient.ConversationTurn("user", latestMessage));
        return turns;
    }

    private AssistantConversation getOrCreateConversation(String id) {
        return conversationRepository.findById(id).orElseGet(() -> {
            AssistantConversation c = AssistantConversation.builder()
                    .id(id)
                    .startedAt(LocalDateTime.now())
                    .lastActivityAt(LocalDateTime.now())
                    .messageCount(0)
                    .source("WEBSITE")
                    .build();
            return conversationRepository.save(c);
        });
    }

    private void touchConversation(AssistantConversation conversation) {
        conversation.setLastActivityAt(LocalDateTime.now());
        conversation.setMessageCount(conversation.getMessageCount() + 1);
        conversationRepository.save(conversation);
    }

    private void saveMessage(String conversationId, String role, String content, boolean wasFallback) {
        AssistantMessage message = AssistantMessage.builder()
                .conversationId(conversationId)
                .role(role)
                .content(content)
                .wasFallback(wasFallback)
                .createdAt(LocalDateTime.now())
                .build();
        messageRepository.save(message);
    }

    private String sanitiseMessage(String raw) {
        if (raw == null) return null;
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > maxMessageLength ? trimmed.substring(0, maxMessageLength) : trimmed;
    }

    private static final String FALLBACK_HIGH_DEMAND =
            "I'm getting a lot of questions right now and can't look this one up immediately. "
            + "For a quick answer, WhatsApp or call us on +263 71 918 3557, or email info@payport.africa — "
            + "if you're already using Payport Pay, the in-app Help section can also help.";

    private static final String FALLBACK_ERROR =
            "Sorry, something went wrong on my end answering that. "
            + "WhatsApp or call +263 71 918 3557, or email info@payport.africa and the team will help directly.";

    public record ChatResult(String conversationId, String reply, boolean wasFallback) {}
}
