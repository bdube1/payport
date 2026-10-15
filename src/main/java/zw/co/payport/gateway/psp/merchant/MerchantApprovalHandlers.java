package zw.co.payport.gateway.psp.merchant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.platform.approval.ApprovalHandler;
import zw.co.payport.gateway.psp.platform.outbox.EventTypes;
import zw.co.payport.gateway.psp.platform.outbox.OutboxService;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Four-eyes executors for merchant changes. They deliberately use the
 * repositories directly (not MerchantControlService) to avoid a dependency
 * cycle with ApprovalService.
 */
public final class MerchantApprovalHandlers {
    private MerchantApprovalHandlers() {}

    static JsonNode parse(ObjectMapper om, String json) {
        try { return om.readTree(json); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static MerchantProfile profile(MerchantProfileRepository profiles, long merchantId) {
        return profiles.findByMerchantId(merchantId).orElseThrow(() -> PspException.notFound("Merchant profile", merchantId));
    }

    @Component
    public static class Reinstate implements ApprovalHandler {
        private final MerchantProfileRepository profiles;
        private final OutboxService outbox;
        private final ObjectMapper om;
        public Reinstate(MerchantProfileRepository profiles, OutboxService outbox, ObjectMapper om) { this.profiles = profiles; this.outbox = outbox; this.om = om; }
        @Override public String actionType() { return MerchantControlService.APPROVE_REINSTATE; }
        @Override public String requiredRole() { return "COMPLIANCE"; }
        @Override public String execute(String targetRef, String payload, String requestedBy, String approvedBy) {
            long id = parse(om, payload).path("merchantId").asLong();
            MerchantProfile p = profile(profiles, id);
            String was = p.getSuspensionScope();
            p.setSuspensionScope("NONE");
            p.setSuspensionReason(null);
            p.setSuspendedBy(null);
            p.setSuspendedAt(null);
            profiles.save(p);
            outbox.publish(EventTypes.MERCHANT_REINSTATED, "MERCHANT", String.valueOf(id), Map.of("merchantId", id, "previousScope", was));
            return "Merchant " + id + " reinstated (was " + was + ")";
        }
    }

    @Component
    public static class Verify implements ApprovalHandler {
        private final MerchantProfileRepository profiles;
        private final ObjectMapper om;
        public Verify(MerchantProfileRepository profiles, ObjectMapper om) { this.profiles = profiles; this.om = om; }
        @Override public String actionType() { return MerchantControlService.APPROVE_VERIFICATION; }
        @Override public String requiredRole() { return "COMPLIANCE"; }
        @Override public String execute(String targetRef, String payload, String requestedBy, String approvedBy) {
            JsonNode n = parse(om, payload);
            MerchantProfile p = profile(profiles, n.path("merchantId").asLong());
            String status = n.path("status").asText();
            p.setVerificationStatus(status);
            if ("VERIFIED".equals(status)) {
                p.setVerifiedBy(approvedBy);
                p.setVerifiedAt(LocalDateTime.now());
                p.setKycReviewDueAt(LocalDateTime.now().plusMonths(RiskPolicy.defaultsFor(p.getRiskClass()).kycReviewMonths()));
            }
            profiles.save(p);
            return "Verification set to " + status;
        }
    }

    @Component
    public static class FundsPolicy implements ApprovalHandler {
        private final MerchantProfileRepository profiles;
        private final ObjectMapper om;
        public FundsPolicy(MerchantProfileRepository profiles, ObjectMapper om) { this.profiles = profiles; this.om = om; }
        @Override public String actionType() { return MerchantControlService.APPROVE_FUNDS_POLICY; }
        @Override public String requiredRole() { return "COMPLIANCE"; }
        @Override public String execute(String targetRef, String payload, String requestedBy, String approvedBy) {
            JsonNode n = parse(om, payload);
            MerchantProfile p = profile(profiles, n.path("merchantId").asLong());
            if (n.hasNonNull("riskClass")) p.setRiskClass(n.path("riskClass").asText());
            p.setHoldDays(n.path("holdDays").asInt());
            p.setReserveBps(n.path("reserveBps").asInt());
            p.setReserveDays(n.path("reserveDays").asInt());
            profiles.save(p);
            return "Funds policy updated: risk=" + p.getRiskClass() + " hold=" + p.getHoldDays() + "d reserve=" + p.getReserveBps() + "bps";
        }
    }

    @Component
    public static class Product implements ApprovalHandler {
        private final MerchantProductRepository products;
        private final ObjectMapper om;
        public Product(MerchantProductRepository products, ObjectMapper om) { this.products = products; this.om = om; }
        @Override public String actionType() { return MerchantControlService.APPROVE_PRODUCT; }
        @Override public String requiredRole() { return "COMPLIANCE"; }
        @Override public String execute(String targetRef, String payload, String requestedBy, String approvedBy) {
            JsonNode n = parse(om, payload);
            long id = n.path("merchantId").asLong();
            String method = n.path("method").asText();
            String ccy = n.path("currency").asText();
            MerchantProduct p = products.findByMerchantIdAndPaymentMethodCodeAndCurrency(id, method, ccy).orElseGet(MerchantProduct::new);
            p.setMerchantId(id);
            p.setPaymentMethodCode(method);
            p.setCurrency(ccy);
            p.setStatus(n.path("enable").asBoolean() ? "ACTIVE" : "DISABLED");
            p.setApprovedBy(approvedBy);
            p.setApprovedAt(LocalDateTime.now());
            products.save(p);
            return method + " " + ccy + " is now " + p.getStatus();
        }
    }

    @Component
    public static class Limit implements ApprovalHandler {
        private final MerchantLimitRepository limits;
        private final ObjectMapper om;
        public Limit(MerchantLimitRepository limits, ObjectMapper om) { this.limits = limits; this.om = om; }
        @Override public String actionType() { return MerchantControlService.APPROVE_LIMIT; }
        @Override public String requiredRole() { return "COMPLIANCE"; }
        @Override public String execute(String targetRef, String payload, String requestedBy, String approvedBy) {
            JsonNode n = parse(om, payload);
            long id = n.path("merchantId").asLong();
            String ccy = n.path("currency").asText();
            String type = n.path("limitType").asText();
            MerchantLimit l = limits.findByMerchantIdAndCurrencyAndLimitType(id, ccy, type).orElseGet(MerchantLimit::new);
            l.setMerchantId(id);
            l.setCurrency(ccy);
            l.setLimitType(type);
            l.setLimitValue(n.path("value").asLong());
            l.setApprovedBy(approvedBy);
            limits.save(l);
            return type + " = " + l.getLimitValue();
        }
    }

    @Component
    public static class PayoutAccountVerify implements ApprovalHandler {
        private final MerchantPayoutAccountRepository accounts;
        private final ObjectMapper om;
        @Value("${payport.psp.merchant.payout-cooling-off-hours:24}")
        private int coolingOffHours = 24;
        public PayoutAccountVerify(MerchantPayoutAccountRepository accounts, ObjectMapper om) { this.accounts = accounts; this.om = om; }
        @Override public String actionType() { return MerchantControlService.APPROVE_PAYOUT_ACCOUNT; }
        @Override public String requiredRole() { return "FINANCE"; }
        @Override public String execute(String targetRef, String payload, String requestedBy, String approvedBy) {
            MerchantPayoutAccount a = accounts.findByPayoutRef(targetRef).orElseThrow(() -> PspException.notFound("Payout account", targetRef));
            if (!"PENDING_VERIFICATION".equals(a.getStatus())) throw PspException.conflict("NOT_PENDING", "Payout account is " + a.getStatus());
            boolean hadPrevious = false;
            for (MerchantPayoutAccount old : accounts.findByMerchantIdAndCurrencyAndStatus(a.getMerchantId(), a.getCurrency(), "ACTIVE")) {
                old.setStatus("SUPERSEDED");
                accounts.save(old);
                hadPrevious = true;
            }
            a.setStatus("ACTIVE");
            a.setVerifiedBy(approvedBy);
            a.setVerifiedAt(LocalDateTime.now());
            // A changed destination is the classic account-takeover pattern: hold payouts briefly so the merchant can object.
            if (hadPrevious) a.setCoolingOffUntil(LocalDateTime.now().plusHours(coolingOffHours));
            accounts.save(a);
            return "Payout account " + a.getPayoutRef() + " active" + (hadPrevious ? ", cooling off for " + coolingOffHours + "h" : "");
        }
    }
}
