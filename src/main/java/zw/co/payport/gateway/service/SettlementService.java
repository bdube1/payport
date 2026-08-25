package zw.co.payport.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.entity.CheckoutSession;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.entity.Settlement;
import zw.co.payport.gateway.repository.CheckoutSessionRepository;
import zw.co.payport.gateway.repository.MerchantRepository;
import zw.co.payport.gateway.repository.SettlementRepository;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final CheckoutSessionRepository checkoutSessionRepository;
    private final MerchantRepository merchantRepository;
    private final RefundService refundService;

    public List<Settlement> runForAllMerchants(LocalDateTime periodStart, LocalDateTime periodEnd) {
        List<Settlement> created = new java.util.ArrayList<>();
        for (Merchant merchant : merchantRepository.findAll()) {
            if (!"ACTIVE".equals(merchant.getStatus())) continue;
            for (String currency : currenciesWithUnsettledActivity(merchant.getId())) {
                Settlement s = runForMerchant(merchant.getId(), currency, periodStart, periodEnd);
                if (s != null) created.add(s);
            }
        }
        log.info("[SETTLEMENT] Batch run created {} settlement(s) for period {} to {}", created.size(), periodStart, periodEnd);
        return created;
    }

    public Settlement runForMerchant(Long merchantId, String currency, LocalDateTime periodStart, LocalDateTime periodEnd) {
        List<CheckoutSession> eligible = checkoutSessionRepository.findByMerchantIdAndSettlementIdIsNull(merchantId).stream()
                .filter(s -> currency.equals(s.getCurrency()))
                .filter(s -> isSettleableStatus(s.getStatus()))
                .filter(s -> !s.getCreatedAt().isBefore(periodStart) && s.getCreatedAt().isBefore(periodEnd))
                .toList();

        if (eligible.isEmpty()) return null;

        long gross = 0, commission = 0, refunded = 0, net = 0;
        for (CheckoutSession s : eligible) {
            long sessionRefunded = refundService.totalRefunded(s.getId());
            long remainingGross = Math.max(0, s.getAmountCents() - sessionRefunded);
            double ratio = s.getAmountCents() == 0 ? 0 : (double) remainingGross / s.getAmountCents();

            long fullCommission = s.getCommissionAmountCents() != null ? s.getCommissionAmountCents() : 0;
            long fullNet = s.getNetAmountCents() != null ? s.getNetAmountCents() : s.getAmountCents();

            gross += remainingGross;
            commission += Math.round(fullCommission * ratio);
            refunded += sessionRefunded;
            net += Math.round(fullNet * ratio);
        }

        Settlement settlement = Settlement.builder()
                .merchantId(merchantId)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .currency(currency)
                .sessionCount(eligible.size())
                .grossAmountCents(gross)
                .commissionAmountCents(commission)
                .refundAmountCents(refunded)
                .netAmountCents(net)
                // "Automated payout initiation" per Phase 6 -- but still
                // gated. PENDING->PROCESSING (this step) is safe to
                // automate: nothing has moved yet, it just means "ready
                // for ops to action." PROCESSING->PAID (markPaid) stays a
                // deliberate human click, because that step is what
                // actually confirms real money left the business -- the
                // exact distinction the design doc drew from the start.
                .status("PROCESSING")
                .build();
        settlementRepository.save(settlement);

        for (CheckoutSession s : eligible) {
            s.setSettlementId(settlement.getId());
        }
        checkoutSessionRepository.saveAll(eligible);

        log.info("[SETTLEMENT] Created settlement {} for merchant {} ({}): {} sessions, net {} {}",
                settlement.getId(), merchantId, currency, eligible.size(), net, currency);
        return settlement;
    }

    public Settlement markProcessing(Long settlementId) {
        Settlement s = getOrThrow(settlementId);
        if (!"PENDING".equals(s.getStatus())) {
            throw new RuntimeException("Only a PENDING settlement can move to PROCESSING (current: " + s.getStatus() + ")");
        }
        s.setStatus("PROCESSING");
        settlementRepository.save(s);
        return s;
    }

    public Settlement markPaid(Long settlementId, String payoutReference) {
        Settlement s = getOrThrow(settlementId);
        if ("PAID".equals(s.getStatus())) {
            throw new RuntimeException("This settlement is already marked PAID");
        }
        s.setStatus("PAID");
        s.setPayoutReference(payoutReference);
        s.setPaidAt(LocalDateTime.now());
        settlementRepository.save(s);
        log.info("[SETTLEMENT] {} marked PAID, payout reference: {}", settlementId, payoutReference);
        return s;
    }

    public List<Settlement> listForMerchant(Long merchantId) {
        return settlementRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    public List<Settlement> listAll() {
        return settlementRepository.findAllByOrderByCreatedAtDesc();
    }

    private Set<String> currenciesWithUnsettledActivity(Long merchantId) {
        Set<String> currencies = new HashSet<>();
        for (CheckoutSession s : checkoutSessionRepository.findByMerchantIdAndSettlementIdIsNull(merchantId)) {
            if (isSettleableStatus(s.getStatus())) currencies.add(s.getCurrency());
        }
        return currencies;
    }

    private boolean isSettleableStatus(String status) {
        return "PAID".equals(status) || "PARTIALLY_REFUNDED".equals(status) || "REFUNDED".equals(status);
    }

    private Settlement getOrThrow(Long id) {
        return settlementRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Settlement not found: " + id));
    }
}
