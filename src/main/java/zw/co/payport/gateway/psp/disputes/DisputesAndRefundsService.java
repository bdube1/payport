package zw.co.payport.gateway.psp.disputes;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.BreakReporter;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.common.Refs;
import zw.co.payport.gateway.psp.integration.PayoutRail;
import zw.co.payport.gateway.psp.integration.PayoutRails;
import zw.co.payport.gateway.psp.ledger.LedgerService;
import zw.co.payport.gateway.psp.ledger.MerchantFundsService;
import zw.co.payport.gateway.psp.ledger.domain.AccountCategory;
import zw.co.payport.gateway.psp.ledger.domain.AccountKey;
import zw.co.payport.gateway.psp.ledger.domain.PostingRules;
import zw.co.payport.gateway.psp.ledger.domain.WalletPostingRules;
import zw.co.payport.gateway.psp.legacy.LegacyGateway;
import zw.co.payport.gateway.psp.platform.audit.AuditService;
import zw.co.payport.gateway.psp.platform.outbox.EventTypes;
import zw.co.payport.gateway.psp.platform.outbox.OutboxService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Disputes and refunds, with their money flows.
 *
 * Merchant refund (gateway RefundService) -> {@link #fundMerchantRefund}: debits the
 *   merchant (AVAILABLE then PENDING; refused if they cannot cover it) and creates a
 *   customer refund liability.
 * Dispute opened -> {@link #onDisputeOpened}: holds the disputed amount in the merchant's
 *   RESERVE so it cannot be paid out while the dispute runs; any shortfall is recorded.
 * Dispute decided -> {@link #onDisputeResolved}: WON (merchant) releases the hold;
 *   LOST becomes a chargeback, a customer refund liability, with any shortfall absorbed
 *   by PayPort as a dispute loss.
 * Customer refunds are only closed when actually paid ({@link #payRefund}), via a rail
 *   or manually with a payout reference.
 */
@Service
public class DisputesAndRefundsService {

    private final DisputeCaseRepository disputes;
    private final CustomerRefundRepository refunds;
    private final LedgerService ledger;
    private final MerchantFundsService funds;
    private final LegacyGateway legacy;
    private final PayoutRails rails;
    private final AuditService audit;
    private final OutboxService outbox;
    private final BreakReporter breaks;

    @Value("${payport.psp.disputes.response-days:7}")
    private int responseDays = 7;

    public DisputesAndRefundsService(DisputeCaseRepository disputes, CustomerRefundRepository refunds, LedgerService ledger,
                                     MerchantFundsService funds, LegacyGateway legacy, PayoutRails rails, AuditService audit,
                                     OutboxService outbox, BreakReporter breaks) {
        this.disputes = disputes;
        this.refunds = refunds;
        this.ledger = ledger;
        this.funds = funds;
        this.legacy = legacy;
        this.rails = rails;
        this.audit = audit;
        this.outbox = outbox;
        this.breaks = breaks;
    }

    // ── Merchant refunds ─────────────────────────────────────────────────

    /**
     * Called by the gateway's RefundService before it records a refund.
     * Throws InsufficientFundsException (HTTP 422) if the merchant cannot cover it.
     * @return the PSP refund reference to store on the gateway refund row
     */
    @Transactional
    public String fundMerchantRefund(long checkoutSessionId, long amountMinor, String customerMsisdn, String initiatedBy) {
        LegacyGateway.CheckoutLink s = legacy.checkoutSession(checkoutSessionId)
                .orElseThrow(() -> PspException.notFound("Checkout session", checkoutSessionId));
        String ref = Refs.newRef("crf");
        LedgerService.PostedEntry e = funds.fundRefund(ref, s.merchantId(), s.currency(), amountMinor, initiatedBy);
        CustomerRefund r = new CustomerRefund();
        r.setRefundRef(ref);
        r.setSourceType("MERCHANT_REFUND");
        r.setSourceRef(ref);
        r.setMerchantId(s.merchantId());
        r.setCurrency(AccountKey.normaliseCurrency(s.currency()));
        r.setAmountMinor(amountMinor);
        r.setCustomerMsisdn(customerMsisdn);
        r.setStatus("PAYABLE");
        r.setFundingEntryRef(e.entryRef());
        refunds.save(r);
        return ref;
    }

    // ── Disputes ─────────────────────────────────────────────────────────

    @Transactional
    public DisputeCase onDisputeOpened(long disputeId, long checkoutSessionId, long amountMinor, String actor) {
        if (disputes.findByDisputeId(disputeId).isPresent()) return disputes.findByDisputeId(disputeId).get();
        LegacyGateway.CheckoutLink s = legacy.checkoutSession(checkoutSessionId)
                .orElseThrow(() -> PspException.notFound("Checkout session", checkoutSessionId));
        String ccy = AccountKey.normaliseCurrency(s.currency());
        long available = Math.max(0, ledger.balance(AccountKey.merchant(AccountCategory.MERCHANT_AVAILABLE, s.merchantId(), ccy)));
        long pending = Math.max(0, ledger.balance(AccountKey.merchant(AccountCategory.MERCHANT_PENDING, s.merchantId(), ccy)));
        long fromAvailable = Math.min(available, amountMinor);
        long fromPending = Math.min(pending, amountMinor - fromAvailable);
        long held = fromAvailable + fromPending;

        DisputeCase d = new DisputeCase();
        d.setDisputeId(disputeId);
        d.setMerchantId(s.merchantId());
        d.setCheckoutSessionId(checkoutSessionId);
        d.setCurrency(ccy);
        d.setDisputedMinor(amountMinor);
        d.setHeldMinor(held);
        d.setShortfallMinor(amountMinor - held);
        d.setStatus("OPEN");
        d.setRespondBy(LocalDateTime.now().plusDays(responseDays));
        if (held > 0) {
            d.setHoldEntryRef(ledger.post(WalletPostingRules.disputeHold("D" + disputeId, s.merchantId(), ccy, fromAvailable, fromPending), actor).entryRef());
        }
        disputes.save(d);
        if (d.getShortfallMinor() > 0) {
            breaks.report("DISPUTE", "DISPUTE_UNDERFUNDED", "dispute:" + disputeId, ccy, amountMinor, held,
                    "Merchant " + s.merchantId() + " could not cover the full disputed amount; PayPort is exposed for " + d.getShortfallMinor());
        }
        outbox.publish(EventTypes.DISPUTE_OPENED, "DISPUTE", String.valueOf(disputeId), Map.of("disputeId", disputeId,
                "merchantId", s.merchantId(), "amountMinor", amountMinor, "currency", ccy,
                "respondBy", d.getRespondBy().toString()));
        return d;
    }

    /** @param outcome WON = merchant won (funds released), LOST = customer won (chargeback) */
    @Transactional
    public DisputeCase onDisputeResolved(long disputeId, String outcome, String actor, String customerMsisdn) {
        DisputeCase d = disputes.findByDisputeId(disputeId).orElseThrow(() -> PspException.notFound("Dispute case", disputeId));
        if (!"OPEN".equals(d.getStatus())) throw PspException.conflict("DISPUTE_CLOSED", "Dispute already " + d.getStatus());
        String key = "D" + disputeId;
        if ("WON".equals(outcome)) {
            if (d.getHeldMinor() > 0) {
                d.setOutcomeEntryRef(ledger.post(WalletPostingRules.disputeRelease(key, d.getMerchantId(), d.getCurrency(), d.getHeldMinor()), actor).entryRef());
            }
            d.setStatus("RELEASED");
        } else if ("LOST".equals(outcome)) {
            d.setOutcomeEntryRef(ledger.post(WalletPostingRules.chargeback(key, d.getMerchantId(), d.getCurrency(), d.getHeldMinor(),
                    d.getShortfallMinor()), actor).entryRef());
            d.setStatus("CHARGED_BACK");
            CustomerRefund r = new CustomerRefund();
            r.setRefundRef(Refs.newRef("crf"));
            r.setSourceType("CHARGEBACK");
            r.setSourceRef("dispute:" + disputeId);
            r.setMerchantId(d.getMerchantId());
            r.setCurrency(d.getCurrency());
            r.setAmountMinor(d.getDisputedMinor());
            r.setCustomerMsisdn(customerMsisdn);
            r.setStatus("PAYABLE");
            r.setFundingEntryRef(d.getOutcomeEntryRef());
            refunds.save(r);
        } else {
            throw PspException.badRequest("INVALID_OUTCOME", "WON or LOST");
        }
        d.setOutcome(outcome);
        d.setDecidedBy(actor);
        disputes.save(d);
        outbox.publish(EventTypes.DISPUTE_RESOLVED, "DISPUTE", String.valueOf(disputeId), Map.of("disputeId", disputeId,
                "outcome", outcome, "merchantId", d.getMerchantId()));
        return d;
    }

    // ── Paying customers back ────────────────────────────────────────────

    /**
     * Pays an outstanding customer refund.
     * @param railCode a payout rail (e.g. ECOCASH_B2C, SANDBOX) or MANUAL with payoutReference
     */
    @Transactional
    public CustomerRefund payRefund(String refundRef, String railCode, String cashAccount, String payoutReference, Actor actor) {
        CustomerRefund r = refunds.findByRefundRef(refundRef).orElseThrow(() -> PspException.notFound("Refund", refundRef));
        if (!"PAYABLE".equals(r.getStatus()) && !"FAILED".equals(r.getStatus())) throw PspException.conflict("NOT_PAYABLE", "Refund is " + r.getStatus());
        if (cashAccount == null || cashAccount.isBlank()) throw PspException.badRequest("CASH_ACCOUNT_REQUIRED", "Which account paid the customer?");
        String reference = payoutReference;
        if (!"MANUAL".equalsIgnoreCase(railCode)) {
            if (r.getCustomerMsisdn() == null) throw PspException.unprocessable("NO_DESTINATION", "No customer number on file; pay manually");
            PayoutRail.PayoutResult res = rails.require(railCode).submit(new PayoutRail.PayoutRequest(r.getRefundRef(), r.getCurrency(),
                    r.getAmountMinor(), "Customer refund", null, null, null, null, r.getCustomerMsisdn(), "PayPort refund " + r.getRefundRef()));
            if ("REJECTED".equals(res.status())) {
                r.setStatus("FAILED");
                r.setFailureReason(res.message());
                refunds.save(r);
                return r;
            }
            reference = res.railReference() != null ? res.railReference() : res.status();
            if ("UNKNOWN".equals(res.status())) {
                breaks.report("REFUND", "REFUND_PAYOUT_UNCONFIRMED", refundRef, r.getCurrency(), r.getAmountMinor(), null,
                        "Rail " + railCode + " gave no definitive answer; confirm before re-paying");
            }
        } else if (reference == null || reference.isBlank()) {
            throw PspException.badRequest("REFERENCE_REQUIRED", "Manual refunds need the payout reference");
        }
        LedgerService.PostedEntry e = ledger.post(PostingRules.refundPayout(r.getRefundRef(), r.getCurrency(), r.getAmountMinor(),
                cashAccount.toUpperCase()), actor.id());
        r.setStatus("PAID");
        r.setPayoutEntryRef(e.entryRef());
        r.setPayoutMethod(railCode.toUpperCase());
        r.setPayoutReference(reference);
        r.setPaidBy(actor.id());
        r.setPaidAt(LocalDateTime.now());
        refunds.save(r);
        audit.record(actor, "CUSTOMER_REFUND_PAID", "CUSTOMER_REFUND", refundRef, Map.of("rail", railCode, "reference", String.valueOf(reference)));
        return r;
    }

    public List<CustomerRefund> outstandingRefunds() {
        List<CustomerRefund> l = new java.util.ArrayList<>(refunds.findByStatusOrderByCreatedAtAsc("PAYABLE"));
        l.addAll(refunds.findByStatusOrderByCreatedAtAsc("FAILED"));
        return l;
    }

    public List<DisputeCase> overdueDisputes() {
        return disputes.findByStatusAndRespondByBefore("OPEN", LocalDateTime.now());
    }

    public List<DisputeCase> merchantDisputes(long merchantId) {
        return disputes.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    public List<CustomerRefund> merchantRefunds(long merchantId) {
        return refunds.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }
}
