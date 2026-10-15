package zw.co.payport.gateway.psp.legacy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.repository.MerchantRepository;
import zw.co.payport.gateway.repository.NetworkPrefixRepository;
import zw.co.payport.gateway.service.CheckoutSessionService;
import zw.co.payport.gateway.repository.ProviderHealthRepository;
import zw.co.payport.gateway.repository.TransactionRepository;
import zw.co.payport.gateway.service.AdaptorRegistry;
import zw.co.payport.gateway.service.FulfilmentQueueService;
import zw.co.payport.gateway.service.adaptor.PaymentProviderAdaptor;
import zw.co.payport.gateway.service.adaptor.ecocash.EcoCashAdaptor;
import zw.co.payport.gateway.service.adaptor.onemoney.OneMoneyAdaptor;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Bridge to the gateway's payment engine: applying provider outcomes through
 * the orchestrator's state machine, querying rails for status, provider
 * health and wallet prefix lookup. PSP modules never call adaptors directly.
 */
@Component
public class LegacyPayments {

    private static final Logger log = LoggerFactory.getLogger(LegacyPayments.class);

    /** Normalised provider status answer. status: SUCCESS | FAILED | PENDING | UNKNOWN */
    public record ProviderStatus(String status, Long amountMinor, String currency, String providerReference, String detail) {
        public boolean definitive() { return "SUCCESS".equals(status) || "FAILED".equals(status); }
    }

    private final TransactionOrchestratorService orchestrator;
    private final AdaptorRegistry adaptors;
    private final EcoCashAdaptor ecoCash;
    private final OneMoneyAdaptor oneMoney;
    private final TransactionRepository transactions;
    private final ProviderHealthRepository health;
    private final NetworkPrefixRepository prefixes;
    private final FulfilmentQueueService fulfilmentQueue;
    private final org.springframework.beans.factory.ObjectProvider<CheckoutSessionService> checkoutSessions;
    private final MerchantRepository merchants;

    public LegacyPayments(TransactionOrchestratorService orchestrator, AdaptorRegistry adaptors, EcoCashAdaptor ecoCash,
                          OneMoneyAdaptor oneMoney, TransactionRepository transactions, ProviderHealthRepository health,
                          NetworkPrefixRepository prefixes, FulfilmentQueueService fulfilmentQueue,
                          org.springframework.beans.factory.ObjectProvider<CheckoutSessionService> checkoutSessions,
                          MerchantRepository merchants) {
        this.checkoutSessions = checkoutSessions;
        this.merchants = merchants;
        this.orchestrator = orchestrator;
        this.adaptors = adaptors;
        this.ecoCash = ecoCash;
        this.oneMoney = oneMoney;
        this.transactions = transactions;
        this.health = health;
        this.prefixes = prefixes;
        this.fulfilmentQueue = fulfilmentQueue;
    }

    /**
     * Feeds a verified outcome through the orchestrator's normal callback path
     * (same state machine, SMS and fulfilment enqueueing as a real callback).
     */
    public void applyOutcome(String reference, String status, String providerReference, long amountMinor,
                             String currency, String rawPayload, String failureMessage) {
        PaymentProviderAdaptor.CallbackEvent ev = PaymentProviderAdaptor.CallbackEvent.builder()
                .payportReference(reference)
                .providerReference(providerReference)
                .status(status)
                .amountCents(amountMinor)
                .currency(currency)
                .timestamp(LocalDateTime.now().toString())
                .rawPayload(rawPayload)
                .failureMessage(failureMessage)
                .build();
        orchestrator.handlePaymentCallback(ev);
    }

    /** Moves PAYMENT_PENDING -> PAYMENT_TIMEOUT through the state machine (audited), meaning "outcome unknown", never "failed". */
    public void markOutcomeUnknown(String reference, String reason) {
        orchestrator.markPaymentOutcomeUnknown(reference, reason);
    }

    public boolean hasPaymentAdaptor(String provider) {
        return provider != null && adaptors.hasPaymentAdaptor(provider);
    }

    public PaymentProviderAdaptor.CallbackValidationResult validateCallback(String provider, Map<String, String> headers, String body) {
        return adaptors.getPaymentAdaptor(provider).validateCallback(headers, body);
    }

    public PaymentProviderAdaptor.CallbackEvent parseCallback(String provider, String body) throws Exception {
        return adaptors.getPaymentAdaptor(provider).parseCallback(body);
    }

    /** Asks the rail directly what happened to a payment. Never throws: failures come back as UNKNOWN. */
    public ProviderStatus queryProvider(String provider, String reference, String providerReference, String customerPhone) {
        if (provider == null) return new ProviderStatus("UNKNOWN", null, null, null, "No provider recorded");
        try {
            switch (provider.toUpperCase()) {
                case "ECOCASH" -> {
                    EcoCashAdaptor.QueryResult r = ecoCash.queryTransaction(reference, customerPhone);
                    String mapped = ecoCash.mapEcoCashStatus(r.getTransactionStatus());
                    return new ProviderStatus(normalise(mapped), r.getAmount() > 0 ? Long.valueOf(Math.round(r.getAmount() * 100)) : null, r.getCurrency(),
                            r.getEcocashReference(), "EcoCash: " + r.getTransactionStatus());
                }
                case "ONEMONEY" -> {
                    OneMoneyAdaptor.C2BQueryResult r = oneMoney.queryC2BTransaction(reference);
                    String mapped = oneMoney.mapOneMoneyStatus(r.getOrderStatus());
                    return new ProviderStatus(normalise(mapped), r.getOrderAmt() > 0 ? Long.valueOf(Math.round(r.getOrderAmt() * 100)) : null, r.getCurrency(),
                            r.getOrderNo(), "OneMoney: " + r.getOrderStatus());
                }
                default -> {
                    if (!adaptors.hasPaymentAdaptor(provider)) {
                        return new ProviderStatus("UNKNOWN", null, null, null, "No adaptor for " + provider);
                    }
                    PaymentProviderAdaptor.PaymentStatusResult r = adaptors.getPaymentAdaptor(provider)
                            .queryStatus(providerReference != null ? providerReference : reference);
                    return new ProviderStatus(normalise(r.getStatus()), r.getAmountCents() > 0 ? Long.valueOf(r.getAmountCents()) : null,
                            null, r.getProviderReference(), r.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[PSP] Status query to {} for {} failed: {}", provider, reference, e.getMessage());
            return new ProviderStatus("UNKNOWN", null, null, null, "Query failed: " + e.getMessage());
        }
    }

    private static String normalise(String s) {
        if (s == null) return "UNKNOWN";
        return switch (s.toUpperCase()) {
            case "SUCCESS", "SUCCESSFUL", "COMPLETED", "CONFIRMED" -> "SUCCESS";
            case "FAILED", "FAILURE", "DECLINED", "CANCELLED", "REJECTED", "EXPIRED" -> "FAILED";
            case "PENDING", "PROCESSING", "SUBMITTED" -> "PENDING";
            default -> "UNKNOWN";
        };
    }

    /** Provider health as recorded by the gateway's health scheduler: code -> status (UP / DEGRADED / DOWN). */
    public Map<String, String> providerHealth() {
        return health.findAll().stream()
                .filter(h -> h.getProviderCode() != null)
                .collect(Collectors.toMap(h -> h.getProviderCode().toUpperCase(),
                        h -> h.getStatus() == null ? "UNKNOWN" : h.getStatus().toUpperCase(), (a, b) -> a));
    }

    public long recentFailures(String provider, LocalDateTime since) {
        return transactions.countRecentFailuresByProvider(provider, since);
    }

    /** Mobile network for a Zimbabwean number, from the gateway's prefix table (e.g. 077 -> ECONET). */
    public Optional<String> networkForMsisdn(String msisdn) {
        if (msisdn == null) return Optional.empty();
        String digits = msisdn.replaceAll("\\D", "");
        if (digits.startsWith("263")) digits = "0" + digits.substring(3);
        for (int len = 4; len >= 3; len--) {
            if (digits.length() < len) continue;
            var p = prefixes.findByPrefix(digits.substring(0, len));
            if (p.isPresent()) return Optional.of(p.get().getNetworkCode().toUpperCase());
        }
        return Optional.empty();
    }

    /** Re-queue fulfilment for a confirmed payment (the gateway's queue is in-memory and lost on restart). */
    public void requeueFulfilment(String reference) {
        fulfilmentQueue.enqueue(reference);
    }

    /**
     * Creates a hosted-checkout session for a merchant (used by the WhatsApp
     * bot, partner API and POS). Resolved lazily because CheckoutSessionService
     * itself calls into the PSP modules.
     * @return the checkout session id (the hosted page is /checkout/{id})
     */
    public long createCheckout(long merchantId, String reference, long amountMinor, String currency, String description,
                               String customerPhone) {
        var merchant = merchants.findById(merchantId).orElseThrow(() -> new IllegalArgumentException("Merchant not found: " + merchantId));
        var req = new CheckoutSessionService.CreateSessionRequest(reference, amountMinor, currency, description,
                customerPhone, null, null, null, null);
        return checkoutSessions.getObject().createSession(merchant, null, req).getId();
    }

    /** Starts payment on a checkout session (wallet push to the customer's phone). */
    public CheckoutSessionService.PaymentSubmissionResult payCheckout(long sessionId, String customerPhone, String methodCode,
                                                                     String ipAddress) throws Exception {
        return checkoutSessions.getObject().submitPayment(sessionId, customerPhone, null, methodCode, ipAddress, false);
    }
}
