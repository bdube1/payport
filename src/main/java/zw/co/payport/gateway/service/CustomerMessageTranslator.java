package zw.co.payport.gateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Customer Message Translator
 *
 * Every payment provider (EcoCash, OneMoney, Econet, NetOne, ZimSwitch, the
 * eSolutions billers...) returns its own raw status text on failure — things
 * like "(8030) IN failure", vendor-specific numeric codes, or terse internal
 * strings. None of that means anything to a customer, and until now it was
 * being stored as the transaction's failureReason and shown to them
 * unmodified, in the app and in SMS.
 *
 * This class is the one place that decides what a customer actually sees.
 * The raw provider text is never discarded — it still goes into
 * failureDetails / the audit log untouched, for support and ops to
 * investigate. This only changes the customer-facing copy.
 *
 * Deliberately conservative: we only map patterns we're actually confident
 * about. Anything unrecognised gets a safe, honest, generic message rather
 * than a guess — showing the wrong specific reason is worse than showing
 * a general one.
 */
@Slf4j
@Component
public class CustomerMessageTranslator {

    private static final String GENERIC_PAYMENT_FAILED =
            "We couldn't process your payment. Please try again, and contact support if this keeps happening.";

    private static final String GENERIC_FULFILMENT_FAILED =
            "Your payment was received, but we're having trouble delivering this right now. " +
            "Our team has been notified and is looking into it — you don't need to do anything.";

    /** For a rejected/failed payment attempt (money never left the customer, or was reversed). */
    public String forPaymentFailure(String rawMessage) {
        String clean = matchKnownPattern(rawMessage);
        if (clean != null) return clean;
        log.info("[MESSAGE_TRANSLATOR] Unrecognised payment failure text, using generic message. Raw: {}", rawMessage);
        return GENERIC_PAYMENT_FAILED;
    }

    /** For a failed delivery/fulfilment AFTER payment has already succeeded — the customer has paid. */
    public String forFulfilmentFailure(String rawMessage) {
        String clean = matchKnownPattern(rawMessage);
        if (clean != null) return clean;
        log.info("[MESSAGE_TRANSLATOR] Unrecognised fulfilment failure text, using generic message. Raw: {}", rawMessage);
        return GENERIC_FULFILMENT_FAILED;
    }

    /**
     * Pattern-matches common, well-understood failure reasons that show up
     * across providers in slightly different wording. Case-insensitive
     * substring matching, since we don't have a canonical error-code list
     * for every provider — only what's actually shown up in practice.
     * Returns null if nothing recognised matches, so the caller can fall
     * back to a safe generic message instead of guessing.
     */
    private String matchKnownPattern(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String r = raw.toLowerCase();

        if (r.contains("insufficient") || r.contains("low balance") || r.contains("not enough")) {
            return "Insufficient balance. Please top up your wallet and try again.";
        }
        if (r.contains("incorrect pin") || r.contains("invalid pin") || r.contains("wrong pin")) {
            return "The PIN entered was incorrect. Please try again.";
        }
        if (r.contains("otp invalid") || r.contains("invalid otp") || r.contains("incorrect otp") || r.contains("otp incorrect") || r.contains("wrong otp") || r.contains("invalid code") || r.contains("incorrect code")) {
            return "The code entered was incorrect. Please try again.";
        }
        if (r.contains("cancel")) {
            return "The payment was cancelled.";
        }
        if (r.contains("timeout") || r.contains("timed out") || r.contains("no response")) {
            return "We didn't receive a response in time. Please check your phone for a payment prompt, or try again.";
        }
        if (r.contains("limit exceeded") || r.contains("exceeds limit") || r.contains("exceeds_limit") || r.contains("daily limit")) {
            return "This payment exceeds your wallet's transaction limit for today.";
        }
        if (r.contains("duplicate")) {
            return "This transaction has already been processed.";
        }
        if (r.contains("invalid number") || r.contains("invalid recipient") || r.contains("invalid msisdn")) {
            return "The recipient number could not be validated. Please check it and try again.";
        }
        if (r.contains("suspended") || r.contains("blocked") || r.contains("restricted")) {
            return "This wallet is currently unable to receive payments. Please contact your provider.";
        }
        if (r.contains("service unavailable") || r.contains("system busy") || r.contains("try again later")) {
            return "The payment service is temporarily unavailable. Please try again shortly.";
        }
        return null;
    }
}
