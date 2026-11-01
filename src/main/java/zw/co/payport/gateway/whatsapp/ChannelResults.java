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
import java.util.Set;
import java.util.EnumSet;

/**
 * WhatsApp and USSD give the customer the final answer, like the app (owner, 2026-11-01: these are real-time payments;
 * the customer is told it succeeded or failed, never that it is "being delivered"). After the customer approves (or not)
 * on their phone, the watcher waits for the payment's answer and then for the purchase itself (the airtime, data,
 * token or bill), and sends one message: successful (the delivery message, which carries any token or voucher),
 * failed with the provider's own reason, or not approved in time. A payment the provider never answers is closed for
 * the customer exactly as for the app (never fulfilled afterwards). Only if the network has still not answered about
 * the purchase after {@link #DELIVERY_WAIT_SECONDS} is the customer told that, and the final result follows by SMS.
 *
 * USSD cannot hold the session while the customer approves (the provider's PIN prompt replaces it), so USSD customers
 * get the same answer by SMS.
 */
@Service
public class ChannelResults {

    private static final Logger log = LoggerFactory.getLogger(ChannelResults.class);
    public static final String KIND = "PAYMENT_RESULT";
    /** How long the purchase may take after the payment is approved before the customer is answered regardless. */
    static final int DELIVERY_WAIT_SECONDS = 300;
    /** States after which nothing more happens without staff (the customer's final answer). */
    static final Set<TransactionStatus> FINAL = EnumSet.of(TransactionStatus.COMPLETED, TransactionStatus.PAYMENT_FAILED,
            TransactionStatus.PAYMENT_TIMEOUT, TransactionStatus.FULFILMENT_FAILED, TransactionStatus.MANUAL_FULFILMENT,
            TransactionStatus.REFUND_PENDING, TransactionStatus.REFUNDED, TransactionStatus.CANCELLED);
    static final Set<TransactionStatus> DELIVERING = EnumSet.of(TransactionStatus.PAYMENT_CONFIRMED, TransactionStatus.FULFILMENT_PENDING,
            TransactionStatus.FULFILMENT_RETRY);

    private final TransactionOutcomeService outcomes;
    private final PaymentTimeoutWatchdog windows;
    private final WhatsAppSender sender;
    private final JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<zw.co.payport.gateway.service.SmsNotificationService> sms;

    public ChannelResults(TransactionOutcomeService outcomes, PaymentTimeoutWatchdog windows, WhatsAppSender sender, JdbcTemplate jdbc) {
        this.outcomes = outcomes;
        this.windows = windows;
        this.sender = sender;
        this.jdbc = jdbc;
    }

    /** WhatsApp: waits in the background (the chat has already been told to approve the prompt). */
    @Async
    public void watch(String waId, String reference) {
        try {
            watchNow(waId, reference, paymentWait(reference));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("[WHATSAPP] result for {} not sent: {}", reference, e.getMessage());
        }
    }

    /** USSD: the same answer by SMS. A success is confirmed by the delivery SMS and a failed purchase by its own SMS. */
    @Async
    public void watchBySms(String phone, String reference) {
        try {
            Optional<TransactionOutcomeService.Result> r = settle(reference, paymentWait(reference));
            if (r.isEmpty() || sms == null) return;
            TransactionStatus st = r.get().status();
            if (st == TransactionStatus.COMPLETED || st == TransactionStatus.FULFILMENT_FAILED || st == TransactionStatus.MANUAL_FULFILMENT) return;
            String text = message(r.get(), details(reference));
            var s = sms.getIfAvailable();
            if (text != null && s != null) s.sendText(phone, "Payport: " + text.replace("\n", " "), reference, KIND);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("[USSD] result SMS for {} not sent: {}", reference, e.getMessage());
        }
    }

    private int paymentWait(String reference) {
        List<String> p = jdbc.queryForList("SELECT payment_provider_code FROM transactions WHERE reference = ?", String.class, reference);
        return Math.min(windows.windowSeconds(p.isEmpty() ? null : p.get(0), "WHATSAPP") + 20, 180);
    }

    /**
     * Waits for the payment's answer (closing it for the customer when the window ends with none), then for the
     * purchase's answer. Returns the latest result, final unless the network has still not answered about the purchase.
     */
    Optional<TransactionOutcomeService.Result> settle(String reference, int paymentSeconds) throws InterruptedException {
        Optional<TransactionOutcomeService.Result> r = outcomes.waitForFinal(reference, paymentSeconds);
        if (r.isPresent() && r.get().status() == TransactionStatus.PAYMENT_PENDING) {
            windows.closeForCustomer(reference);
            r = outcomes.current(reference);
        }
        long deadline = System.currentTimeMillis() + DELIVERY_WAIT_SECONDS * 1000L;
        while (r.isPresent() && !FINAL.contains(r.get().status()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(1000);
            r = outcomes.current(reference);
        }
        return r;
    }

    /** Waits for the final answer and sends it to the chat; returns what was sent (null when nothing is sent). */
    public String watchNow(String waId, String reference, int maxSeconds) throws InterruptedException {
        if (answered(reference)) return null;   // one result per payment, even if two watchers start
        Optional<TransactionOutcomeService.Result> r = settle(reference, maxSeconds);
        if (r.isEmpty() || answered(reference)) return null;
        String text = message(r.get(), details(reference));
        if (text == null) return null;
        String err = sender.send(waId, text);
        jdbc.update("INSERT INTO whatsapp_outbox (wa_id, body, kind, sent, send_error, created_at, reference) VALUES (?,?,?,?,?,?,?)",
                waId, text, KIND, err == null, err == null ? null : err.substring(0, Math.min(err.length(), 300)), Timestamp.valueOf(LocalDateTime.now()), reference);
        return text;
    }

    private Map<String, Object> details(String reference) {
        List<Map<String, Object>> t = jdbc.queryForList("SELECT COALESCE(t.product_name, p.name) AS product_name, t.amount_cents, t.charge_cents, t.currency, "
                + "t.recipient_identifier, t.payment_provider_code FROM transactions t LEFT JOIN product_types p ON p.id = t.product_type_id WHERE t.reference = ?", reference);
        return t.isEmpty() ? Map.of() : t.get(0);
    }

    private static final Map<String, String> PAYERS = Map.of("ECOCASH", "EcoCash", "ONEMONEY", "OneMoney", "OMARI", "O'mari",
            "INNBUCKS", "InnBucks", "ZIMSWITCH", "card", "WALLET", "Payport wallet", "PAYPORT_WALLET", "Payport wallet");

    /** The customer's answer: successful, failed (with the reason) or not approved in time; never "being delivered". */
    static String message(TransactionOutcomeService.Result r, Map<String, Object> t) {
        String what = t.get("product_name") == null ? "Your payment" : String.valueOf(t.get("product_name"));
        long amount = t.get("amount_cents") instanceof Number n ? n.longValue() : 0;
        long charge = t.get("charge_cents") instanceof Number n ? n.longValue() : 0;
        String money = t.get("currency") == null ? "" : " " + t.get("currency") + " " + BigDecimal.valueOf(amount + charge, 2).toPlainString();
        String to = t.get("recipient_identifier") == null ? "" : " for " + t.get("recipient_identifier");
        String payer = t.get("payment_provider_code") == null ? null : PAYERS.get(String.valueOf(t.get("payment_provider_code")).toUpperCase());
        String paid = payer == null ? "Your payment" : "Your " + payer + " payment";
        String ref = "\nRef " + r.reference();
        String reason = r.message() == null || r.message().isBlank() ? null : r.message().trim();
        TransactionStatus st = r.status();
        if (st == TransactionStatus.COMPLETED) return null;   // the delivery message confirms it (with any token or voucher)
        if (st == TransactionStatus.FULFILMENT_FAILED || st == TransactionStatus.MANUAL_FULFILMENT) {
            return "Failed: " + what + money + to + ".\n" + (reason == null ? "The provider could not complete it." : reason)
                    + "\n" + paid + " will be refunded." + ref;
        }
        if (st != null && DELIVERING.contains(st)) {
            return "Not yet confirmed: " + what + money + to + ".\n" + paid + " was approved but the provider has not answered yet. Do not pay again; "
                    + "you will get the result by SMS." + ref;
        }
        return switch (r.outcome()) {
            case SUCCEEDED -> null;
            case FAILED -> "Payment failed: " + what + money + to + ".\n" + (reason == null ? "The payment was declined." : reason) + "\nNo money was taken." + ref;
            case TIMED_OUT, NO_RESPONSE -> "Payment failed: " + what + money + to + ".\n"
                    + (r.outcome() == TransactionOutcomeService.Outcome.TIMED_OUT ? "The payment was not approved in time." : "The provider did not answer in time.")
                    + "\nIf money left your account it will be refunded automatically." + ref;
            case REFUNDED -> "Refunded: " + what + money + to + "." + ref;
            case CANCELLED -> "Cancelled: " + what + money + to + "." + ref;
            case REQUIRES_PAYMENT -> "Payment not started: " + what + money + to + "." + ref;
            case PROCESSING -> "Not yet confirmed: " + what + money + to + ".\nThe provider has not answered yet. Do not pay again; you will get the result by SMS." + ref;
        };
    }

    /** True when the chat already has this payment's result (so the SMS-style update is not repeated there). */
    public boolean answered(String reference) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM whatsapp_outbox WHERE reference = ? AND kind = ?", Integer.class, reference, KIND);
        return n != null && n > 0;
    }
}
