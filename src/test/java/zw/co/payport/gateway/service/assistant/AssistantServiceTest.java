package zw.co.payport.gateway.service.assistant;

import org.junit.jupiter.api.Test;
import zw.co.payport.gateway.entity.Transaction;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.repository.AssistantConversationRepository;
import zw.co.payport.gateway.repository.AssistantMessageRepository;
import zw.co.payport.gateway.repository.TransactionRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Payport Assistant always answers: live payment status, small talk, and the knowledge base without any AI service. */
class AssistantServiceTest {

    final TransactionRepository transactions = mock(TransactionRepository.class);
    final AssistantConversationRepository conversations = mock(AssistantConversationRepository.class);
    final AssistantMessageRepository messages = mock(AssistantMessageRepository.class);
    final GeminiClient gemini = mock(GeminiClient.class);
    final ClaudeClient claude = new ClaudeClient("", "m", "http://localhost");
    final AssistantService service = new AssistantService(mock(AssistantKnowledgeBaseService.class), gemini, claude, new AssistantBrain(),
            transactions, conversations, messages);

    AssistantServiceTest() {
        when(conversations.findById(any())).thenReturn(Optional.empty());
        when(conversations.save(any())).thenAnswer(i -> i.getArgument(0));
        when(messages.findByConversationIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(gemini.isConfigured()).thenReturn(false);
    }

    private Transaction tx(TransactionStatus s, String reason) {
        Transaction t = mock(Transaction.class);
        when(t.getStatus()).thenReturn(s);
        when(t.getProductName()).thenReturn("Econet Airtime");
        when(t.getAmountFormatted()).thenReturn("USD 0.50");
        when(t.getFailureReason()).thenReturn(reason);
        return t;
    }

    @Test
    void aPaymentReferenceGetsItsRealStatus() {
        Transaction ok = tx(TransactionStatus.COMPLETED, null);
        when(transactions.findByReference("PP-WA-20261101-A47976EE")).thenReturn(Optional.of(ok));
        String r = service.handleMessage(null, "where is my airtime pp-wa-20261101-a47976ee").reply();
        assertEquals("Econet Airtime USD 0.50 (PP-WA-20261101-A47976EE): successful.", r);

        Transaction failed = tx(TransactionStatus.FULFILMENT_FAILED, "Invalid subscriber");
        when(transactions.findByReference("PP-APP-1234567")).thenReturn(Optional.of(failed));
        String f = service.handleMessage(null, "PP-APP-1234567").reply();
        assertTrue(f.contains("failed: Invalid subscriber.") && f.contains("will be refunded"), f);

        assertTrue(service.handleMessage(null, "PP-NOPE-999999").reply().startsWith("I could not find a payment"));
    }

    @Test
    void answersFromTheKnowledgeBaseWithoutAnyAi() {
        AssistantService.ChatResult r = service.handleMessage(null, "how do i buy zesa tokens?");
        assertFalse(r.wasFallback());
        assertTrue(r.reply().contains("meter number"), r.reply());
    }

    @Test
    void greetsAndOffersHelp_andAdmitsWhenItIsNotSure() {
        assertTrue(service.handleMessage(null, "Hi").reply().startsWith("Hello! I'm Payport Assistant"));
        AssistantService.ChatResult unsure = service.handleMessage(null, "what is the capital of france");
        assertTrue(unsure.wasFallback() && unsure.reply().contains("+263 71 918 3557"), unsure.reply());
    }
}
