package zw.co.payport.gateway.psp.fees;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.common.Refs;
import zw.co.payport.gateway.psp.ledger.domain.AccountKey;
import zw.co.payport.gateway.psp.ledger.domain.FeeCalculator;
import zw.co.payport.gateway.psp.ledger.domain.FeeQuote;
import zw.co.payport.gateway.psp.ledger.domain.FeeSchedule;
import zw.co.payport.gateway.psp.legacy.LegacyGateway;
import zw.co.payport.gateway.psp.platform.approval.ApprovalRequest;
import zw.co.payport.gateway.psp.platform.approval.ApprovalService;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fees, tax and IMTT.
 *
 * Resolution order for a collection (first match wins):
 *   1. merchant agreement for this payment method
 *   2. merchant agreement for any method
 *   3. platform default for this payment method
 *   4. platform default for any method
 *   5. the gateway's legacy commission_rate_bps (so nothing changes for a merchant until finance sets terms)
 *
 * Taxes come from psp_tax_rates (VAT on the fee, merchant-borne IMTT if
 * configured). Agreements and tax rates only take effect after a second
 * person approves them.
 */
@Service
public class FeeService {

    public static final String APPROVE_AGREEMENT = "FEE_AGREEMENT_ACTIVATE";
    public static final String APPROVE_TAX_RATE = "TAX_RATE_ACTIVATE";

    public record AgreementRequest(Long merchantId, String paymentMethodCode, String currency, int percentBps,
                                   long fixedMinor, long minFeeMinor, long maxFeeMinor,
                                   LocalDateTime effectiveFrom, String notes) {}

    public record TaxRateRequest(String taxCode, String currency, int rateBps, long capMinor, long thresholdMinor,
                                 boolean merchantBorne, LocalDateTime effectiveFrom, String notes) {}

    private final FeeAgreementRepository agreements;
    private final TaxRateRepository taxRates;
    private final LegacyGateway legacy;
    private final ApprovalService approvals;
    private final ObjectMapper objectMapper;

    public FeeService(FeeAgreementRepository agreements, TaxRateRepository taxRates, LegacyGateway legacy,
                      ApprovalService approvals, ObjectMapper objectMapper) {
        this.agreements = agreements;
        this.taxRates = taxRates;
        this.legacy = legacy;
        this.approvals = approvals;
        this.objectMapper = objectMapper;
    }

    // ── Quoting ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public FeeSchedule scheduleFor(long merchantId, String paymentMethodCode, String currency) {
        String ccy = AccountKey.normaliseCurrency(currency);
        LocalDateTime now = LocalDateTime.now();
        Optional<FeeAgreement> a = pick(agreements.findByMerchantIdAndCurrencyAndStatus(merchantId, ccy, "ACTIVE"), paymentMethodCode, now)
                .or(() -> pick(agreements.findByMerchantIdIsNullAndCurrencyAndStatus(ccy, "ACTIVE"), paymentMethodCode, now));

        int vatBps = 0;
        Optional<TaxRate> vat = activeTax(PostingTaxCodes.VAT, ccy, now);
        if (vat.isPresent()) vatBps = vat.get().getRateBps();

        int imttBps = 0;
        long imttCap = 0;
        Optional<TaxRate> imtt = activeTax(PostingTaxCodes.IMTT, ccy, now).filter(TaxRate::isMerchantBorne);
        if (imtt.isPresent()) {
            imttBps = imtt.get().getRateBps();
            imttCap = imtt.get().getCapMinor();
        }

        if (a.isPresent()) {
            FeeAgreement f = a.get();
            return new FeeSchedule(f.getPercentBps(), f.getFixedMinor(), f.getMinFeeMinor(), f.getMaxFeeMinor(),
                    vatBps, imttBps, imttCap, f.getAgreementRef());
        }
        return new FeeSchedule(legacy.legacyCommissionBps(merchantId), 0, 0, 0, vatBps, imttBps, imttCap, "LEGACY_COMMISSION");
    }

    @Transactional(readOnly = true)
    public FeeQuote quote(long merchantId, String paymentMethodCode, String currency, long grossMinor) {
        FeeSchedule s = scheduleFor(merchantId, paymentMethodCode, currency);
        FeeQuote q = FeeCalculator.quote(s, grossMinor);
        // IMTT exemption threshold: below it, no transfer tax.
        Optional<TaxRate> imtt = activeTax(PostingTaxCodes.IMTT, AccountKey.normaliseCurrency(currency), LocalDateTime.now());
        if (q.transferTaxMinor() > 0 && imtt.isPresent() && grossMinor <= imtt.get().getThresholdMinor()) {
            q = new FeeQuote(q.grossMinor(), q.feeMinor(), q.vatOnFeeMinor(), 0,
                    q.netToMerchantMinor() + q.transferTaxMinor(), q.agreementRef());
        }
        return q;
    }

    private Optional<FeeAgreement> pick(List<FeeAgreement> list, String method, LocalDateTime now) {
        List<FeeAgreement> live = list.stream()
                .filter(f -> !f.getEffectiveFrom().isAfter(now))
                .filter(f -> f.getEffectiveTo() == null || f.getEffectiveTo().isAfter(now))
                .sorted(Comparator.comparing(FeeAgreement::getEffectiveFrom).reversed())
                .toList();
        return live.stream().filter(f -> method != null && method.equalsIgnoreCase(f.getPaymentMethodCode())).findFirst()
                .or(() -> live.stream().filter(f -> f.getPaymentMethodCode() == null).findFirst());
    }

    public Optional<TaxRate> activeTax(String code, String currency, LocalDateTime at) {
        return taxRates.findByTaxCodeAndCurrencyAndStatus(code, currency, "ACTIVE").stream()
                .filter(t -> !t.getEffectiveFrom().isAfter(at))
                .filter(t -> t.getEffectiveTo() == null || t.getEffectiveTo().isAfter(at))
                .max(Comparator.comparing(TaxRate::getEffectiveFrom));
    }

    // ── Proposing changes (four-eyes) ─────────────────────────────────────

    @Transactional
    public ApprovalRequest proposeAgreement(AgreementRequest r, Actor actor) {
        if (r.percentBps() < 0 || r.percentBps() > 10_000) throw PspException.badRequest("INVALID_FEE", "percentBps must be 0..10000");
        if (r.fixedMinor() < 0 || r.minFeeMinor() < 0 || r.maxFeeMinor() < 0) throw PspException.badRequest("INVALID_FEE", "Fee amounts must be >= 0");
        if (r.maxFeeMinor() > 0 && r.minFeeMinor() > r.maxFeeMinor()) throw PspException.badRequest("INVALID_FEE", "minFee exceeds maxFee");
        if (r.merchantId() != null && legacy.merchant(r.merchantId()).isEmpty()) throw PspException.notFound("Merchant", r.merchantId());

        FeeAgreement f = new FeeAgreement();
        f.setAgreementRef(Refs.newRef("fee"));
        f.setMerchantId(r.merchantId());
        f.setPaymentMethodCode(r.paymentMethodCode() == null || r.paymentMethodCode().isBlank() ? null : r.paymentMethodCode().toUpperCase());
        f.setCurrency(AccountKey.normaliseCurrency(r.currency()));
        f.setPercentBps(r.percentBps());
        f.setFixedMinor(r.fixedMinor());
        f.setMinFeeMinor(r.minFeeMinor());
        f.setMaxFeeMinor(r.maxFeeMinor());
        f.setEffectiveFrom(r.effectiveFrom() != null ? r.effectiveFrom() : LocalDateTime.now());
        f.setStatus("PENDING_APPROVAL");
        f.setCreatedBy(actor.id());
        f.setNotes(r.notes());
        agreements.save(f);

        String who = r.merchantId() == null ? "platform default" : "merchant " + r.merchantId();
        String summary = String.format("Fee agreement for %s, %s %s: %d bps + %d fixed (min %d, max %d)", who,
                f.getCurrency(), f.getPaymentMethodCode() == null ? "all methods" : f.getPaymentMethodCode(),
                f.getPercentBps(), f.getFixedMinor(), f.getMinFeeMinor(), f.getMaxFeeMinor());
        return approvals.request(APPROVE_AGREEMENT, f.getAgreementRef(), json(Map.of("agreementRef", f.getAgreementRef())),
                summary, r.notes(), actor);
    }

    @Transactional
    public ApprovalRequest proposeTaxRate(TaxRateRequest r, Actor actor) {
        String code = r.taxCode() == null ? "" : r.taxCode().toUpperCase();
        if (!PostingTaxCodes.ALL.contains(code)) throw PspException.badRequest("INVALID_TAX_CODE", "Tax code must be one of " + PostingTaxCodes.ALL);
        if (r.rateBps() < 0 || r.rateBps() > 10_000) throw PspException.badRequest("INVALID_TAX_RATE", "rateBps must be 0..10000");
        TaxRate t = new TaxRate();
        t.setTaxCode(code);
        t.setCurrency(AccountKey.normaliseCurrency(r.currency()));
        t.setRateBps(r.rateBps());
        t.setCapMinor(Math.max(0, r.capMinor()));
        t.setThresholdMinor(Math.max(0, r.thresholdMinor()));
        t.setMerchantBorne(r.merchantBorne());
        t.setEffectiveFrom(r.effectiveFrom() != null ? r.effectiveFrom() : LocalDateTime.now());
        t.setStatus("PENDING_APPROVAL");
        t.setCreatedBy(actor.id());
        t.setNotes(r.notes());
        taxRates.save(t);
        String summary = String.format("%s %s at %d bps (cap %d, threshold %d, merchant-borne %s) from %s",
                code, t.getCurrency(), t.getRateBps(), t.getCapMinor(), t.getThresholdMinor(), t.isMerchantBorne(), t.getEffectiveFrom());
        return approvals.request(APPROVE_TAX_RATE, "tax:" + t.getId(), json(Map.of("taxRateId", t.getId())), summary, r.notes(), actor);
    }

    public List<FeeAgreement> agreementsFor(Long merchantId) {
        return merchantId == null ? agreements.findByMerchantIdIsNullOrderByCreatedAtDesc() : agreements.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    public List<TaxRate> taxRates() {
        return taxRates.findAllByOrderByCreatedAtDesc();
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
