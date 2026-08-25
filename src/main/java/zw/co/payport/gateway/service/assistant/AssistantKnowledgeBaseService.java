package zw.co.payport.gateway.service.assistant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Loads the Payport Assistant knowledge base (src/main/resources/assistant-kb/*.md)
 * into a single system prompt at startup.
 *
 * Deliberately reads real files rather than embedding content as Java
 * strings -- updating what the assistant knows means editing/adding a
 * markdown file and redeploying, not touching this class. Files are
 * read via classpath pattern scanning (not java.io.File) so this also
 * works correctly when the app is running from a packaged jar, not
 * just when run from exploded classes on disk.
 *
 * Sorted alphabetically, which is why the knowledge base files are
 * numerically prefixed (00-, 01-, 02- ...) -- that numbering IS the
 * ordering the model sees them in, not just a cosmetic filename choice.
 */
@Slf4j
@Service
public class AssistantKnowledgeBaseService {

    private static final String KB_LOCATION_PATTERN = "classpath:assistant-kb/*.md";

    private String systemPrompt;
    private int fileCount;

    @PostConstruct
    void loadKnowledgeBase() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(KB_LOCATION_PATTERN);

            if (resources.length == 0) {
                log.error("[ASSISTANT-KB] No knowledge base files found at {} -- assistant will run with no product knowledge", KB_LOCATION_PATTERN);
                systemPrompt = buildFallbackPrompt();
                return;
            }

            Arrays.sort(resources, Comparator.comparing(Resource::getFilename));

            StringBuilder combined = new StringBuilder();
            combined.append(PROMPT_PREAMBLE);
            combined.append("\n\n");

            for (Resource resource : resources) {
                String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                combined.append("--- ").append(resource.getFilename()).append(" ---\n");
                combined.append(content);
                combined.append("\n\n");
            }

            this.systemPrompt = combined.toString();
            this.fileCount = resources.length;
            log.info("[ASSISTANT-KB] Loaded {} knowledge base files, {} characters total", fileCount, systemPrompt.length());

        } catch (IOException e) {
            log.error("[ASSISTANT-KB] Failed to load knowledge base files: {}", e.getMessage(), e);
            systemPrompt = buildFallbackPrompt();
        }
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public int getFileCount() {
        return fileCount;
    }

    private String buildFallbackPrompt() {
        // Never leave the assistant with a null/empty system prompt --
        // if the KB files genuinely fail to load, it should still know
        // enough to be honest and route to a human, not answer with no
        // grounding at all.
        return PROMPT_PREAMBLE + "\n\nThe knowledge base failed to load. "
                + "You have no specific product information available right now. "
                + "Apologise briefly, say you're unable to look up details at the moment, "
                + "and direct the person to WhatsApp/phone +263 71 918 3557 or "
                + "info@payport.africa for help.";
    }

    private static final String PROMPT_PREAMBLE = """
            You are Payport Assistant, the AI assistant for Payport Financial \
            Services (Pvt) Limited -- a Zimbabwean technology and financial \
            solutions company. You answer questions from website visitors and \
            customers about Payport and its four products: Payport Pay \
            (payments gateway), Payport Books (accounting), Payport Docs \
            (document management), and Payport School (school management).

            Everything below this line is your knowledge base. Answer only \
            from it and from what the person tells you in the conversation -- \
            do not invent facts, prices, dates, or commitments that aren't in \
            this knowledge base. Follow the ground rules in the \
            00-README-assistant-guidance.md file exactly, especially: never \
            invent pricing, never dead-end a question with "I don't know" \
            without also giving a real next step, and route account-specific, \
            legal, or data-rights requests to a human via the contact details \
            provided rather than attempting to resolve them yourself.

            Keep replies conversational and concise -- this is a chat widget, \
            not a document. A few sentences is usually right; use a short list \
            only when the question genuinely calls for one.
            """;
}
