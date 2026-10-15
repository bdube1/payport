package zw.co.payport.gateway.psp.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.payport.gateway.psp.ledger.domain.*;

import java.time.LocalDateTime;
import java.util.List;

import static zw.co.payport.gateway.psp.ledger.domain.AccountCategory.*;

/**
 * Merchant money lifecycle on top of the ledger:
 *
 *   collected -> PENDING --(T+N hold)--> AVAILABLE (+ RESERVE) --(settlement)--> paid out
 *
 * Hold length and reserve come from the merchant's risk profile (passed in as
 * {@link FundsPolicy}) so a high-risk merchant can be held longer and reserved
 * more without touching code.
 */
@Service
public class MerchantFundsService {

    private static final Logger log = LoggerFactory.getLogger(MerchantFundsService.class);

    /** @param holdDays days in PENDING before release; @param reserveBps share withheld; @param reserveDays how long the reserve is kept */
    public record FundsPolicy(int holdDays, int reserveBps, int reserveDays) {
        public static final FundsPolicy DEFAULT = new FundsPolicy(1, 0, 0);
    }

    public record MerchantBalances(long merchantId, String currency, long pendingMinor, long availableMinor,
                                   long reserveMinor, long inSettlementMinor) {
        public long totalOwedMinor() { return pendingMinor + availableMinor + reserveMinor + inSettlementMinor; }
    }

    private final LedgerService ledger;
    private final FundHoldRepository holds;
    private final TransactionTemplate tx;

    public MerchantFundsService(LedgerService ledger, FundHoldRepository holds, PlatformTransactionManager txManager) {
        this.ledger = ledger;
        this.holds = holds;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Posts the collection and opens its T+N hold. Idempotent on paymentRef. */
    @Transactional
    public LedgerService.PostedEntry recordCollection(String paymentRef, long merchantId, String providerCode,
                                                      String currency, FeeQuote quote, FundsPolicy policy, String actor) {
        LedgerService.PostedEntry posted = ledger.post(
                PostingRules.merchantCollection(paymentRef, merchantId, providerCode, currency, quote), actor);
        openHold(posted, paymentRef, merchantId, currency, quote, policy);
        return posted;
    }

    /** Same as {@link #recordCollection} but funded from a customer's wallet instead of a rail. */
    @Transactional
    public LedgerService.PostedEntry recordWalletCollection(String paymentRef, long merchantId, String customerRef,
                                                            String currency, FeeQuote quote, FundsPolicy policy, String actor) {
        LedgerService.PostedEntry posted = ledger.post(
                WalletPostingRules.merchantPayment(paymentRef, customerRef, merchantId, currency, quote), actor);
        openHold(posted, paymentRef, merchantId, currency, quote, policy);
        return posted;
    }

    private void openHold(LedgerService.PostedEntry posted, String paymentRef, long merchantId, String currency,
                          FeeQuote quote, FundsPolicy policy) {
        if (posted.replayed() || quote.netToMerchantMinor() <= 0 || holds.findByHoldRef(paymentRef).isPresent()) return;
        FundHold h = new FundHold();
        h.setHoldRef(paymentRef);
        h.setMerchantId(merchantId);
        h.setCurrency(AccountKey.normaliseCurrency(currency));
        h.setAmountMinor(quote.netToMerchantMinor());
        h.setReserveBps(policy.reserveBps());
        h.setReserveDays(Math.max(0, policy.reserveDays()));
        h.setReleaseAt(LocalDateTime.now().plusDays(Math.max(0, policy.holdDays())));
        h.setStatus("HELD");
        h.setReserveStatus("NONE");
        holds.save(h);
    }

    /**
     * Funds a merchant-initiated refund: from AVAILABLE first, then PENDING.
     * Throws InsufficientFundsException if the merchant cannot cover it.
     */
    @Transactional
    public LedgerService.PostedEntry fundRefund(String refundRef, long merchantId, String currency, long amountMinor, String actor) {
        long available = Math.max(0, ledger.balance(AccountKey.merchant(MERCHANT_AVAILABLE, merchantId, currency)));
        long fromAvailable = Math.min(available, amountMinor);
        long fromPending = amountMinor - fromAvailable;
        return ledger.post(PostingRules.merchantRefund(refundRef, merchantId, currency, fromAvailable, fromPending), actor);
    }

    @Transactional(readOnly = true)
    public MerchantBalances balances(long merchantId, String currency) {
        return new MerchantBalances(merchantId, AccountKey.normaliseCurrency(currency),
                ledger.balance(AccountKey.merchant(MERCHANT_PENDING, merchantId, currency)),
                ledger.balance(AccountKey.merchant(MERCHANT_AVAILABLE, merchantId, currency)),
                ledger.balance(AccountKey.merchant(MERCHANT_RESERVE, merchantId, currency)),
                ledger.balance(AccountKey.merchant(SETTLEMENT_PAYABLE, merchantId, currency)));
    }

    public List<FundHold> openHolds(long merchantId) {
        return holds.findByMerchantIdAndStatusOrderByReleaseAtAsc(merchantId, "HELD");
    }

    // ── Scheduled releases ────────────────────────────────────────────────

    @Scheduled(fixedDelayString = "${payport.psp.funds.release-poll-ms:300000}", initialDelay = 60_000)
    public void releaseDue() {
        List<FundHold> due = holds.findByStatusAndReleaseAtBeforeOrderByReleaseAtAsc("HELD", LocalDateTime.now(), PageRequest.of(0, 500));
        for (FundHold h : due) {
            try {
                tx.executeWithoutResult(s -> releaseOne(h.getId()));
            } catch (Exception e) {
                log.error("[FUNDS] Release of hold {} failed: {}", h.getHoldRef(), e.getMessage());
            }
        }
        List<FundHold> reserves = holds.findByReserveStatusAndReserveReleaseAtBeforeOrderByReserveReleaseAtAsc("HELD", LocalDateTime.now(), PageRequest.of(0, 500));
        for (FundHold h : reserves) {
            try {
                tx.executeWithoutResult(s -> releaseReserveOne(h.getId()));
            } catch (Exception e) {
                log.error("[FUNDS] Reserve release of hold {} failed: {}", h.getHoldRef(), e.getMessage());
            }
        }
    }

    /** Ops can stop a release (e.g. fraud suspicion) by moving the hold to FROZEN; releases skip it. */
    @Transactional
    public FundHold freeze(String holdRef, boolean frozen) {
        FundHold h = holds.findByHoldRef(holdRef).orElseThrow();
        if (frozen && "HELD".equals(h.getStatus())) h.setStatus("FROZEN");
        else if (!frozen && "FROZEN".equals(h.getStatus())) h.setStatus("HELD");
        return holds.save(h);
    }

    private void releaseOne(Long holdId) {
        FundHold h = holds.findById(holdId).orElseThrow();
        if (!"HELD".equals(h.getStatus())) return;
        long pending = ledger.balance(AccountKey.merchant(MERCHANT_PENDING, h.getMerchantId(), h.getCurrency()));
        long amount = Math.min(h.getAmountMinor(), Math.max(0, pending));
        long reserve = 0;
        if (amount > 0) {
            PostingRules.Release r = PostingRules.releaseFunds(h.getHoldRef(), h.getMerchantId(), h.getCurrency(), amount, h.getReserveBps());
            ledger.post(r.draft(), "SYSTEM:funds-release");
            reserve = r.toReserveMinor();
        }
        h.setStatus("RELEASED");
        h.setReleasedAt(LocalDateTime.now());
        h.setReleasedAmountMinor(amount);
        h.setReserveMinor(reserve);
        if (reserve > 0) {
            h.setReserveStatus("HELD");
            h.setReserveReleaseAt(LocalDateTime.now().plusDays(h.getReserveDays()));
        }
        holds.save(h);
    }

    private void releaseReserveOne(Long holdId) {
        FundHold h = holds.findById(holdId).orElseThrow();
        if (!"HELD".equals(h.getReserveStatus()) || h.getReserveMinor() == null) return;
        long reserveBal = ledger.balance(AccountKey.merchant(MERCHANT_RESERVE, h.getMerchantId(), h.getCurrency()));
        long amount = Math.min(h.getReserveMinor(), Math.max(0, reserveBal));
        if (amount > 0) {
            ledger.post(PostingRules.releaseReserve(h.getHoldRef(), h.getMerchantId(), h.getCurrency(), amount), "SYSTEM:reserve-release");
        }
        h.setReserveStatus("RELEASED");
        h.setReserveReleasedAt(LocalDateTime.now());
        holds.save(h);
    }
}
