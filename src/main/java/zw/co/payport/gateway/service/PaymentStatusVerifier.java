package zw.co.payport.gateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.entity.Transaction;
import zw.co.payport.gateway.service.adaptor.PaymentProviderAdaptor;
import zw.co.payport.gateway.service.adaptor.ecocash.EcoCashAdaptor;
import zw.co.payport.gateway.service.adaptor.onemoney.OneMoneyAdaptor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Asks the provider itself what happened to a payment.
 *
 * Two uses:
 *  - Callback trust (R4): for providers whose callbacks carry nothing we can verify
 *    (payport.payments.query-providers, default ECOCASH,ONEMONEY,ZIMSWITCH,MTN_MOMO,AIRTEL_MONEY), a callback is only a
 *    hint; the status and amount we act on come from our own authenticated status query.
 *  - Uncertain outcomes: the resolver uses verify() for every provider. EcoCash and
 *    OneMoney have dedicated query calls; any other provider is asked through its
 *    adaptor's queryStatus().
 */
@Slf4j
@Service
public class PaymentStatusVerifier {

    /** Normalised provider answer. status is SUCCESS, FAILED, PENDING or UNKNOWN. amountCents/currency may be null. */
    public record Verified(String status, Long amountCents, String currency, String providerReference, String detail,
                           String providerMessage) {
        public Verified(String status, Long amountCents, String currency, String providerReference, String detail) {
            this(status, amountCents, currency, providerReference, detail, null);
        }

        public boolean isDefinite() {
            return "SUCCESS".equals(status) || "FAILED".equals(status);
        }
    }

    /**
     * Providers whose callbacks are verified by status query. ZIMSWITCH is included because its
     * webhook handler only checked the body's structure (no signature), so a forged webhook
     * could mark a card payment paid (R45).
     */
    public static final String DEFAULT_QUERY_PROVIDERS = "ECOCASH,ONEMONEY,ZIMSWITCH,MTN_MOMO,AIRTEL_MONEY";

    private final EcoCashAdaptor ecoCashAdaptor;
    private final OneMoneyAdaptor oneMoneyAdaptor;
    private final AdaptorRegistry adaptorRegistry;
    private final Set<String> queryProviders;

    @Autowired
    public PaymentStatusVerifier(EcoCashAdaptor ecoCashAdaptor, OneMoneyAdaptor oneMoneyAdaptor, AdaptorRegistry adaptorRegistry,
                                 @Value("${payport.payments.query-providers:" + DEFAULT_QUERY_PROVIDERS + "}") String queryProviders) {
        this.ecoCashAdaptor = ecoCashAdaptor;
        this.oneMoneyAdaptor = oneMoneyAdaptor;
        this.adaptorRegistry = adaptorRegistry;
        this.queryProviders = Arrays.stream(queryProviders.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(String::toUpperCase)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** For unit tests of the pure rules. */
    PaymentStatusVerifier(EcoCashAdaptor ecoCashAdaptor, OneMoneyAdaptor oneMoneyAdaptor) {
        this(ecoCashAdaptor, oneMoneyAdaptor, null, DEFAULT_QUERY_PROVIDERS);
    }

    /** True if this provider's callbacks must be confirmed by a status query before they change anything. */
    public boolean requiresVerification(String provider) {
        return provider != null && queryProviders.contains(provider.toUpperCase());
    }

    /** Never throws: a failed query is reported as UNKNOWN so the caller leaves the payment untouched. */
    public Verified verify(String provider, Transaction tx) {
        if (provider == null) return new Verified("UNKNOWN", null, null, null, "No provider recorded");
        try {
            switch (provider.toUpperCase()) {
                case "ECOCASH": {
                    EcoCashAdaptor.QueryResult r = ecoCashAdaptor.queryTransaction(attemptReference(tx), tx.getCustomerPhone());
                    return new Verified(normalise(ecoCashAdaptor.mapEcoCashStatus(r.getTransactionStatus())),
                            toCents(r.getAmount()), r.getCurrency(), r.getEcocashReference(),
                            "EcoCash status query: " + r.getTransactionStatus());
                }
                case "ONEMONEY": {
                    OneMoneyAdaptor.C2BQueryResult r = oneMoneyAdaptor.queryC2BTransaction(attemptReference(tx));
                    return new Verified(normalise(oneMoneyAdaptor.mapOneMoneyStatus(r.getOrderStatus())),
                            toCents(r.getOrderAmt()), r.getCurrency(), r.getOrderNo(),
                            "OneMoney status query: orderStatus=" + r.getOrderStatus());
                }
                default: {
                    if (adaptorRegistry == null || !adaptorRegistry.hasPaymentAdaptor(provider)) {
                        return new Verified("UNKNOWN", null, null, null, "No status query for provider " + provider);
                    }
                    String queryRef = firstNonBlank(tx.getPaymentCorrelator(), tx.getProviderReference(), attemptReference(tx));
                    PaymentProviderAdaptor.PaymentStatusResult r = adaptorRegistry.getPaymentAdaptor(provider).queryStatus(queryRef);
                    if (r == null) return new Verified("UNKNOWN", null, null, null, provider + " status query returned nothing");
                    return new Verified(normalise(r.getStatus()), r.getAmountCents() > 0 ? r.getAmountCents() : null, null,
                            r.getProviderReference(), provider + " status query: " + r.getStatus()
                            + (r.getMessage() != null ? " (" + r.getMessage() + ")" : ""), r.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[VERIFY] Status query to {} for {} failed: {}", provider, tx.getReference(), e.getMessage());
            return new Verified("UNKNOWN", null, null, null, "Status query failed: " + e.getMessage());
        }
    }

    /**
     * Builds the event to apply from a definite provider answer, or returns null (apply
     * nothing) when the answer is not definite or the provider reports a different amount.
     *
     * The status comes from our own authenticated query, never from the callback, so it
     * can be trusted. The amount is checked when the provider reports one. When it reports
     * none (the adaptors parse a missing or non-numeric amount as 0) the success is still
     * applied, with a warning: this matches the behaviour of the reconciler that has run
     * in production, so containment does not stall real payments. Currency is logged, not
     * enforced, for the same reason: the EcoCash parser defaults a missing currency to USD,
     * which would wrongly block ZiG payments.
     */
    public PaymentProviderAdaptor.CallbackEvent toEvent(Transaction tx, Verified v, String trigger) {
        if (!v.isDefinite()) return null;
        if ("SUCCESS".equals(v.status())) {
            if (isAmountMismatch(tx, v)) {
                log.error("[VERIFY] AMOUNT MISMATCH for {}: expected {} cents, provider confirmed {} cents -- not applying; needs review",
                        tx.getReference(), tx.collectCents(), v.amountCents());
                return null;
            }
            if (v.amountCents() == null) {
                log.warn("[VERIFY] {} confirmed SUCCESS for {} but reported no amount -- applying; amount not independently confirmed",
                        trigger, tx.getReference());
            }
            if (v.currency() != null && !v.currency().isBlank() && tx.getCurrency() != null
                    && !sameCurrency(v.currency(), tx.getCurrency())) {
                log.warn("[VERIFY] Currency differs for {}: transaction {}, provider reported {} -- applied; review if unexpected",
                        tx.getReference(), tx.getCurrency(), v.currency());
            }
        }
        return PaymentProviderAdaptor.CallbackEvent.builder()
                .payportReference(attemptReference(tx))
                .providerReference(v.providerReference() != null ? v.providerReference() : tx.getProviderReference())
                .failureMessage(v.providerMessage())
                .status(v.status())
                .amountCents(v.amountCents() != null ? v.amountCents() : 0)
                .currency(v.currency() != null ? v.currency() : tx.getCurrency())
                .timestamp(LocalDateTime.now().toString())
                .rawPayload("Confirmed by provider status query (" + trigger + "): " + v.detail())
                .build();
    }

    /** A definite SUCCESS for a different amount than we asked for: never applied automatically. */
    public boolean isAmountMismatch(Transaction tx, Verified v) {
        return "SUCCESS".equals(v.status()) && v.amountCents() != null && v.amountCents().longValue() != tx.collectCents();
    }

    static String normalise(String s) {
        if (s == null) return "UNKNOWN";
        switch (s.toUpperCase()) {
            case "SUCCESS": case "SUCCESSFUL": case "COMPLETED": case "CONFIRMED": return "SUCCESS";
            case "FAILED": case "FAILURE": case "DECLINED": case "CANCELLED": case "REJECTED": case "EXPIRED": return "FAILED";
            case "PENDING": case "PROCESSING": case "SUBMITTED": return "PENDING";
            default: return "UNKNOWN";
        }
    }

    /** Provider amounts arrive as decimal major units; convert exactly, never through binary floating point rounding surprises. */
    static Long toCents(double majorUnits) {
        if (majorUnits <= 0 || Double.isNaN(majorUnits) || Double.isInfinite(majorUnits)) return null;
        return new BigDecimal(Double.toString(majorUnits)).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** ZiG appears as ZWG or ZIG depending on the provider. */
    static boolean sameCurrency(String a, String b) {
        return canonical(a).equals(canonical(b));
    }

    private static String canonical(String c) {
        String u = c.trim().toUpperCase();
        return "ZIG".equals(u) ? "ZWG" : u;
    }

    /** The reference the current attempt was sent with (the payment reference for attempt 1). */
    public static String attemptReference(Transaction tx) {
        String a = tx.getPaymentAttemptReference();
        return a != null && !a.isBlank() ? a : tx.getReference();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }
}
