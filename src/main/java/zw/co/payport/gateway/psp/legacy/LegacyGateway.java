package zw.co.payport.gateway.psp.legacy;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.entity.CheckoutSession;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.entity.Transaction;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.repository.CheckoutSessionRepository;
import zw.co.payport.gateway.repository.MerchantRepository;
import zw.co.payport.gateway.repository.SettlementRepository;
import zw.co.payport.gateway.repository.TransactionRepository;
import zw.co.payport.gateway.service.CommissionService;
import zw.co.payport.gateway.service.RefundService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Anti-corruption layer between the PSP modules and the original gateway.
 *
 * PSP code reads the gateway's merchants, transactions and checkout sessions
 * only through these snapshots, so the gateway's entities can keep evolving
 * (or be replaced) without rippling through the ledger, settlement and risk
 * modules. The only write is {@link #markSessionsMigrated}, used once per
 * merchant by the ledger cutover.
 */
@Component
public class LegacyGateway {

    public record MerchantSnapshot(long id, String businessName, String status, String contactEmail,
                                   String contactPhone, String defaultCurrency, Integer commissionRateBps,
                                   int riskScore, String riskFlags, String payoutAccountType,
                                   String payoutAccountDetails, String businessCategory, String registrationNumber) {
        public boolean isActive() { return "ACTIVE".equals(status); }
    }

    public record TransactionSnapshot(String reference, TransactionStatus status, String channel, String productCode,
                                      String productName, String fulfilmentProviderCode, String paymentProviderCode,
                                      String paymentMethodCode, long amountCents, String currency, String customerPhone,
                                      String recipientIdentifier, String providerReference, Long userId,
                                      LocalDateTime createdAt, LocalDateTime paymentConfirmedAt, LocalDateTime completedAt,
                                      String refundMethod, String ipAddress, String deviceType) {}

    /** A gateway checkout session whose money has not yet been settled by the old settlement code. */
    public record UnsettledSession(long sessionId, String transactionReference, String currency, long netRemainingCents) {}

    public record CheckoutLink(long sessionId, long merchantId, String merchantReference, long amountCents,
                               String currency, String paymentMethodCode, String status) {}

    private final MerchantRepository merchants;
    private final TransactionRepository transactions;
    private final CheckoutSessionRepository sessions;
    private final CommissionService commissionService;
    private final RefundService refundService;
    private final SettlementRepository legacySettlements;

    public LegacyGateway(MerchantRepository merchants, TransactionRepository transactions,
                         CheckoutSessionRepository sessions, CommissionService commissionService,
                         RefundService refundService, SettlementRepository legacySettlements) {
        this.merchants = merchants;
        this.transactions = transactions;
        this.sessions = sessions;
        this.commissionService = commissionService;
        this.refundService = refundService;
        this.legacySettlements = legacySettlements;
    }

    /**
     * Unsettled sessions with what the old code would have paid the merchant
     * (same proportional arithmetic as the old SettlementEngine, in integer maths).
     */
    @Transactional(readOnly = true)
    public List<UnsettledSession> unsettledSessions(long merchantId) {
        java.util.List<UnsettledSession> out = new java.util.ArrayList<>();
        for (CheckoutSession s : sessions.findByMerchantIdAndSettlementIdIsNull(merchantId)) {
            if (!java.util.Set.of("PAID", "PARTIALLY_REFUNDED", "REFUNDED").contains(s.getStatus())) continue;
            long gross = s.getAmountCents();
            long refunded = refundService.totalRefunded(s.getId());
            long remaining = Math.max(0, gross - refunded);
            long fullNet = s.getNetAmountCents() != null ? s.getNetAmountCents() : gross;
            long net = gross == 0 ? 0 : Math.floorDiv(Math.multiplyExact(fullNet, remaining) + gross / 2, gross);
            out.add(new UnsettledSession(s.getId(), s.getTransactionReference(), s.getCurrency(), net));
        }
        return out;
    }

    /** Cutover only: records that these sessions' balance now lives in the PSP ledger, so the old code never settles them. */
    @Transactional
    public void markSessionsMigrated(long merchantId, String currency, java.util.List<Long> sessionIds, long netTotalCents) {
        if (sessionIds.isEmpty()) return;
        zw.co.payport.gateway.entity.Settlement marker = zw.co.payport.gateway.entity.Settlement.builder()
                .merchantId(merchantId)
                .periodStart(LocalDateTime.of(2000, 1, 1, 0, 0))
                .periodEnd(LocalDateTime.now())
                .currency(currency)
                .sessionCount(sessionIds.size())
                .grossAmountCents(netTotalCents)
                .commissionAmountCents(0L)
                .refundAmountCents(0L)
                .netAmountCents(netTotalCents)
                .status("MIGRATED_TO_LEDGER")
                .payoutReference("PSP opening balance")
                .build();
        legacySettlements.save(marker);
        for (CheckoutSession s : sessions.findAllById(sessionIds)) {
            s.setSettlementId(marker.getId());
            sessions.save(s);
        }
    }

    @Transactional(readOnly = true)
    public Optional<MerchantSnapshot> merchant(long merchantId) {
        return merchants.findById(merchantId).map(LegacyGateway::snap);
    }

    @Transactional(readOnly = true)
    public List<MerchantSnapshot> merchantsWithStatus(String status) {
        return merchants.findByStatus(status).stream().map(LegacyGateway::snap).toList();
    }

    @Transactional(readOnly = true)
    public List<MerchantSnapshot> allMerchants() {
        return merchants.findAll().stream().map(LegacyGateway::snap).toList();
    }

    /** The commission the gateway would have charged; used when a merchant has no PSP fee agreement yet. */
    @Transactional(readOnly = true)
    public int legacyCommissionBps(long merchantId) {
        return merchants.findById(merchantId).map(commissionService::resolveEffectiveBps)
                .orElseGet(commissionService::getPlatformDefaultBps);
    }

    @Transactional(readOnly = true)
    public Optional<TransactionSnapshot> transaction(String reference) {
        return transactions.findByReference(reference).map(LegacyGateway::snap);
    }

    @Transactional(readOnly = true)
    public List<TransactionSnapshot> transactionsInStatus(List<TransactionStatus> statuses) {
        return transactions.findByStatusIn(statuses).stream().map(LegacyGateway::snap).toList();
    }

    @Transactional(readOnly = true)
    public Optional<CheckoutLink> checkoutForTransaction(String transactionReference) {
        return sessions.findByTransactionReference(transactionReference).map(s -> new CheckoutLink(
                s.getId(), s.getMerchantId(), s.getReference(), s.getAmountCents(), s.getCurrency(),
                s.getPaymentMethodCode(), s.getStatus()));
    }

    @Transactional(readOnly = true)
    public Optional<CheckoutLink> checkoutByMerchantReference(long merchantId, String reference) {
        return sessions.findByMerchantIdAndReference(merchantId, reference).map(s -> new CheckoutLink(
                s.getId(), s.getMerchantId(), s.getReference(), s.getAmountCents(), s.getCurrency(),
                s.getPaymentMethodCode(), s.getStatus()));
    }

    /** Transaction reference behind a checkout session, once payment was attempted. */
    @Transactional(readOnly = true)
    public Optional<String> transactionReferenceOfSession(long sessionId) {
        return sessions.findById(sessionId).map(CheckoutSession::getTransactionReference);
    }

    @Transactional(readOnly = true)
    public Optional<CheckoutLink> checkoutSession(long sessionId) {
        return sessions.findById(sessionId).map(s -> new CheckoutLink(
                s.getId(), s.getMerchantId(), s.getReference(), s.getAmountCents(), s.getCurrency(),
                s.getPaymentMethodCode(), s.getStatus()));
    }

    private static MerchantSnapshot snap(Merchant m) {
        return new MerchantSnapshot(m.getId(), m.getBusinessName(), m.getStatus(), m.getContactEmail(), m.getContactPhone(),
                m.getDefaultCurrency(), m.getCommissionRateBps(), m.getRiskScore() == null ? 0 : m.getRiskScore(),
                m.getRiskFlags(), m.getPayoutAccountType(), m.getPayoutAccountDetails(), m.getBusinessCategory(),
                m.getRegistrationNumber());
    }

    private static TransactionSnapshot snap(Transaction t) {
        return new TransactionSnapshot(t.getReference(), t.getStatus(),
                t.getChannel() != null ? t.getChannel().name() : null,
                t.getProductType() != null ? t.getProductType().getCode() : null,
                t.getProductName(),
                t.getProductType() != null ? t.getProductType().getProviderCode() : t.getFulfilmentProviderCode(),
                t.getPaymentProviderCode(),
                t.getPaymentMethod() != null ? t.getPaymentMethod().getCode() : null,
                t.getAmountCents() == null ? 0 : t.getAmountCents(), t.getCurrency(), t.getCustomerPhone(),
                t.getRecipientIdentifier(), t.getProviderReference(),
                t.getUser() != null ? t.getUser().getId() : null,
                t.getCreatedAt(), t.getPaymentConfirmedAt(), t.getCompletedAt(), t.getRefundMethod(),
                t.getIpAddress(), t.getDeviceType());
    }
}
