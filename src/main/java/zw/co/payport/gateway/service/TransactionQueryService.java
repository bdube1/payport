package zw.co.payport.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.entity.CheckoutSession;
import zw.co.payport.gateway.entity.QueryMessage;
import zw.co.payport.gateway.entity.TransactionQuery;
import zw.co.payport.gateway.repository.CheckoutSessionRepository;
import zw.co.payport.gateway.repository.QueryMessageRepository;
import zw.co.payport.gateway.repository.TransactionQueryRepository;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * A query is now a proper chronological thread -- both the merchant and
 * ops can add a message at any time, not just one round each. Reopening a
 * resolved query is its own event type in that same thread, so its
 * timestamp appears naturally in order rather than needing a separate
 * field for ops to hunt for.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionQueryService {

    private final TransactionQueryRepository transactionQueryRepository;
    private final CheckoutSessionRepository checkoutSessionRepository;
    private final QueryMessageRepository queryMessageRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public record RaiseQueryRequest(Long checkoutSessionId, String subject, String message) {}

    public TransactionQuery raise(Long merchantId, Long merchantUserId, String merchantUserName, RaiseQueryRequest req) {
        CheckoutSession session = checkoutSessionRepository.findById(req.checkoutSessionId())
                .orElseThrow(() -> new RuntimeException("Transaction not found: " + req.checkoutSessionId()));
        if (!session.getMerchantId().equals(merchantId)) {
            throw new RuntimeException("Transaction not found: " + req.checkoutSessionId());
        }
        if (req.subject() == null || req.subject().isBlank()) {
            throw new RuntimeException("A subject is required");
        }
        if (req.message() == null || req.message().isBlank()) {
            throw new RuntimeException("A message is required");
        }

        TransactionQuery query = TransactionQuery.builder()
                .referenceNumber(generateReferenceNumber())
                .checkoutSessionId(session.getId())
                .merchantId(merchantId)
                .raisedByMerchantUserId(merchantUserId)
                .subject(req.subject().trim())
                .message(req.message().trim())
                .status("OPEN")
                .build();
        transactionQueryRepository.save(query);

        addMessageInternal(query.getId(), "MERCHANT_USER", merchantUserId, merchantUserName, "MESSAGE", req.message().trim());

        log.info("[TX_QUERY] Raised by merchant {} on session {}: {} ({})", merchantId, session.getId(), req.subject(), query.getReferenceNumber());
        return query;
    }

    /** Either side adding a message to an existing thread -- the core of the redesign, replacing the old single-response model. */
    public QueryMessage addMessage(Long queryId, String senderType, Long senderId, String senderName, String message) {
        if (message == null || message.isBlank()) {
            throw new RuntimeException("A message is required");
        }
        TransactionQuery query = getOrThrow(queryId);
        // Sending a fresh message while a query sits RESOLVED implicitly
        // reopens it -- the merchant/ops shouldn't have to make two
        // separate calls (reopen, then message) just to keep talking.
        // The dedicated /reopen endpoint still exists for the explicit
        // "I'm not satisfied" action with its own event marker.
        if ("RESOLVED".equals(query.getStatus())) {
            query.setStatus("OPEN");
            query.setResolvedAt(null);
            transactionQueryRepository.save(query);
        }
        return addMessageInternal(queryId, senderType, senderId, senderName, "MESSAGE", message.trim());
    }

    public TransactionQuery setStatus(Long queryId, String status) {
        if (!"OPEN".equals(status) && !"IN_PROGRESS".equals(status) && !"RESOLVED".equals(status)) {
            throw new RuntimeException("status must be OPEN, IN_PROGRESS, or RESOLVED");
        }
        TransactionQuery query = getOrThrow(queryId);
        query.setStatus(status);
        if ("RESOLVED".equals(status)) {
            query.setResolvedAt(LocalDateTime.now());
        } else {
            query.setResolvedAt(null);
        }
        transactionQueryRepository.save(query);
        return query;
    }

    /**
     * Merchant reopens a query they weren't satisfied with -- a distinct,
     * explicitly-timestamped event in the thread (not just another
     * message), so "when was this reopened" is always answerable at a
     * glance. Deliberately merchant-initiated only (see the controller's
     * ownership check).
     */
    public TransactionQuery reopen(Long queryId, Long merchantId, Long merchantUserId, String merchantUserName, String followUpMessage) {
        TransactionQuery query = getOrThrow(queryId);
        if (!query.getMerchantId().equals(merchantId)) {
            throw new RuntimeException("Query not found: " + queryId);
        }
        if (!"RESOLVED".equals(query.getStatus())) {
            throw new RuntimeException("Only a resolved query can be reopened (current status: " + query.getStatus() + ")");
        }
        query.setStatus("OPEN");
        query.setResolvedAt(null);
        transactionQueryRepository.save(query);

        String reopenText = (followUpMessage != null && !followUpMessage.isBlank())
                ? followUpMessage.trim() : "Reopened -- the previous resolution wasn't satisfactory.";
        addMessageInternal(queryId, "MERCHANT_USER", merchantUserId != null ? merchantUserId : 0L, merchantUserName, "REOPENED", reopenText);

        log.info("[TX_QUERY] {} reopened by merchant {}", queryId, merchantId);
        return query;
    }

    public List<QueryMessage> listMessages(Long queryId) {
        return queryMessageRepository.findByQueryIdOrderByCreatedAtAsc(queryId);
    }

    public List<TransactionQuery> listForSession(Long checkoutSessionId) {
        return transactionQueryRepository.findByCheckoutSessionIdOrderByCreatedAtDesc(checkoutSessionId);
    }

    public List<TransactionQuery> listForMerchant(Long merchantId) {
        return transactionQueryRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    public List<TransactionQuery> listAll() {
        return transactionQueryRepository.findAllByOrderByCreatedAtDesc();
    }

    public TransactionQuery getByReferenceNumber(String referenceNumber) {
        return transactionQueryRepository.findByReferenceNumber(referenceNumber)
                .orElseThrow(() -> new RuntimeException("No query found with reference: " + referenceNumber));
    }

    public TransactionQuery getOrThrow(Long id) {
        return transactionQueryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Query not found: " + id));
    }

    private QueryMessage addMessageInternal(Long queryId, String senderType, Long senderId, String senderName, String eventType, String message) {
        QueryMessage qm = QueryMessage.builder()
                .queryId(queryId)
                .senderType(senderType)
                .senderId(senderId)
                .senderName(senderName)
                .eventType(eventType)
                .message(message)
                .build();
        return queryMessageRepository.save(qm);
    }

    private String generateReferenceNumber() {
        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        int suffix = 1000 + secureRandom.nextInt(9000);
        String candidate = "QRY-" + datePart + "-" + suffix;
        while (transactionQueryRepository.findByReferenceNumber(candidate).isPresent()) {
            suffix = 1000 + secureRandom.nextInt(9000);
            candidate = "QRY-" + datePart + "-" + suffix;
        }
        return candidate;
    }
}
