package zw.co.payport.gateway.whatsapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.payments.TransactionOutcomeService;
import zw.co.payport.gateway.scheduler.PaymentTimeoutWatchdog;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * WhatsApp waits for the payment's answer the way the app does (owner, 2026-10-31): after the customer approves (or
 * not) on their phone, the result is sent into the chat, worded from the same customer-facing outcome the app and the
 * website show (TransactionOutcomeService): paid, being delivered, declined with the provider's own reason, not
 * approved in time, or no answer from the provider. When the payment window ends with no answer the payment is
 * closed for the customer exactly as for the app and USSD (never fulfilled afterwards). A successful purchase is
 * confirmed by the delivery message (it carries tokens and vouchers), so success is not sent twice.
 *
 * USSD cannot wait in the same way: the provider's PIN prompt replaces the USSD session on the phone, so USSD
 * customers get the result by SMS (same wording).
 */
@Service
public class ChannelResults {

    private static final Logger log = LoggerFactory.getLogger(ChannelResults.class);
    public static final String KIND = "PAYMENT_RESULT";

    private final TransactionOutcomeService outcomes;
    private final PaymentTimeoutWatchdog windows;
    private final WhatsAppSender sender;
    private final JdbcTemplate jdbc;

    public ChannelResults(TransactionOutcomeService outcomes, PaymentTimeoutWatchdog windows, WhatsAppSender sender, JdbcTemplate jdbc) {
        this.outcomes = outcomes;
        this.windows = windows;
        this.sender = sender;
        this.jdbc = jdbc;
    }

    /** Waits in the background (the chat has already been told to approve the prompt). */
    @Async
    public void watch(String waId, String reference) {
        try {
            List<String> p = jdbc.queryForList("SELECT payment_provider_code FROM transactions WHERE reference = ?", String.class, reference);
            int window = windows.windowSeconds(p.isEmpty() ? null : p.get(0), "WHATSAPP");
            watchNow(waId, reference, Math.min(window + 20, 180));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("[WHATSAPP] result for {} not sent: {}", reference, e.getMessage());
        }
    }

    /** Waits up to maxSeconds for the answer and sends it; returns what was sent (null when nothing is sent). */
    public String watchNow(String waId, String reference, int maxSeconds) throws InterruptedException {
        if (answered(reference)) return null;   // one result per payment, even if two watchers start
        Optional<TransactionOutcomeService.Result> r = outcomes.waitForFinal(reference, maxSeconds);
        if (r.isPresent() && r.get().status() == TransactionStatus.PAYMENT_PENDING) {
            windows.closeForCustomer(reference);
            r = outcomes.current(reference);
        }
        if (r.isEmpty() || answered(reference)) return null;
        List<Map<String, Object>> t = jdbc.queryForList("SELECT product_name, amount_cents, charge_cents, currency, recipient_identifier FROM transactions WHERE reference = ?", reference);
        String text = message(r.get(), t.isEmpty() ? Map.of() : t.get(0));
        if (text == null) return null;
        String err = sender.send(waId, text);
        jdbc.update("INSERT INTO whatsapp_outbox (wa_id, body, kind, sent, send_error, created_at, reference) VALUES (?,?,?,?,?,?,?)",
                waId, text, KIND, err == null, err == null ? null : err.substring(0, Math.min(err.length(), 300)), Timestamp.valueOf(LocalDateTime.now()), reference);
        return text;
    }

    /** The chat message for an outcome: the same facts and reasons as the app, in a few words. */
    static String message(TransactionOutcomeService.Result r, Map<String, Object> t) {
        String what = t.get("product_name") == null ? "your payment" : String.valueOf(t.get("product_name"));
        long amount = t.get("amount_cents") instanceof Number n ? n.longValue() : 0;
        long charge = t.get("charge_cents") instanceof Number n ? n.longValue() : 0;
        String money = t.get("currency") == null ? "" : " " + t.get("currency") + " " + BigDecimal.valueOf(amount + charge, 2).toPlainString();
        String ref = "\nRef " + r.reference();
        String reason = r.message() == null || r.message().isBlank() ? null : r.message().trim();
        return switch (r.outcome()) {
            case SUCCEEDED -> null;   // the delivery message confirms it (with any token or voucher)
            case PROCESSING -> "Payment received for " + what + money + ".\n" + (reason == null ? "It is being delivered; I will message you when it is done." : reason) + ref;
            case FAILED -> "Payment not completed: " + what + money + ".\n" + (reason == null ? "The payment was declined." : reason) + "\nNo money was taken." + ref;
            case TIMED_OUT -> "Payment not completed: " + what + money + ".\n" + (reason == null ? "The payment was not approved in time." : reason)
                    + "\nIf money was taken it will be refunded." + ref;
            case NO_RESPONSE -> "We have not had an answer about " + what + money + " yet.\n" + (reason == null ? "We are checking with the provider." : reason)
                    + "\nYou will get a message as soon as we know. Do not pay again." + ref;
            case REFUNDED -> "Payment refunded: " + what + money + "." + ref;
            case CANCELLED -> "Payment cancelled: " + what + money + "." + ref;
            case REQUIRES_PAYMENT -> "Payment not started: " + what + money + "." + ref;
        };
    }

    /** True when the chat already has this payment's result (so the SMS-style update is not repeated there). */
    public boolean answered(String reference) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM whatsapp_outbox WHERE reference = ? AND kind = ?", Integer.class, reference, KIND);
        return n != null && n > 0;
    }
}
