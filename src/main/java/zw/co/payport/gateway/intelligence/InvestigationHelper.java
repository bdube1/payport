package zw.co.payport.gateway.intelligence;

import org.springframework.stereotype.Service;
import zw.co.payport.gateway.care.CareService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The investigation helper: reads what Payport recorded for a reference (the same trace the complaint desk shows) and
 * says it in plain words, with what to check next and a draft reply to the customer. Read-only: it never requests or
 * approves a reversal and never contacts the customer; staff read it, change the draft and decide.
 */
@Service
public class InvestigationHelper {

    private final CareService care;
    private final Intelligence intelligence;

    public InvestigationHelper(CareService care, Intelligence intelligence) {
        this.care = care;
        this.intelligence = intelligence;
    }

    static String money(Object minor, Object ccy) {
        long m = minor instanceof Number n ? n.longValue() : 0;
        return ccy + " " + BigDecimal.valueOf(m, 2).toPlainString();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> explain(String ref) {
        intelligence.require(Intelligence.HELPER);
        Map<String, Object> t = care.trace(ref);
        List<String> findings = new ArrayList<>(), next = new ArrayList<>();
        String reply;
        List<Map<String, Object>> txs = (List<Map<String, Object>>) t.getOrDefault("transactions", List.of());
        List<Map<String, Object>> lines = (List<Map<String, Object>>) t.getOrDefault("walletLines", List.of());
        List<Map<String, Object>> options = (List<Map<String, Object>>) t.getOrDefault("options", List.of());
        List<Map<String, Object>> reversals = (List<Map<String, Object>>) t.getOrDefault("reversals", List.of());
        if (txs.isEmpty() && lines.isEmpty()) {
            findings.add("Payport has no payment or wallet movement with the reference " + ref + ".");
            next.add("Ask the customer for the reference on their SMS or provider message, or the date, amount and number used.");
            reply = "Thank you for contacting Payport. We could not find a transaction with the reference " + ref
                    + ". Please send us the reference from your confirmation message, or the date, amount and phone number used, and we will look into it.";
            return result(ref, findings, next, reply, options);
        }
        String outcome = null, what = null;
        for (Map<String, Object> x : txs) {
            what = x.get("product") + " " + money(x.get("amount_cents"), x.get("currency"));
            outcome = String.valueOf(x.getOrDefault("outcome", x.get("status")));
            String via = x.get("payment_method_name") == null ? "" : " with " + x.get("payment_method_name");
            String to = x.get("merchant") != null ? " to " + x.get("merchant") : x.get("recipient_identifier") != null ? " for " + x.get("recipient_identifier") : "";
            findings.add(what + to + via + ": " + plain(outcome) + ".");
            if (x.get("outcomeMessage") != null) findings.add("Provider message: " + x.get("outcomeMessage"));
            if (Boolean.TRUE.equals(x.get("refunded"))) findings.add("It has already been refunded" + (x.get("refund_reference") == null ? "." : " (" + x.get("refund_reference") + ")."));
            List<?> dup = (List<?>) x.getOrDefault("possibleDuplicates", List.of());
            if (!dup.isEmpty()) {
                findings.add(dup.size() + " other payment" + (dup.size() > 1 ? "s" : "") + " of the same amount from the same number within 15 minutes: possibly paid twice.");
                next.add("Compare the duplicate references with the customer's provider statement before refunding one.");
            }
            switch (outcome) {
                case "NO_RESPONSE" -> next.add("The provider never answered: check the provider's portal or statement for this reference before anything else.");
                case "PROCESSING" -> next.add("Still being processed: wait for the provider's answer (the payment window), then check again.");
                case "FAILED", "TIMED_OUT" -> next.add("Not paid: ask the customer to check their provider statement; if money left their account, send the provider's reference.");
                default -> { }
            }
        }
        for (Map<String, Object> l : lines) {
            findings.add("Wallet " + l.get("wallet_ref") + ": " + String.valueOf(l.get("kind")).replace('_', ' ').toLowerCase(Locale.ROOT) + " "
                    + ("DR".equals(l.get("direction")) ? "out " : "in ") + money(l.get("amount_minor"), l.get("currency"))
                    + (l.get("counterparty") == null ? "" : " (" + l.get("counterparty") + ")") + ".");
        }
        for (Map<String, Object> r : reversals) findings.add("Reversal " + r.get("reversal_ref") + " is " + String.valueOf(r.get("status")).replace('_', ' ').toLowerCase(Locale.ROOT) + ".");
        if (!options.isEmpty() && reversals.isEmpty()) next.add("Eligible for a reversal from the reversal desk: " + options.get(0).get("label") + ".");
        if (next.isEmpty()) next.add("Nothing unusual: confirm the outcome to the customer.");

        String o = outcome == null ? "" : outcome;
        reply = switch (o) {
            case "SUCCEEDED" -> "Thank you for contacting Payport. Your payment for " + what + " (reference " + ref + ") was successful and completed. "
                    + "If you have not received it, reply to this message and we will follow it up with the provider.";
            case "PROCESSING", "NO_RESPONSE" -> "Thank you for contacting Payport. Your payment for " + what + " (reference " + ref + ") is still being confirmed with the provider. "
                    + "We will let you know the result; if money was taken and the payment does not complete, it will be refunded.";
            case "FAILED", "TIMED_OUT" -> "Thank you for contacting Payport. Your payment for " + what + " (reference " + ref + ") did not go through, so it was not charged by Payport. "
                    + "If your statement shows a deduction, please send us the provider's reference and we will investigate.";
            case "REFUNDED" -> "Thank you for contacting Payport. Your payment for " + what + " (reference " + ref + ") has been refunded.";
            default -> "Thank you for contacting Payport. We are looking into reference " + ref + " and will come back to you shortly.";
        };
        return result(ref, findings, next, reply, options);
    }

    static String plain(String outcome) {
        return switch (outcome) {
            case "SUCCEEDED" -> "paid and delivered";
            case "PROCESSING" -> "still being processed";
            case "REQUIRES_PAYMENT" -> "not paid yet";
            case "FAILED" -> "failed";
            case "TIMED_OUT" -> "not completed by the customer in time";
            case "NO_RESPONSE" -> "the provider has not answered (outcome unknown)";
            case "REFUNDED" -> "refunded";
            case "CANCELLED" -> "cancelled";
            default -> outcome.replace('_', ' ').toLowerCase(Locale.ROOT);
        };
    }

    static Map<String, Object> result(String ref, List<String> findings, List<String> next, String reply, List<Map<String, Object>> options) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reference", ref);
        out.put("findings", findings);
        out.put("next", next);
        out.put("draftReply", reply);
        out.put("reversible", !options.isEmpty());
        return out;
    }
}
