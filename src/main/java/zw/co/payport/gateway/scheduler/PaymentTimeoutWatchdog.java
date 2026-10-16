package zw.co.payport.gateway.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.entity.Transaction;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.payments.PaymentAttemptStore;
import zw.co.payport.gateway.repository.TransactionRepository;
import zw.co.payport.gateway.service.PaymentStatusVerifier;
import zw.co.payport.gateway.service.adaptor.PaymentProviderAdaptor;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService.ExpiryOutcome;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Closes the payment window (Stripe-style: an intent does not stay "processing" for days).
 *
 * When a payment has been pending longer than its provider's window
 * (payport.payments.timeout-seconds, per provider payport.payments.provider-timeout-seconds.CODE),
 * the provider is asked one last time:
 *  - a definite answer (paid / declined) is applied;
 *  - otherwise the payment becomes PAYMENT_TIMEOUT and the customer is told on the spot:
 *      TIMED_OUT   the provider answered but the customer did not complete in time;
 *      NO_RESPONSE the provider never answered (submit outcome unknown and the query failed).
 * A confirmation that lands at the same moment wins (row lock, attempt check). Background
 * checks continue for late-payment detection (PaymentReconciliationScheduler); a late success
 * goes to review, it is never silently applied or ignored.
 */
@Slf4j
@Component
public class PaymentTimeoutWatchdog {

    private final TransactionRepository transactionRepository;
    private final TransactionOrchestratorService orchestrator;
    private final PaymentStatusVerifier verifier;
    private final PaymentAttemptStore attempts;
    private final Environment env;
    private final int defaultTimeoutSeconds;
    private final boolean enabled;

    public PaymentTimeoutWatchdog(TransactionRepository transactionRepository, TransactionOrchestratorService orchestrator,
                                  PaymentStatusVerifier verifier, PaymentAttemptStore attempts, Environment env,
                                  @Value("${payport.payments.timeout-seconds:${payport.gateway.payment-timeout-seconds:180}}") int defaultTimeoutSeconds,
                                  @Value("${payport.payments.watchdog.enabled:true}") boolean enabled) {
        this.transactionRepository = transactionRepository;
        this.orchestrator = orchestrator;
        this.verifier = verifier;
        this.attempts = attempts;
        this.env = env;
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
        this.enabled = enabled;
    }

    /** The payment window for a provider, in seconds (any channel). */
    public int windowSeconds(String provider) {
        return windowSeconds(provider, null);
    }

    /**
     * The payment window for a provider on a channel, most specific setting first:
     * window-seconds.CHANNEL.PROVIDER, window-seconds.CHANNEL, provider-timeout-seconds.PROVIDER, default.
     * Changing any value needs the owner's approval (PaymentWindowsTest pins them).
     */
    public int windowSeconds(String provider, String channel) {
        String p = provider == null ? null : provider.toUpperCase();
        String c = channel == null ? null : channel.toUpperCase();
        Integer v = null;
        if (c != null && p != null) v = env.getProperty("payport.payments.window-seconds." + c + "." + p, Integer.class);
        if (v == null && c != null) v = env.getProperty("payport.payments.window-seconds." + c, Integer.class);
        if (v == null && p != null) v = env.getProperty("payport.payments.provider-timeout-seconds." + p, Integer.class);
        return v != null ? v : defaultTimeoutSeconds;
    }

    @jakarta.annotation.PostConstruct
    void logWindows() {
        log.info("[EXPIRY] Payment windows (owner-approved values): default={}s, ECOCASH={}s, ONEMONEY={}s, OMARI={}s, ZIMSWITCH={}s, USSD(ECOCASH)={}s",
                defaultTimeoutSeconds, windowSeconds("ECOCASH"), windowSeconds("ONEMONEY"), windowSeconds("OMARI"), windowSeconds("ZIMSWITCH"),
                windowSeconds("ECOCASH", "USD"));
    }

    /**
     * The customer-facing wait (e.g. a USSD session) has ended while the payment is still open.
     * The customer is about to be told TIMED_OUT (or NO_RESPONSE), so the payment is closed now in
     * exactly the same way as at the end of the window: one last provider query, a definite answer
     * wins, otherwise PAYMENT_TIMEOUT -- never PAYMENT_FAILED. From then on a late success is
     * refunded, never fulfilled. Returns true if the payment was closed or settled by the query.
     */
    public boolean closeForCustomer(String reference) {
        Transaction tx = transactionRepository.findByReference(reference).orElse(null);
        if (tx == null || tx.getStatus() != TransactionStatus.PAYMENT_PENDING) return false;
        return close(tx, LocalDateTime.now(), "customer-facing wait ended");
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 15000)
    public void processTimeouts() {
        if (!enabled) return;
        try {
            runOnce(LocalDateTime.now());
        } catch (Exception e) {
            log.error("[EXPIRY] run failed: {}", e.getMessage(), e);
        }
    }

    /** Returns how many payments were closed (expired or settled by the final check). */
    public int runOnce(LocalDateTime now) {
        int shortest = Math.min(defaultTimeoutSeconds, 5);
        List<Transaction> candidates = transactionRepository
                .findByStatusAndPaymentSubmittedAtBefore(TransactionStatus.PAYMENT_PENDING, now.minusSeconds(shortest));
        if (candidates == null || candidates.isEmpty()) return 0;

        int closed = 0;
        for (Transaction tx : candidates) {
            try {
                if (closeIfExpired(tx, now)) closed++;
            } catch (Exception e) {
                log.error("[EXPIRY] Failed to process {}: {}", tx.getReference(), e.getMessage(), e);
            }
        }
        return closed;
    }

    private boolean closeIfExpired(Transaction tx, LocalDateTime now) {
        String provider = tx.getPaymentProviderCode();
        if (tx.getPaymentSubmittedAt() == null) return false;
        long age = Duration.between(tx.getPaymentSubmittedAt(), now).getSeconds();
        if (age < windowSeconds(provider, tx.getChannel() == null ? null : tx.getChannel().name())) return false;
        return close(tx, now, "payment window closed after " + age + "s");
    }

    private boolean close(Transaction tx, LocalDateTime now, String why) {
        String provider = tx.getPaymentProviderCode();

        // Last chance: ask the provider before telling the customer it timed out.
        PaymentStatusVerifier.Verified v = verifier.verify(provider, tx);
        PaymentProviderAdaptor.CallbackEvent event = verifier.toEvent(tx, v, "payment window close");
        if (event != null) {
            TransactionOrchestratorService.CallbackOutcome o = orchestrator.handlePaymentCallback(event);
            log.info("[EXPIRY] {} settled by the final status query at window close: {} ({})", tx.getReference(), v.status(), o);
            return o == TransactionOrchestratorService.CallbackOutcome.APPLIED;
        }

        boolean submitUnanswered = attempts.latest(tx.getReference())
                .map(a -> a.status() == PaymentAttemptStore.Status.UNKNOWN || a.status() == PaymentAttemptStore.Status.SENT)
                .orElse(true);
        boolean queryUnanswered = "UNKNOWN".equals(v.status());
        ExpiryOutcome outcome = submitUnanswered && queryUnanswered ? ExpiryOutcome.NO_RESPONSE : ExpiryOutcome.TIMED_OUT;
        boolean done = orchestrator.expirePayment(tx.getReference(), tx.getPaymentAttemptReference(), outcome, v.providerMessage());
        if (done) {
            log.info("[EXPIRY] {} closed as {} ({}; provider says {}: {})", tx.getReference(), outcome, why, v.status(), v.detail());
        }
        return done;
    }
}
