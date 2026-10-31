package zw.co.payport.gateway.recon;

import zw.co.payport.gateway.payments.PaymentAttemptStore;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure matching of a provider statement against Payport's payments (no database, no side
 * effects; unit-tested). Every difference is categorised; nothing is adjusted.
 *
 *  MATCHED              same payment, amount and currency, inside the window
 *  MISSING_IN_PAYPORT   the provider shows a payment Payport has no record of
 *  STATUS_MISMATCH      the provider shows it paid; Payport does not (failed, timed out, pending...)
 *  DUPLICATE            the statement shows the same payment more than once
 *  AMOUNT_MISMATCH      same payment, different amount
 *  CURRENCY_MISMATCH    same payment, different currency
 *  TIMING               same payment but on the other side of the statement window, or a paid
 *                       payment from the last part of the window that should appear in the
 *                       next statement
 *  MISSING_IN_STATEMENT Payport recorded the payment as paid; the provider statement does not show it
 */
public final class ReconEngine {

    public enum Result { MATCHED, MISSING_IN_PAYPORT, STATUS_MISMATCH, DUPLICATE, AMOUNT_MISMATCH, CURRENCY_MISMATCH, TIMING, MISSING_IN_STATEMENT }

    /** One statement line (transaction lines only; sweeps and fees are filtered out before). */
    public record Line(long lineId, List<String> references, long amountMinor, String currency, LocalDateTime occurredAt) {}

    /** One Payport payment that could appear in the statement. paid = the provider took the money per Payport. */
    public record Payment(String reference, Set<String> providerSideReferences, long amountMinor, String currency,
                          boolean paid, String status, LocalDateTime paidAt) {}

    public record Finding(Result result, Long lineId, String transactionReference, String providerReference,
                          Long expectedMinor, Long actualMinor, String currency, String detail, String method) {
        public Finding(Result result, Long lineId, String transactionReference, String providerReference,
                       Long expectedMinor, Long actualMinor, String currency, String detail) {
            this(result, lineId, transactionReference, providerReference, expectedMinor, actualMinor, currency, detail,
                    transactionReference == null ? null : "REFERENCE");
        }
    }

    /** How close in time a line and a payment must be to pair them by amount when no reference matches. */
    public static final Duration AMOUNT_TIME_WINDOW = Duration.ofMinutes(10);

    private ReconEngine() {}

    /**
     * Two passes: by reference, then (for lines no reference matched, with a real time of day) by
     * amount + currency + time within AMOUNT_TIME_WINDOW -- only when the pairing is unique both
     * ways, so an ambiguous pair is never guessed. Card switch reports (ZimSwitch) and airtime
     * reports carry no Payport reference, which is what the second pass is for.
     */
    public static List<Finding> match(List<Line> lines, List<Payment> payments, LocalDateTime windowStart, LocalDateTime windowEnd,
                                      Duration timingTolerance) {
        Map<String, Payment> byRef = new HashMap<>();
        for (Payment p : payments) {
            byRef.put(p.reference(), p);
            for (String r : p.providerSideReferences()) if (r != null && !r.isBlank()) byRef.putIfAbsent(r, p);
        }

        List<Finding> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Line> unreferenced = new ArrayList<>();
        for (Line l : lines) {
            Payment p = null;
            String usedRef = null;
            for (String r : l.references()) {
                if (r == null || r.isBlank()) continue;
                p = byRef.get(r.trim());
                if (p == null) p = byRef.get(PaymentAttemptStore.intentReference(r));
                if (p != null) { usedRef = r; break; }
            }
            if (p == null) {
                unreferenced.add(l);
                continue;
            }
            if (!seen.add(p.reference())) {
                out.add(new Finding(Result.DUPLICATE, l.lineId(), p.reference(), usedRef, p.amountMinor(), l.amountMinor(), l.currency(),
                        "The statement shows this payment more than once"));
                continue;
            }
            if (!p.paid()) {
                out.add(new Finding(Result.STATUS_MISMATCH, l.lineId(), p.reference(), usedRef, p.amountMinor(), l.amountMinor(), l.currency(),
                        "Provider shows the payment as paid; Payport has it as " + p.status()));
                continue;
            }
            if (l.currency() != null && p.currency() != null && !sameCurrency(l.currency(), p.currency())) {
                out.add(new Finding(Result.CURRENCY_MISMATCH, l.lineId(), p.reference(), usedRef, p.amountMinor(), l.amountMinor(), l.currency(),
                        "Statement currency " + l.currency() + ", Payport " + p.currency()));
                continue;
            }
            if (l.amountMinor() != p.amountMinor()) {
                out.add(new Finding(Result.AMOUNT_MISMATCH, l.lineId(), p.reference(), usedRef, p.amountMinor(), l.amountMinor(), p.currency(),
                        "Statement " + l.amountMinor() + ", Payport " + p.amountMinor()));
                continue;
            }
            if (p.paidAt() != null && (p.paidAt().isBefore(windowStart.minus(timingTolerance)) || p.paidAt().isAfter(windowEnd.plus(timingTolerance)))) {
                out.add(new Finding(Result.TIMING, l.lineId(), p.reference(), usedRef, p.amountMinor(), l.amountMinor(), p.currency(),
                        "Matched, but Payport recorded it at " + p.paidAt() + ", outside the statement window"));
                continue;
            }
            out.add(new Finding(Result.MATCHED, l.lineId(), p.reference(), usedRef, p.amountMinor(), l.amountMinor(), p.currency(), null));
        }

        // Second pass: unique amount + currency + time pairing.
        Map<Long, List<Payment>> candidatesByLine = new HashMap<>();
        Map<String, Integer> claims = new HashMap<>();
        for (Line l : unreferenced) {
            List<Payment> c = new ArrayList<>();
            if (l.occurredAt() != null && !l.occurredAt().toLocalTime().equals(java.time.LocalTime.MIDNIGHT)) {
                for (Payment p : payments) {
                    if (!p.paid() || seen.contains(p.reference()) || p.paidAt() == null) continue;
                    if (p.amountMinor() != l.amountMinor()) continue;
                    if (l.currency() != null && p.currency() != null && !sameCurrency(l.currency(), p.currency())) continue;
                    if (Duration.between(p.paidAt(), l.occurredAt()).abs().compareTo(AMOUNT_TIME_WINDOW) > 0) continue;
                    c.add(p);
                }
            }
            candidatesByLine.put(l.lineId(), c);
            if (c.size() == 1) claims.merge(c.get(0).reference(), 1, Integer::sum);
        }
        for (Line l : unreferenced) {
            List<Payment> c = candidatesByLine.get(l.lineId());
            String firstRef = l.references().isEmpty() ? null : l.references().get(0);
            if (c.size() == 1 && claims.get(c.get(0).reference()) == 1) {
                Payment p = c.get(0);
                seen.add(p.reference());
                out.add(new Finding(Result.MATCHED, l.lineId(), p.reference(), firstRef, p.amountMinor(), l.amountMinor(), p.currency(),
                        "Matched by amount and time (no reference in the statement)", "AMOUNT_TIME"));
            } else {
                out.add(new Finding(Result.MISSING_IN_PAYPORT, l.lineId(), null, firstRef, null, l.amountMinor(), l.currency(),
                        c.size() > 1 ? "No reference match; " + c.size() + " payments have this amount and time, so none was chosen"
                                : "Provider statement line has no matching Payport payment", null));
            }
        }

        for (Payment p : payments) {
            if (!p.paid() || seen.contains(p.reference()) || p.paidAt() == null) continue;
            if (p.paidAt().isBefore(windowStart) || p.paidAt().isAfter(windowEnd)) continue;
            if (p.paidAt().isAfter(windowEnd.minus(timingTolerance))) {
                out.add(new Finding(Result.TIMING, null, p.reference(), null, p.amountMinor(), null, p.currency(),
                        "Paid at " + p.paidAt() + ", near the end of the window: expected in the next statement"));
            } else {
                out.add(new Finding(Result.MISSING_IN_STATEMENT, null, p.reference(), null, p.amountMinor(), null, p.currency(),
                        "Payport recorded this payment as paid; the provider statement does not show it"));
            }
        }
        return out;
    }

    static boolean sameCurrency(String a, String b) {
        String x = a.trim().toUpperCase(), y = b.trim().toUpperCase();
        if ("ZIG".equals(x)) x = "ZWG";
        if ("ZIG".equals(y)) y = "ZWG";
        return x.equals(y);
    }
}
