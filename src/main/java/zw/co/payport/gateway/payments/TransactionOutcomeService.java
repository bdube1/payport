package zw.co.payport.gateway.payments;

import org.springframework.stereotype.Service;
import zw.co.payport.gateway.entity.Transaction;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.model.response.TransactionResponse;
import zw.co.payport.gateway.repository.TransactionRepository;
import zw.co.payport.gateway.service.FulfilmentQueueService;

import java.util.Optional;

/**
 * The single answer a channel (web, app, USSD, merchant API) gives the customer, derived
 * from the payment intent and its delivery:
 *
 *   REQUIRES_PAYMENT  created, no payment attempt yet (or a new attempt is possible after FAILED)
 *   PROCESSING        waiting for the provider or the delivery
 *   SUCCEEDED         paid and delivered
 *   FAILED            the provider declined the payment or the delivery (message = the provider's own words)
 *   TIMED_OUT         the payment window closed; the provider answered but the customer did not complete
 *   NO_RESPONSE       the provider never answered (payment or delivery)
 *   REFUNDED, CANCELLED
 *
 * Final outcomes are everything except REQUIRES_PAYMENT and PROCESSING.
 */
@Service
public class TransactionOutcomeService {

    public enum Outcome { REQUIRES_PAYMENT, PROCESSING, SUCCEEDED, FAILED, TIMED_OUT, NO_RESPONSE, REFUNDED, CANCELLED;
        public boolean isFinal() { return this != REQUIRES_PAYMENT && this != PROCESSING; }
    }

    public record Result(String reference, Outcome outcome, String message, TransactionStatus status, int attempts) {}

    static final String DELIVERING = "Payment received. Your purchase is being delivered; you will get an SMS when it is complete.";

    private final TransactionRepository transactions;
    private final FulfilmentQueueService jobs;

    public TransactionOutcomeService(TransactionRepository transactions, FulfilmentQueueService jobs) {
        this.transactions = transactions;
        this.jobs = jobs;
    }

    public Optional<Result> current(String reference) {
        return transactions.findByReference(reference).map(this::of);
    }

    public Result of(Transaction tx) {
        TransactionStatus s = tx.getStatus();
        Outcome o;
        String msg = tx.getFailureReason();
        switch (s) {
            case INITIATED -> { o = Outcome.REQUIRES_PAYMENT; msg = null; }
            case PAYMENT_PENDING -> { o = Outcome.PROCESSING; msg = null; }
            case PAYMENT_CONFIRMED, FULFILMENT_RETRY -> { o = Outcome.PROCESSING; msg = DELIVERING; }
            case PAYMENT_TIMEOUT -> {
                o = "NO_RESPONSE".equals(tx.getPaymentOutcome()) ? Outcome.NO_RESPONSE : Outcome.TIMED_OUT;
                if (msg == null) msg = o == Outcome.NO_RESPONSE ? "No response from the provider. The transaction timed out." : "The transaction timed out.";
            }
            case PAYMENT_FAILED, FULFILMENT_FAILED, MANUAL_FULFILMENT -> o = Outcome.FAILED;
            case FULFILMENT_PENDING -> {
                FulfilmentQueueService.JobStatus job = jobs.find(tx.getReference()).map(FulfilmentQueueService.Job::status).orElse(null);
                if (job == FulfilmentQueueService.JobStatus.UNKNOWN || job == FulfilmentQueueService.JobStatus.MANUAL) {
                    o = Outcome.NO_RESPONSE;
                    msg = "No response from the service provider. We are confirming whether it was delivered.";
                } else {
                    o = Outcome.PROCESSING;
                    msg = DELIVERING;
                }
            }
            case COMPLETED -> { o = Outcome.SUCCEEDED; msg = null; }
            case REFUND_PENDING, REFUNDED -> o = Outcome.REFUNDED;
            case CANCELLED -> o = Outcome.CANCELLED;
            default -> o = Outcome.PROCESSING;
        }
        int attempts = tx.getPaymentAttemptCount() == null ? 0 : tx.getPaymentAttemptCount();
        return new Result(tx.getReference(), o, msg, s, attempts);
    }

    /** Adds the outcome to an API response. */
    public TransactionResponse enrich(TransactionResponse r) {
        if (r == null || r.getReference() == null) return r;
        current(r.getReference()).ifPresent(res -> {
            r.setOutcome(res.outcome().name());
            r.setOutcomeMessage(res.message());
            r.setPaymentAttempts(res.attempts());
        });
        return r;
    }

    /**
     * Waits up to maxWaitSeconds for a final outcome (for channels such as USSD that must answer
     * the customer in one session). Returns the latest outcome, final or not.
     */
    public Optional<Result> waitForFinal(String reference, int maxWaitSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(0, Math.min(maxWaitSeconds, 180)) * 1000L;
        Optional<Result> r = current(reference);
        while (r.isPresent() && !r.get().outcome().isFinal() && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            r = current(reference);
        }
        return r;
    }
}
