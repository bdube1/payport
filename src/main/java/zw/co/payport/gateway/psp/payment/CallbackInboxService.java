package zw.co.payport.gateway.psp.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.psp.common.BreakReporter;
import zw.co.payport.gateway.psp.ledger.domain.ChainHasher;
import zw.co.payport.gateway.psp.legacy.LegacyGateway;
import zw.co.payport.gateway.psp.legacy.LegacyPayments;
import zw.co.payport.gateway.service.adaptor.PaymentProviderAdaptor;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Provider callbacks, made reliable and trustworthy.
 *
 * 1. Persist first. The raw callback is written to psp_callback_inbox in its
 *    own transaction before anything else happens. If processing then fails,
 *    a scheduler retries it; the old controller returned 200 and dropped it.
 * 2. Deduplicate. Identical bodies from the same provider are counted, not re-applied.
 * 3. Verify. EcoCash and OneMoney callbacks carry no signature PayPort can
 *    check, so a SUCCESS callback is only applied after asking the provider's
 *    status API directly (verify-by-query). A FAILED callback cannot create
 *    money, so it is applied as-is. Amounts must match the payment exactly.
 * 4. Escalate. Contradictions (callback says SUCCESS, provider says FAILED;
 *    amount mismatch; repeated processing errors) open reconciliation breaks.
 */
@Service
public class CallbackInboxService {

    private static final Logger log = LoggerFactory.getLogger(CallbackInboxService.class);
    private static final int MAX_ATTEMPTS = 10;
    private static final Set<String> SENSITIVE_HEADERS = Set.of("authorization", "cookie", "x-api-key");

    public record Ack(int httpStatus, String body) {}

    private final CallbackInboxRepository inbox;
    private final LegacyPayments payments;
    private final LegacyGateway gateway;
    private final BreakReporter breaks;
    private final ObjectMapper om;
    private final TransactionTemplate newTx;

    /** Providers whose callbacks cannot be authenticated and must be confirmed by a status query. */
    @Value("${payport.psp.callbacks.verify-by-query:ECOCASH,ONEMONEY,TELECASH}")
    private String verifyByQuery = "ECOCASH,ONEMONEY,TELECASH";

    public CallbackInboxService(CallbackInboxRepository inbox, LegacyPayments payments, LegacyGateway gateway,
                                BreakReporter breaks, ObjectMapper om, PlatformTransactionManager txManager) {
        this.inbox = inbox;
        this.payments = payments;
        this.gateway = gateway;
        this.breaks = breaks;
        this.om = om;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Entry point from the HTTP callback controllers. Always returns quickly with the provider's expected ack. */
    public Ack receive(String provider, String rawBody, Map<String, String> headers, String remoteIp, String successAck) {
        String p = provider.toUpperCase();
        if (rawBody == null || rawBody.isBlank()) return new Ack(400, "EMPTY");
        // Validate against the ORIGINAL headers (stored copies redact Authorization etc.).
        Boolean signatureValid = null;
        String signatureError = null;
        if (payments.hasPaymentAdaptor(p)) {
            try {
                PaymentProviderAdaptor.CallbackValidationResult v = payments.validateCallback(p, headers == null ? Map.of() : headers, rawBody);
                signatureValid = v.isValid();
                signatureError = v.getFailureReason();
            } catch (Exception ex) {
                signatureValid = false;
                signatureError = ex.getMessage();
            }
        }
        Long id = store(p, rawBody, headers, remoteIp, signatureValid, signatureError);
        if (id == null) {
            return new Ack(200, successAck); // duplicate delivery, already stored
        }
        String outcome = process(id);
        if ("REJECTED_SIGNATURE".equals(outcome)) return new Ack(403, "INVALID_SIGNATURE");
        return new Ack(200, successAck);
    }

    private Long store(String provider, String rawBody, Map<String, String> headers, String remoteIp,
                       Boolean signatureValid, String signatureError) {
        String hash = ChainHasher.sha256(rawBody);
        try {
            return newTx.execute(s -> {
                CallbackInboxEntry e = new CallbackInboxEntry();
                e.setProvider(provider);
                e.setBodyHash(hash);
                e.setRawBody(rawBody);
                e.setHeaders(safeHeaders(headers));
                e.setRemoteIp(remoteIp);
                e.setReceivedAt(LocalDateTime.now());
                e.setDuplicateCount(0);
                e.setStatus("RECEIVED");
                e.setSignatureValid(signatureValid);
                e.setLastError(Boolean.FALSE.equals(signatureValid) ? "Validation failed: " + signatureError : null);
                e.setAttempts(0);
                e.setNextAttemptAt(LocalDateTime.now());
                return inbox.saveAndFlush(e).getId();
            });
        } catch (DataIntegrityViolationException dup) {
            newTx.executeWithoutResult(s -> inbox.findByProviderAndBodyHash(provider, hash).ifPresent(e -> {
                e.setDuplicateCount(e.getDuplicateCount() + 1);
                inbox.save(e);
            }));
            log.info("[CALLBACK-INBOX] Duplicate {} callback (hash {}) ignored", provider, hash.substring(0, 12));
            return null;
        }
    }

    /** Processes one stored callback. Returns a short outcome code. Never throws. */
    public String process(Long id) {
        CallbackInboxEntry e = inbox.findById(id).orElse(null);
        if (e == null || "PROCESSED".equals(e.getStatus()) || "IGNORED".equals(e.getStatus()) || "REJECTED".equals(e.getStatus())) {
            return "SKIPPED";
        }
        String outcome;
        String error = null;
        try {
            outcome = apply(e);
        } catch (Exception ex) {
            outcome = "ERROR";
            error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            log.error("[CALLBACK-INBOX] Processing {} callback #{} failed: {}", e.getProvider(), id, error);
        }
        final String o = outcome;
        final String err = error;
        newTx.executeWithoutResult(s -> {
            CallbackInboxEntry fresh = inbox.findById(id).orElseThrow();
            fresh.setAttempts(fresh.getAttempts() + 1);
            fresh.setVerification(e.getVerification());
            fresh.setSignatureValid(e.getSignatureValid());
            fresh.setPayportReference(e.getPayportReference());
            fresh.setProviderReference(e.getProviderReference());
            fresh.setParsedStatus(e.getParsedStatus());
            fresh.setParsedAmountMinor(e.getParsedAmountMinor());
            switch (o) {
                case "APPLIED", "DUPLICATE_STATE" -> { fresh.setStatus("PROCESSED"); fresh.setProcessedAt(LocalDateTime.now()); fresh.setLastError(null); }
                case "IGNORED", "UNKNOWN_REFERENCE", "PENDING_STATUS" -> { fresh.setStatus("IGNORED"); fresh.setProcessedAt(LocalDateTime.now()); }
                case "REJECTED_SIGNATURE", "REJECTED_CONTRADICTED", "REJECTED_AMOUNT" -> { fresh.setStatus("REJECTED"); fresh.setProcessedAt(LocalDateTime.now()); }
                case "DEFERRED" -> {
                    // Provider could not confirm yet: the uncertain-payment resolver keeps polling the provider.
                    fresh.setStatus("DEFERRED");
                    fresh.setProcessedAt(LocalDateTime.now());
                }
                default -> {
                    fresh.setLastError(err != null && err.length() > 2000 ? err.substring(0, 2000) : err);
                    if (fresh.getAttempts() >= MAX_ATTEMPTS) {
                        fresh.setStatus("FAILED");
                        breaks.report("CALLBACK", "CALLBACK_PROCESSING_FAILED", fresh.getProvider() + ":" + fresh.getId(), null,
                                null, null, "Callback could not be processed after " + MAX_ATTEMPTS + " attempts: " + err);
                    } else {
                        fresh.setStatus("RETRY");
                        fresh.setNextAttemptAt(LocalDateTime.now().plus(PollingPolicy.inboxRetryDelay(fresh.getAttempts())));
                    }
                }
            }
            inbox.save(fresh);
        });
        return outcome;
    }

    private String apply(CallbackInboxEntry e) throws Exception {
        String provider = e.getProvider();
        if (!payments.hasPaymentAdaptor(provider)) return "IGNORED";

        if (!Boolean.TRUE.equals(e.getSignatureValid())) {
            breaks.report("CALLBACK", "INVALID_CALLBACK_SIGNATURE", provider + ":" + e.getId(), null, null, null,
                    "Callback failed provider validation (" + e.getLastError() + ") from " + e.getRemoteIp());
            return "REJECTED_SIGNATURE";
        }

        PaymentProviderAdaptor.CallbackEvent ev = payments.parseCallback(provider, e.getRawBody());
        e.setPayportReference(ev.getPayportReference());
        e.setProviderReference(ev.getProviderReference());
        e.setParsedStatus(ev.getStatus());
        e.setParsedAmountMinor(ev.getAmountCents() > 0 ? ev.getAmountCents() : null);
        if (ev.getPayportReference() == null || ev.getPayportReference().isBlank()) return "IGNORED";

        Optional<LegacyGateway.TransactionSnapshot> txo = gateway.transaction(ev.getPayportReference());
        if (txo.isEmpty()) {
            breaks.report("CALLBACK", "CALLBACK_UNKNOWN_REFERENCE", ev.getPayportReference(), null, null, ev.getAmountCents(),
                    provider + " sent a callback for a reference PayPort does not know");
            return "UNKNOWN_REFERENCE";
        }
        LegacyGateway.TransactionSnapshot tx = txo.get();
        if (tx.status() != TransactionStatus.PAYMENT_PENDING && tx.status() != TransactionStatus.PAYMENT_TIMEOUT) {
            if ("FAILED".equals(ev.getStatus()) && isPaidState(tx.status())) {
                breaks.report("CALLBACK", "LATE_FAILURE_ON_PAID", tx.reference(), tx.currency(), tx.amountCents(), null,
                        provider + " reported FAILED for a payment PayPort already treats as paid");
            }
            return "DUPLICATE_STATE";
        }

        String status = ev.getStatus();
        if ("PENDING".equals(status)) return "PENDING_STATUS";

        long amount = tx.amountCents();
        if ("SUCCESS".equals(status)) {
            Long claimed = ev.getAmountCents() > 0 ? ev.getAmountCents() : null;
            if (requiresQuery(provider)) {
                LegacyPayments.ProviderStatus q = payments.queryProvider(provider, tx.reference(), tx.providerReference(), tx.customerPhone());
                if ("FAILED".equals(q.status())) {
                    e.setVerification("QUERY_CONTRADICTED");
                    breaks.report("CALLBACK", "CALLBACK_CONTRADICTED", tx.reference(), tx.currency(), amount, claimed,
                            provider + " callback said SUCCESS but status query says FAILED (" + q.detail() + "). Possible spoofed callback.");
                    return "REJECTED_CONTRADICTED";
                }
                if (!"SUCCESS".equals(q.status())) {
                    e.setVerification("QUERY_INCONCLUSIVE");
                    return "DEFERRED";
                }
                e.setVerification("QUERY_CONFIRMED");
                if (q.amountMinor() != null) claimed = q.amountMinor();
            } else {
                e.setVerification("SIGNATURE_OK");
            }
            if (claimed != null && claimed != amount) {
                breaks.report("CALLBACK", "AMOUNT_MISMATCH", tx.reference(), tx.currency(), amount, claimed,
                        provider + " confirmed a different amount than PayPort requested");
                return "REJECTED_AMOUNT";
            }
            payments.applyOutcome(tx.reference(), "SUCCESS", ev.getProviderReference(), amount, tx.currency(),
                    e.getRawBody(), null);
            return "APPLIED";
        }
        if ("FAILED".equals(status)) {
            e.setVerification(requiresQuery(provider) ? "FAILURE_ACCEPTED" : "SIGNATURE_OK");
            payments.applyOutcome(tx.reference(), "FAILED", ev.getProviderReference(), 0, tx.currency(),
                    e.getRawBody(), ev.getFailureMessage());
            return "APPLIED";
        }
        return "IGNORED";
    }

    /** Retries callbacks whose processing failed (DB hiccup, provider query timeout...). */
    @Scheduled(fixedDelay = 20_000, initialDelay = 30_000)
    public void retryFailed() {
        for (CallbackInboxEntry e : inbox.findByStatusInAndNextAttemptAtBeforeOrderByIdAsc(
                List.of("RECEIVED", "RETRY"), LocalDateTime.now().minusSeconds(10), PageRequest.of(0, 100))) {
            process(e.getId());
        }
    }

    public List<CallbackInboxEntry> forReference(String reference) {
        return inbox.findByPayportReferenceOrderByIdAsc(reference);
    }

    public List<CallbackInboxEntry> byStatus(String status) {
        return inbox.findTop200ByStatusOrderByIdDesc(status);
    }

    /** Ops: send a parked callback round again after fixing the cause. */
    public String reprocess(Long id) {
        newTx.executeWithoutResult(s -> inbox.findById(id).ifPresent(e -> {
            e.setStatus("RETRY");
            e.setNextAttemptAt(LocalDateTime.now());
            inbox.save(e);
        }));
        return process(id);
    }

    private boolean requiresQuery(String provider) {
        return Arrays.stream(verifyByQuery.split(",")).map(String::trim).anyMatch(provider::equalsIgnoreCase);
    }

    private static boolean isPaidState(TransactionStatus s) {
        return s == TransactionStatus.PAYMENT_CONFIRMED || s == TransactionStatus.FULFILMENT_PENDING
                || s == TransactionStatus.COMPLETED || s == TransactionStatus.FULFILMENT_FAILED
                || s == TransactionStatus.FULFILMENT_RETRY || s == TransactionStatus.MANUAL_FULFILMENT;
    }

    private String safeHeaders(Map<String, String> headers) {
        if (headers == null) return null;
        Map<String, String> copy = new TreeMap<>();
        headers.forEach((k, v) -> copy.put(k.toLowerCase(), SENSITIVE_HEADERS.contains(k.toLowerCase()) ? "[redacted]" : v));
        try { return om.writeValueAsString(copy); } catch (Exception ex) { return null; }
    }
}
