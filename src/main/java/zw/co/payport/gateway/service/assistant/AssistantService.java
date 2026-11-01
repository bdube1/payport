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
 * One assistant chat turn. A payment reference is answered with the payment's real status; greetings and thanks
 * get a short reply; everything else is answered by the AI (Claude, else Gemini) grounded on the knowledge base and
 * the answers that best fit the question, or, when no AI is set up, busy or failing, by Payport's own answer engine
 * (AssistantBrain), so the customer always gets a real answer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantService {

    private final AssistantKnowledgeBaseService knowledgeBaseService;
    private final GeminiClient geminiClient;
    private final ClaudeClient claudeClient;
    private final AssistantBrain brain;
    private final zw.co.payport.gateway.repository.TransactionRepository transactions;
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

        // Live facts first: a payment reference gets its real status (never guessed by a model).
        String lookup = paymentLookup(message);
        String quick = lookup != null ? lookup : smallTalk(message);
        if (quick != null) {
            saveMessage(convId, "ASSISTANT", quick, false);
            touchConversation(conversation);
            return new ChatResult(convId, quick, false);
        }

        String own = ownAnswer(message);
        boolean aiOn = claudeClient.isConfigured() || geminiClient.isConfigured();
        if (!aiOn || isOverDailyQuota()) return answerWith(conversation, convId, own);

        List<GeminiClient.ConversationTurn> turns = buildTurns(convId, message);
        String system = groundedPrompt(message);
        String reply = null;
        if (claudeClient.isConfigured()) reply = claudeClient.reply(system, turns);
        if (reply == null && geminiClient.isConfigured()) {
            try {
                reply = geminiClient.generateReply(system, turns);
            } catch (GeminiClient.GeminiRateLimitException e) {
                log.warn("[ASSISTANT] Gemini rate limited; answering from the knowledge base");
            } catch (GeminiClient.GeminiCallException e) {
                log.error("[ASSISTANT] Gemini call failed: {}", e.getMessage());
            }
        }
        if (reply == null || reply.isBlank()) return answerWith(conversation, convId, own);
        dailyRequestCount.incrementAndGet();
        saveMessage(convId, "ASSISTANT", reply.trim(), false);
        touchConversation(conversation);
        return new ChatResult(convId, reply.trim(), false);
    }

    /** The knowledge base's own answer (no AI), or the topics it can help with. */
    String ownAnswer(String message) {
        return brain.answer(message).map(m -> m.entry().answer()).orElse(null);
    }

    private ChatResult answerWith(AssistantConversation conversation, String convId, String own) {
        if (own == null) return respondWithFallback(conversation, convId, NOT_SURE);
        saveMessage(convId, "ASSISTANT", own, false);
        touchConversation(conversation);
        return new ChatResult(convId, own, false);
    }

    /** The full knowledge base plus the answers that best fit this question, so the model answers from them. */
    private String groundedPrompt(String message) {
        StringBuilder b = new StringBuilder(knowledgeBaseService.getSystemPrompt());
        List<AssistantBrain.Match> best = brain.search(message, 4);
        if (!best.isEmpty()) {
            b.append("\n\n--- Answers that best fit the latest question (use them; they are current) ---\n");
            for (AssistantBrain.Match m : best) b.append("Q: ").append(m.entry().question()).append("\nA: ").append(m.entry().answer()).append("\n\n");
        }
        b.append(ANSWER_RULES);
        return b.toString();
    }

    private static final java.util.regex.Pattern REF = java.util.regex.Pattern.compile("(?i)\\b(PP-[A-Z0-9][A-Z0-9-]{5,60})\\b");

    /** "Where is my payment PP-...": the payment's real status in plain words (no names or numbers of other people). */
    String paymentLookup(String message) {
        java.util.regex.Matcher m = REF.matcher(message);
        if (!m.find()) return null;
        String ref = m.group(1).toUpperCase(java.util.Locale.ROOT);
        var found = transactions.findByReference(ref);
        if (found.isEmpty()) return "I could not find a payment with the reference " + ref + ". Check the reference (it is in the SMS or on the receipt) and send it again, "
                + "or WhatsApp +263 71 918 3557 for help.";
        var tx = found.get();
        String what = tx.getProductName() != null ? tx.getProductName() : tx.getProductType() != null ? tx.getProductType().getName() : "Payment";
        String money = tx.getAmountFormatted() == null ? "" : " " + tx.getAmountFormatted();
        String reason = tx.getFailureReason() == null || tx.getFailureReason().isBlank() ? null : tx.getFailureReason().trim();
        String head = what + money + " (" + ref + "): ";
        return head + switch (tx.getStatus()) {
            case COMPLETED -> "successful.";
            case INITIATED -> "not paid yet. Nothing was taken.";
            case PAYMENT_PENDING -> "waiting for the payment to be approved on the paying phone.";
            case PAYMENT_CONFIRMED, FULFILMENT_PENDING, FULFILMENT_RETRY -> "the payment was approved and the purchase is being completed now. Do not pay again; the result is sent by SMS.";
            case PAYMENT_FAILED -> "failed" + (reason == null ? "." : ": " + reason + (reason.endsWith(".") ? "" : ".")) + " No money was taken.";
            case PAYMENT_TIMEOUT -> "failed: the payment was not approved in time. If money left your account it is refunded automatically.";
            case FULFILMENT_FAILED, MANUAL_FULFILMENT -> "failed" + (reason == null ? "." : ": " + reason + (reason.endsWith(".") ? "" : "."))
                    + " Your payment will be refunded.";
            case REFUND_PENDING -> "your money is being refunded.";
            case REFUNDED -> "refunded.";
            case CANCELLED -> "cancelled. Nothing was taken.";
            default -> tx.getStatus().name().replace('_', ' ').toLowerCase(java.util.Locale.ROOT) + ".";
        };
    }

    private static final java.util.Set<String> GREETINGS = java.util.Set.of("hi", "hie", "hello", "hey", "helo", "hallo", "hy", "mhoro", "mhoroi", "makadii",
            "manheru", "mangwanani", "masikati", "sawubona", "salibonani", "good morning", "good afternoon", "good evening", "morning", "yo");
    private static final java.util.Set<String> THANKS = java.util.Set.of("thanks", "thank you", "thank u", "thanx", "thx", "ok thanks", "ok thank you",
            "tatenda", "ndatenda", "siyabonga", "ngiyabonga", "great", "cool", "ok", "okay", "alright", "noted");

    /** Greetings and thanks get a short, friendly answer. */
    String smallTalk(String message) {
        String m = message.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
        if (GREETINGS.contains(m) || m.matches("(hi|hello|hey|hie|mhoro|mhoroi) (there|payport|team|guys|assistant)")) return GREETING;
        if (THANKS.contains(m)) return "You're welcome. Ask me anything else about Payport.";
        return null;
    }

    private static final String GREETING = "Hello! I'm Payport Assistant. I can help with airtime, data, ZESA and other bills, the Payport wallet, "
            + "Payport Store, payments that failed, refunds, your PIN and businesses using Payport. Send a payment reference (PP-...) and I'll check it. What can I help with?";

    private static final String NOT_SURE = "I'm not sure I understood that. I can help with airtime and data, ZESA and other bills, the Payport wallet "
            + "(top up, send money, cash out), Payport Store, failed payments and refunds, your PIN, and businesses using Payport. "
            + "Send a payment reference (PP-...) to check a payment. For anything else, WhatsApp or call +263 71 918 3557 or email info@payport.africa.";

    private static final String ANSWER_RULES = """

            --- How to answer ---
            Answer the customer's question directly in a few sentences, from the knowledge base and the answers above. If the \
            question is about something Payport does not offer, say so plainly and suggest the closest thing Payport does. Payments \
            are real-time: a payment is successful or failed; never say a purchase "is being delivered". Never invent prices, fees, \
            limits, dates or phone numbers. Never ask for or accept a PIN, password or one-time code. If a question needs the \
            customer's own account, give the steps or the contact (WhatsApp or call +263 71 918 3557, info@payport.africa). \
            Reply in the customer's language when they write in Shona or Ndebele. No markdown headings.
            """;

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
        // the latest message is already saved: it is added once, at the end
        if (!history.isEmpty() && "USER".equals(history.get(history.size() - 1).getRole())
                && latestMessage.equals(history.get(history.size() - 1).getContent())) history = history.subList(0, history.size() - 1);
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

    public record ChatResult(String conversationId, String reply, boolean wasFallback) {}
}
