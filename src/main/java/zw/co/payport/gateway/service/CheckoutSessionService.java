package zw.co.payport.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.entity.CheckoutSession;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.model.request.InitiateTransactionRequest;
import zw.co.payport.gateway.model.request.PayTransactionRequest;
import zw.co.payport.gateway.model.response.TransactionResponse;
import zw.co.payport.gateway.entity.ApiKey;
import zw.co.payport.gateway.repository.ApiKeyRepository;
import zw.co.payport.gateway.repository.CheckoutSessionRepository;
import zw.co.payport.gateway.repository.MerchantRepository;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 1 of the Merchants Module: Checkout Sessions. A merchant's server
 * creates a session via the API; the customer completes payment on a
 * hosted page; the actual payment collection reuses the existing,
 * already-hardened TransactionOrchestratorService rather than duplicating
 * EcoCash/OneMoney integration logic here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutSessionService {

    private static final Set<String> TERMINAL_SUCCESS = Set.of("COMPLETED");
    private static final Set<String> TERMINAL_FAILURE = Set.of("PAYMENT_FAILED", "FULFILMENT_FAILED", "CANCELLED");
    private static final int DEFAULT_EXPIRY_MINUTES = 30;

    private final CheckoutSessionRepository checkoutSessionRepository;
    private final MerchantRepository merchantRepository;
    private final TransactionOrchestratorService orchestrator;
    private final WebhookService webhookService;
    private final EmailService emailService;
    private final CommissionService commissionService;
    private final RefundService refundService;
    private final ApiKeyRepository apiKeyRepository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final SecureRandom secureRandom = new SecureRandom();

    public record CreateSessionRequest(
            String reference, Long amountCents, String currency, String description,
            String customerPhone, String customerEmail,
            String successUrl, String cancelUrl, String metadata) {}

    public CheckoutSession createSession(Merchant merchant, Long apiKeyId, CreateSessionRequest req) {
        if (req.reference() != null) {
            checkoutSessionRepository.findByMerchantIdAndReference(merchant.getId(), req.reference())
                    .ifPresent(existing -> {
                        throw new RuntimeException("A checkout session with reference '" + req.reference() + "' already exists for this merchant");
                    });
        }
        if (req.amountCents() == null || req.amountCents() < 1) {
            throw new RuntimeException("amountCents must be a positive integer");
        }
        String currency = (req.currency() == null || req.currency().isBlank()) ? merchant.getDefaultCurrency() : req.currency().toUpperCase();
        if (!"USD".equals(currency) && !"ZWG".equals(currency)) {
            throw new RuntimeException("currency must be USD or ZWG");
        }

        boolean livemode = resolveLivemode(merchant, apiKeyId);
        if (livemode && apiKeyId != null) {
            // Phase 4 market enablement: a live key takes payments only in currencies PayPort enabled for the merchant.
            Integer enabled = jdbc.queryForObject("SELECT COUNT(*) FROM merchant_enablements WHERE merchant_id = ? AND currency = ? AND status = 'ENABLED'",
                    Integer.class, merchant.getId(), currency);
            if (enabled == null || enabled == 0) {
                throw new RuntimeException("Live payments in " + currency + " are not enabled for this account");
            }
        }

        CheckoutSession session = CheckoutSession.builder()
                .merchantId(merchant.getId())
                .reference(req.reference() != null ? req.reference() : "sess_" + randomToken())
                .amountCents(req.amountCents())
                .currency(currency)
                .description(req.description())
                .status("CREATED")
                .customerPhone(req.customerPhone())
                .customerEmail(req.customerEmail())
                .successUrl(req.successUrl())
                .cancelUrl(req.cancelUrl())
                .metadata(req.metadata())
                .apiKeyId(apiKeyId)
                .livemode(livemode)
                .expiresAt(LocalDateTime.now().plusMinutes(DEFAULT_EXPIRY_MINUTES))
                .build();

        checkoutSessionRepository.save(session);
        log.info("[CHECKOUT] Session created: id={} merchant={} amount={} {} livemode={}", session.getId(), merchant.getId(), req.amountCents(), currency, livemode);
        return session;
    }

    /**
     * Decides, once, whether a session moves real money (R1 fix).
     *
     *   - Created with an API key: that key's environment (LIVE = real, TEST = simulated).
     *   - Created without an API key (payment links, merchant-dashboard checkouts): live.
     *     These are customer-facing features of an approved merchant; they were previously
     *     simulated by accident, which marked unpaid sessions PAID and made them settleable.
     *
     * An API key id that no longer resolves to a key is refused rather than guessed.
     */
    boolean resolveLivemode(Merchant merchant, Long apiKeyId) {
        ApiKey key = apiKeyId == null ? null : apiKeyRepository.findById(apiKeyId).orElse(null);
        return decideLivemode(merchant.getId(), apiKeyId, key);
    }

    /** Pure rule behind resolveLivemode, unit-tested. */
    static boolean decideLivemode(Long merchantId, Long apiKeyId, ApiKey key) {
        if (apiKeyId == null) {
            return true;
        }
        if (key == null) {
            throw new RuntimeException("API key not found for this checkout session");
        }
        if (!merchantId.equals(key.getMerchantId())) {
            throw new RuntimeException("API key does not belong to this merchant");
        }
        return "LIVE".equals(key.getEnvironment());
    }

    public List<CheckoutSession> listForMerchant(Long merchantId) {
        return checkoutSessionRepository.findByMerchantId(merchantId);
    }

    /**
     * Gross total of PAID sessions per currency -- deliberately not called
     * a "balance" anywhere in the service layer, only in the controller's
     * response note. This is pre-commission, pre-settlement -- Phase 4
     * (Settlements) is what turns this into an actual net payable figure.
     */
    public record CurrencyTotals(long grossCents, long commissionCents, long netCents) {}

    /**
     * Per-currency, not blended -- USD and ZWG are genuinely separate
     * ledgers, matching how the rest of the platform already treats
     * multi-currency (see the design doc's Section 11).
     */
    public Map<String, CurrencyTotals> balanceByCurrency(Long merchantId) {
        Map<String, long[]> raw = new LinkedHashMap<>(); // [gross, commission, net]
        for (CheckoutSession s : checkoutSessionRepository.findByMerchantId(merchantId)) {
            // Test-mode sessions never represent money owed to the merchant.
            if (!Boolean.TRUE.equals(s.getLivemode())) continue;
            if (!"PAID".equals(s.getStatus()) && !"PARTIALLY_REFUNDED".equals(s.getStatus()) && !"REFUNDED".equals(s.getStatus())) continue;
            // Already claimed by a Settlement -- it's been moved from
            // "owed" to "settled", tracked in the settlements table now,
            // not double-counted here as well.
            if (s.getSettlementId() != null) continue;

            // A refund reduces what the merchant is actually owed -- a
            // REFUNDED session contributes zero here, a PARTIALLY_REFUNDED
            // one contributes only what's left, calculated proportionally
            // against gross/commission/net so the commission itself scales
            // down too, not just the headline amount.
            long refunded = refundService.totalRefunded(s.getId());
            long remainingGross = s.getAmountCents() - refunded;
            if (remainingGross <= 0) continue;

            double remainingRatio = (double) remainingGross / s.getAmountCents();
            long fullCommission = s.getCommissionAmountCents() != null ? s.getCommissionAmountCents() : 0;
            long fullNet = s.getNetAmountCents() != null ? s.getNetAmountCents() : s.getAmountCents();

            long[] totals = raw.computeIfAbsent(s.getCurrency(), k -> new long[3]);
            totals[0] += remainingGross;
            totals[1] += Math.round(fullCommission * remainingRatio);
            totals[2] += Math.round(fullNet * remainingRatio);
        }
        Map<String, CurrencyTotals> result = new LinkedHashMap<>();
        raw.forEach((currency, t) -> result.put(currency, new CurrencyTotals(t[0], t[1], t[2])));
        return result;
    }

    public record MethodBreakdown(String currency, String paymentMethodCode, long count, long grossCents) {}

    /** Collections grouped by payment method and currency -- what the merchant dashboard's method-breakdown chart reads from. */
    public List<MethodBreakdown> collectionsByPaymentMethod(Long merchantId) {
        Map<String, long[]> raw = new LinkedHashMap<>(); // key = "currency|method" -> [count, gross]
        for (CheckoutSession s : checkoutSessionRepository.findByMerchantId(merchantId)) {
            if (!Boolean.TRUE.equals(s.getLivemode())) continue;
            if (!"PAID".equals(s.getStatus())) continue;
            String method = s.getPaymentMethodCode() != null ? s.getPaymentMethodCode() : "UNKNOWN";
            String key = s.getCurrency() + "|" + method;
            long[] totals = raw.computeIfAbsent(key, k -> new long[2]);
            totals[0] += 1;
            totals[1] += s.getAmountCents();
        }
        List<MethodBreakdown> result = new java.util.ArrayList<>();
        raw.forEach((key, t) -> {
            String[] parts = key.split("\\|", 2);
            result.add(new MethodBreakdown(parts[0], parts[1], t[0], t[1]));
        });
        return result;
    }

    public record DailyTrend(String date, long count, long grossCents) {}

    /** Daily totals over the trailing N days -- feeds the dashboard's trend chart. Days with zero activity are included as zero, not skipped, so a chart doesn't show misleading gaps. */
    public List<DailyTrend> dailyTrends(Long merchantId, String currency, int days) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days - 1).toLocalDate().atStartOfDay();
        Map<String, long[]> byDay = new LinkedHashMap<>(); // [count, gross]

        for (int i = 0; i < days; i++) {
            String key = cutoff.plusDays(i).toLocalDate().toString();
            byDay.put(key, new long[2]);
        }

        for (CheckoutSession s : checkoutSessionRepository.findByMerchantId(merchantId)) {
            if (!Boolean.TRUE.equals(s.getLivemode())) continue;
            if (!"PAID".equals(s.getStatus())) continue;
            if (currency != null && !currency.equalsIgnoreCase(s.getCurrency())) continue;
            if (s.getCreatedAt().isBefore(cutoff)) continue;
            String key = s.getCreatedAt().toLocalDate().toString();
            long[] totals = byDay.get(key);
            if (totals != null) {
                totals[0] += 1;
                totals[1] += s.getAmountCents();
            }
        }

        List<DailyTrend> result = new java.util.ArrayList<>();
        byDay.forEach((date, t) -> result.add(new DailyTrend(date, t[0], t[1])));
        result.sort((a, b) -> a.date().compareTo(b.date()));
        return result;
    }

    public CheckoutSession getForMerchant(Merchant merchant, Long sessionId) {
        CheckoutSession session = getOrThrow(sessionId);
        if (!session.getMerchantId().equals(merchant.getId())) {
            throw new RuntimeException("Checkout session not found: " + sessionId);
        }
        refreshStatusFromTransaction(session);
        return session;
    }

    /** Public -- used by the hosted checkout page. No merchant auth, so callers must not leak anything beyond what's safe for the end customer to see. */
    public CheckoutSession getPublic(Long sessionId) {
        CheckoutSession session = getOrThrow(sessionId);
        refreshStatusFromTransaction(session);
        expireIfPastDue(session);
        return session;
    }

    public CheckoutSession cancel(Merchant merchant, Long sessionId) {
        CheckoutSession session = getForMerchant(merchant, sessionId);
        if (!"CREATED".equals(session.getStatus()) && !"PENDING".equals(session.getStatus())) {
            throw new RuntimeException("Only a CREATED or PENDING session can be cancelled (current status: " + session.getStatus() + ")");
        }
        session.setStatus("CANCELLED");
        session.setUpdatedAt(LocalDateTime.now());
        checkoutSessionRepository.save(session);
        return session;
    }

    /**
     * Customer submits their payment method on the hosted page. This is
     * where the actual EcoCash/OneMoney collection happens, via the exact
     * same orchestrator every other Payport product uses.
     */
    public record PaymentSubmissionResult(CheckoutSession session, String widgetJsUrl, String paymentCorrelator) {}

    /**
     * Test or live is a property of the session (decided at creation), never of the
     * caller: the hosted page is public, so nothing the browser sends may choose
     * whether a real provider is called.
     */
    public PaymentSubmissionResult submitPayment(Long sessionId, String customerPhone, String customerEmail, String paymentMethodCode, String ipAddress) throws Exception {
        CheckoutSession session = getOrThrow(sessionId);
        boolean isTestMode = !Boolean.TRUE.equals(session.getLivemode());
        expireIfPastDue(session);

        if (!"CREATED".equals(session.getStatus())) {
            throw new RuntimeException("This checkout session cannot accept payment (status: " + session.getStatus() + ")");
        }

        Merchant merchant = merchantRepository.findById(session.getMerchantId())
                .orElseThrow(() -> new RuntimeException("Merchant not found"));
        if (!"ACTIVE".equals(merchant.getStatus())) {
            throw new RuntimeException("This merchant account is not currently active");
        }

        String productTypeCode = "ZWG".equals(session.getCurrency()) ? "MERCHANT_CHECKOUT_ZWG" : "MERCHANT_CHECKOUT";
        String txReference = generateTransactionReference();

        InitiateTransactionRequest initiateReq = new InitiateTransactionRequest();
        initiateReq.setReference(txReference);
        initiateReq.setProductTypeCode(productTypeCode);
        initiateReq.setRecipientIdentifier(customerPhone);
        initiateReq.setAmountCents(session.getAmountCents());
        initiateReq.setCurrency(session.getCurrency());
        initiateReq.setCustomerPhone(customerPhone);
        initiateReq.setCustomerEmail(customerEmail);
        initiateReq.setChannel("API");

        orchestrator.initiateTransaction(initiateReq, ipAddress);

        session.setTransactionReference(txReference);
        session.setCustomerPhone(customerPhone);
        session.setCustomerEmail(customerEmail);
        session.setPaymentMethodCode(paymentMethodCode);
        session.setStatus("PENDING");
        session.setUpdatedAt(LocalDateTime.now());
        checkoutSessionRepository.save(session);

        TransactionResponse result;
        if (isTestMode) {
            // Structural, not conventional: a test-mode session never reaches
            // the real submitPayment() call below at all.
            log.info("[CHECKOUT] Test-mode session -- simulating payment instead of calling a real provider for session {}", sessionId);
            zw.co.payport.gateway.merchant.SandboxScenarios.Outcome sandbox = zw.co.payport.gateway.merchant.SandboxScenarios.forPhone(customerPhone);
            result = orchestrator.simulateTestPayment(txReference, sandbox.success(), sandbox.providerMessage());
        } else {
            PayTransactionRequest payReq = new PayTransactionRequest();
            payReq.setReference(txReference);
            payReq.setPaymentMethodCode(paymentMethodCode);
            payReq.setCustomerPhone(customerPhone);
            result = orchestrator.submitPayment(payReq);
        }

        refreshStatusFromTransactionStatus(session, result.getStatus() != null ? result.getStatus().toString() : null);
        checkoutSessionRepository.save(session);

        log.info("[CHECKOUT] Payment submitted for session {}: transaction={} status={}", sessionId, txReference, session.getStatus());
        return new PaymentSubmissionResult(session, result.getWidgetJsUrl(), result.getPaymentCorrelator());
    }

    /** Scheduled sweeper calls this -- see CheckoutSessionExpiryScheduler. */
    public int expireOverdueSessions() {
        List<CheckoutSession> overdue = checkoutSessionRepository.findByStatusAndExpiresAtBefore("CREATED", LocalDateTime.now());
        overdue.addAll(checkoutSessionRepository.findByStatusAndExpiresAtBefore("PENDING", LocalDateTime.now()));
        for (CheckoutSession s : overdue) {
            s.setStatus("EXPIRED");
            s.setUpdatedAt(LocalDateTime.now());
        }
        if (!overdue.isEmpty()) {
            checkoutSessionRepository.saveAll(overdue);
            log.info("[CHECKOUT] Expired {} overdue session(s)", overdue.size());
        }
        return overdue.size();
    }

    private void expireIfPastDue(CheckoutSession session) {
        if (("CREATED".equals(session.getStatus()) || "PENDING".equals(session.getStatus()))
                && session.getExpiresAt().isBefore(LocalDateTime.now())) {
            session.setStatus("EXPIRED");
            session.setUpdatedAt(LocalDateTime.now());
            checkoutSessionRepository.save(session);
        }
    }

    /**
     * Public entry point for TransactionStatusSyncListener -- syncs
     * whichever CheckoutSession is linked to this transaction reference,
     * called on every transaction status change rather than only when
     * something happens to poll the session directly. A no-op if no
     * session is linked to this reference (most transactions aren't
     * merchant checkouts at all).
     */
    public void syncFromTransactionReference(String transactionReference, String newTxStatus) {
        checkoutSessionRepository.findByTransactionReference(transactionReference)
                .ifPresent(session -> refreshStatusFromTransactionStatus(session, newTxStatus));
    }

    private void refreshStatusFromTransaction(CheckoutSession session) {
        if (session.getTransactionReference() == null) return;
        try {
            TransactionResponse tx = orchestrator.getTransaction(session.getTransactionReference());
            refreshStatusFromTransactionStatus(session, tx.getStatus() != null ? tx.getStatus().toString() : null);
        } catch (Exception e) {
            log.warn("[CHECKOUT] Could not refresh status for session {} from transaction {}: {}",
                    session.getId(), session.getTransactionReference(), e.getMessage());
        }
    }

    private void refreshStatusFromTransactionStatus(CheckoutSession session, String txStatus) {
        if (txStatus == null) return;
        String newStatus = session.getStatus();
        if (TERMINAL_SUCCESS.contains(txStatus)) {
            newStatus = "PAID";
        } else if (TERMINAL_FAILURE.contains(txStatus)) {
            newStatus = "FAILED";
        } else {
            newStatus = "PENDING";
        }
        if (!newStatus.equals(session.getStatus())) {
            session.setStatus(newStatus);
            session.setUpdatedAt(LocalDateTime.now());

            // Fires exactly once, right here, precisely because this block
            // only runs when the status is genuinely changing -- not on
            // every status poll from the merchant or the hosted page.
            if ("PAID".equals(newStatus)) {
                applyCommission(session);
                webhookService.notifySessionStatusChanged(session, "checkout_session.paid");
                sendConfirmationEmail(session);
            } else if ("FAILED".equals(newStatus)) {
                webhookService.notifySessionStatusChanged(session, "checkout_session.failed");
            }
            // Explicit save rather than relying on ambient transactional
            // dirty-checking -- this method is now called from an event
            // listener (see TransactionStatusSyncListener) which may not
            // share a transaction context with the original status
            // change, so an implicit flush can't be assumed here.
            checkoutSessionRepository.save(session);
        }
    }

    /**
     * Frozen at the moment payment succeeds -- commissionBpsApplied
     * records the exact rate used, so a later change to the merchant's
     * rate (or the platform default) can never retroactively alter a
     * collection that already happened.
     */
    private void applyCommission(CheckoutSession session) {
        merchantRepository.findById(session.getMerchantId()).ifPresent(merchant -> {
            CommissionService.CommissionResult result = commissionService.calculate(merchant, session.getAmountCents());
            session.setCommissionBpsApplied(result.bpsApplied());
            session.setCommissionAmountCents(result.commissionAmountCents());
            session.setNetAmountCents(result.netAmountCents());
        });
    }

    private void sendConfirmationEmail(CheckoutSession session) {
        if (session.getCustomerEmail() == null || session.getCustomerEmail().isBlank()) return;
        merchantRepository.findById(session.getMerchantId()).ifPresent(merchant -> {
            String amount = formatAmount(session.getAmountCents(), session.getCurrency());
            emailService.sendPaymentConfirmation(session.getCustomerEmail(), merchant.getBusinessName(), amount, session.getReference());
        });
    }

    private String formatAmount(Long amountCents, String currency) {
        double value = amountCents / 100.0;
        String symbol = "ZWG".equals(currency) ? "ZWG " : "$";
        return symbol + String.format("%.2f", value);
    }

    private CheckoutSession getOrThrow(Long id) {
        return checkoutSessionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Checkout session not found: " + id));
    }

    private String generateTransactionReference() {
        String datePart = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String seq = String.format("%06d", secureRandom.nextInt(1_000_000));
        return "PP-API-" + datePart + "-" + seq;
    }

    private String randomToken() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
