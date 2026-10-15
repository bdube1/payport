package zw.co.payport.gateway.psp.fees;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.platform.approval.ApprovalHandler;

import java.time.LocalDateTime;

/** Executes approved fee-agreement and tax-rate changes. */
public final class FeeApprovalHandlers {
    private FeeApprovalHandlers() {}

    @Component
    public static class ActivateAgreement implements ApprovalHandler {
        private final FeeAgreementRepository agreements;
        private final ObjectMapper om;

        public ActivateAgreement(FeeAgreementRepository agreements, ObjectMapper om) {
            this.agreements = agreements;
            this.om = om;
        }

        @Override public String actionType() { return FeeService.APPROVE_AGREEMENT; }
        @Override public String requiredRole() { return "FINANCE"; }

        @Override
        public String execute(String targetRef, String payloadJson, String requestedBy, String approvedBy) {
            FeeAgreement f = agreements.findByAgreementRef(targetRef).orElseThrow(() -> PspException.notFound("Fee agreement", targetRef));
            if (!"PENDING_APPROVAL".equals(f.getStatus())) throw PspException.conflict("NOT_PENDING", "Agreement is " + f.getStatus());
            LocalDateTime start = f.getEffectiveFrom();
            // Supersede the agreement this one replaces (same merchant / method / currency).
            var existing = f.getMerchantId() == null
                    ? agreements.findByMerchantIdIsNullAndCurrencyAndStatus(f.getCurrency(), "ACTIVE")
                    : agreements.findByMerchantIdAndCurrencyAndStatus(f.getMerchantId(), f.getCurrency(), "ACTIVE");
            int superseded = 0;
            for (FeeAgreement old : existing) {
                boolean sameMethod = old.getPaymentMethodCode() == null ? f.getPaymentMethodCode() == null
                        : old.getPaymentMethodCode().equalsIgnoreCase(String.valueOf(f.getPaymentMethodCode()));
                // Old terms stay ACTIVE until the new ones start, so there is never a gap.
                if (sameMethod && (old.getEffectiveTo() == null || old.getEffectiveTo().isAfter(start))) {
                    old.setEffectiveTo(start);
                    if (!start.isAfter(LocalDateTime.now())) old.setStatus("SUPERSEDED");
                    agreements.save(old);
                    superseded++;
                }
            }
            f.setStatus("ACTIVE");
            f.setApprovedBy(approvedBy);
            agreements.save(f);
            return "Activated " + f.getAgreementRef() + (superseded > 0 ? ", superseded " + superseded : "");
        }
    }

    @Component
    public static class ActivateTaxRate implements ApprovalHandler {
        private final TaxRateRepository taxRates;
        private final ObjectMapper om;

        public ActivateTaxRate(TaxRateRepository taxRates, ObjectMapper om) {
            this.taxRates = taxRates;
            this.om = om;
        }

        @Override public String actionType() { return FeeService.APPROVE_TAX_RATE; }
        @Override public String requiredRole() { return "FINANCE"; }

        @Override
        public String execute(String targetRef, String payloadJson, String requestedBy, String approvedBy) {
            long id;
            try {
                JsonNode n = om.readTree(payloadJson);
                id = n.path("taxRateId").asLong();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            TaxRate t = taxRates.findById(id).orElseThrow(() -> PspException.notFound("Tax rate", id));
            if (!"PENDING_APPROVAL".equals(t.getStatus())) throw PspException.conflict("NOT_PENDING", "Tax rate is " + t.getStatus());
            for (TaxRate old : taxRates.findByTaxCodeAndCurrencyAndStatus(t.getTaxCode(), t.getCurrency(), "ACTIVE")) {
                if (old.getEffectiveTo() == null || old.getEffectiveTo().isAfter(t.getEffectiveFrom())) {
                    old.setEffectiveTo(t.getEffectiveFrom());
                    if (!t.getEffectiveFrom().isAfter(LocalDateTime.now())) old.setStatus("SUPERSEDED");
                    taxRates.save(old);
                }
            }
            t.setStatus("ACTIVE");
            t.setApprovedBy(approvedBy);
            taxRates.save(t);
            return "Activated " + t.getTaxCode() + " " + t.getCurrency() + " at " + t.getRateBps() + " bps";
        }
    }
}
