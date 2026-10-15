package zw.co.payport.gateway.psp.merchant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.common.Refs;
import zw.co.payport.gateway.psp.ledger.MerchantFundsService;
import zw.co.payport.gateway.psp.ledger.domain.AccountKey;
import zw.co.payport.gateway.psp.legacy.LegacyGateway;
import zw.co.payport.gateway.psp.platform.approval.ApprovalRequest;
import zw.co.payport.gateway.psp.platform.approval.ApprovalService;
import zw.co.payport.gateway.psp.platform.audit.AuditService;
import zw.co.payport.gateway.psp.platform.crypto.FieldCrypto;
import zw.co.payport.gateway.psp.platform.outbox.EventTypes;
import zw.co.payport.gateway.psp.platform.outbox.OutboxService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Merchant accounts: verification status, risk class, approved products,
 * transaction limits, fund-hold policy, payout accounts and suspension.
 *
 * {@link #assertCanCollect} and {@link #assertCanPayout} are the single
 * gates every payment and payout path calls.
 */
@Service
public class MerchantControlService {

    private static final Logger log = LoggerFactory.getLogger(MerchantControlService.class);

    public static final Set<String> VERIFICATION = Set.of("UNVERIFIED", "PENDING_REVIEW", "VERIFIED", "REJECTED", "EXPIRED");
    public static final Set<String> RISK_CLASSES = Set.of("LOW", "MEDIUM", "HIGH", "PROHIBITED");
    public static final Set<String> SUSPENSION_SCOPES = Set.of("NONE", "COLLECTIONS", "PAYOUTS", "FULL");
    public static final Set<String> LIMIT_TYPES = Set.of("PER_TXN_MIN", "PER_TXN_MAX", "DAILY_AMOUNT", "MONTHLY_AMOUNT", "DAILY_COUNT");
    public static final Set<String> PAYOUT_METHODS = Set.of("BANK_RTGS", "ZIPIT", "ECOCASH", "ONEMONEY", "INNBUCKS", "OMARI");

    public static final String APPROVE_REINSTATE = "MERCHANT_REINSTATE";
    public static final String APPROVE_LIMIT = "MERCHANT_LIMIT_SET";
    public static final String APPROVE_PRODUCT = "MERCHANT_PRODUCT_APPROVE";
    public static final String APPROVE_FUNDS_POLICY = "MERCHANT_FUNDS_POLICY";
    public static final String APPROVE_PAYOUT_ACCOUNT = "PAYOUT_ACCOUNT_VERIFY";
    public static final String APPROVE_VERIFICATION = "MERCHANT_VERIFY";

    private final MerchantProfileRepository profiles;
    private final MerchantProductRepository products;
    private final MerchantLimitRepository limits;
    private final MerchantPayoutAccountRepository payoutAccounts;
    private final LegacyGateway legacy;
    private final ApprovalService approvals;
    private final AuditService audit;
    private final OutboxService outbox;
    private final ObjectMapper om;

    @Value("${payport.psp.merchant.enforce-controls:true}")
    private boolean enforce = true;

    /** Methods auto-approved when a profile is first created for an already-ACTIVE gateway merchant. */
    @Value("${payport.psp.merchant.default-products:ECOCASH,ONEMONEY,OMARI,ZIMSWITCH}")
    private String defaultProducts = "ECOCASH,ONEMONEY,OMARI,ZIMSWITCH";

    @Value("${payport.psp.merchant.payout-cooling-off-hours:24}")
    private int coolingOffHours = 24;

    public MerchantControlService(MerchantProfileRepository profiles, MerchantProductRepository products,
                                  MerchantLimitRepository limits, MerchantPayoutAccountRepository payoutAccounts,
                                  LegacyGateway legacy, ApprovalService approvals, AuditService audit,
                                  OutboxService outbox, ObjectMapper om) {
        this.profiles = profiles;
        this.products = products;
        this.limits = limits;
        this.payoutAccounts = payoutAccounts;
        this.legacy = legacy;
        this.approvals = approvals;
        this.audit = audit;
        this.outbox = outbox;
        this.om = om;
    }

    // ── Profile ──────────────────────────────────────────────────────────

    /** Profile for a merchant, created from the gateway record the first time it is needed. */
    // Business rejections must not mark a caller's surrounding transaction rollback-only.
    @Transactional(noRollbackFor = PspException.class)
    public MerchantProfile profile(long merchantId) {
        return profiles.findByMerchantId(merchantId).orElseGet(() -> createProfile(merchantId));
    }

    private MerchantProfile createProfile(long merchantId) {
        LegacyGateway.MerchantSnapshot m = legacy.merchant(merchantId).orElseThrow(() -> PspException.notFound("Merchant", merchantId));
        MerchantProfile p = new MerchantProfile();
        p.setMerchantId(merchantId);
        p.setMerchantType("MERCHANT");
        p.setVerificationStatus(switch (m.status()) {
            case "ACTIVE", "SUSPENDED" -> "VERIFIED";
            case "REJECTED" -> "REJECTED";
            default -> "PENDING_REVIEW";
        });
        String risk = m.riskScore() >= 70 ? "HIGH" : m.riskScore() >= 40 ? "MEDIUM" : "LOW";
        p.setRiskClass(risk);
        p.setRiskReason("Derived from onboarding risk score " + m.riskScore() + (m.riskFlags() != null ? " (" + m.riskFlags() + ")" : ""));
        RiskPolicy policy = RiskPolicy.defaultsFor(risk);
        p.setHoldDays(policy.holdDays());
        p.setReserveBps(policy.reserveBps());
        p.setReserveDays(policy.reserveDays());
        p.setMinPayoutMinor(100);
        p.setSettlementFrequency("DAILY");
        p.setSuspensionScope("SUSPENDED".equals(m.status()) ? "FULL" : "NONE");
        p.setKycReviewDueAt(LocalDateTime.now().plusMonths(policy.kycReviewMonths()));
        profiles.save(p);

        if (m.isActive()) {
            String ccy = m.defaultCurrency() == null ? "USD" : m.defaultCurrency();
            for (String method : defaultProducts.split(",")) {
                if (method.isBlank()) continue;
                for (String c : new LinkedHashSet<>(List.of(AccountKey.normaliseCurrency(ccy), "USD"))) {
                    approveProductInternal(merchantId, method.trim().toUpperCase(), c, "SYSTEM:default-products");
                }
            }
        }
        log.info("[MERCHANT] Created PSP profile for merchant {}: verification={} risk={}", merchantId, p.getVerificationStatus(), risk);
        return p;
    }

    // ── Gates ────────────────────────────────────────────────────────────

    /**
     * Called before any collection for a merchant. Throws PspException with a
     * stable code when the merchant may not take this payment.
     */
    // Business rejections must not mark a caller's surrounding transaction rollback-only.
    @Transactional(noRollbackFor = PspException.class)
    public void assertCanCollect(long merchantId, String paymentMethodCode, String currency, long amountMinor) {
        if (!enforce) return;
        String ccy = AccountKey.normaliseCurrency(currency);
        LegacyGateway.MerchantSnapshot m = legacy.merchant(merchantId).orElseThrow(() -> PspException.notFound("Merchant", merchantId));
        MerchantProfile p = profile(merchantId);

        if (!m.isActive()) throw PspException.forbidden("MERCHANT_INACTIVE", "Merchant account is not active");
        if (!"VERIFIED".equals(p.getVerificationStatus()))
            throw PspException.forbidden("MERCHANT_NOT_VERIFIED", "Merchant verification is " + p.getVerificationStatus());
        if ("PROHIBITED".equals(p.getRiskClass()))
            throw PspException.forbidden("MERCHANT_PROHIBITED", "Merchant is in a prohibited risk class");
        if ("COLLECTIONS".equals(p.getSuspensionScope()) || "FULL".equals(p.getSuspensionScope()))
            throw PspException.forbidden("MERCHANT_SUSPENDED", "Collections are suspended for this merchant");

        if (paymentMethodCode != null) {
            boolean approved = products.findByMerchantIdAndPaymentMethodCodeAndCurrency(merchantId, paymentMethodCode.toUpperCase(), ccy)
                    .map(x -> "ACTIVE".equals(x.getStatus())).orElse(false);
            if (!approved) throw PspException.forbidden("PRODUCT_NOT_APPROVED",
                    "Merchant is not approved to accept " + paymentMethodCode + " in " + ccy);
        }

        Map<String, Long> l = limitMap(merchantId, ccy);
        if (l.containsKey("PER_TXN_MIN") && amountMinor < l.get("PER_TXN_MIN"))
            throw PspException.unprocessable("BELOW_MIN_AMOUNT", "Amount is below this merchant's minimum of " + l.get("PER_TXN_MIN"));
        if (l.containsKey("PER_TXN_MAX") && amountMinor > l.get("PER_TXN_MAX"))
            throw PspException.unprocessable("ABOVE_MAX_AMOUNT", "Amount exceeds this merchant's per-transaction limit of " + l.get("PER_TXN_MAX"));
        if (l.containsKey("DAILY_AMOUNT") || l.containsKey("DAILY_COUNT")) {
            long[] today = volumeSince(merchantId, ccy, LocalDate.now().atStartOfDay());
            if (l.containsKey("DAILY_AMOUNT") && today[0] + amountMinor > l.get("DAILY_AMOUNT"))
                throw PspException.unprocessable("DAILY_LIMIT_EXCEEDED", "Merchant daily limit reached");
            if (l.containsKey("DAILY_COUNT") && today[1] + 1 > l.get("DAILY_COUNT"))
                throw PspException.unprocessable("DAILY_COUNT_EXCEEDED", "Merchant daily transaction count reached");
        }
        if (l.containsKey("MONTHLY_AMOUNT")) {
            long[] month = volumeSince(merchantId, ccy, LocalDate.now().withDayOfMonth(1).atStartOfDay());
            if (month[0] + amountMinor > l.get("MONTHLY_AMOUNT"))
                throw PspException.unprocessable("MONTHLY_LIMIT_EXCEEDED", "Merchant monthly limit reached");
        }
    }

    /** Called before a merchant is included in a settlement batch. Returns the payout account to use. */
    // Business rejections must not mark a caller's surrounding transaction rollback-only.
    @Transactional(noRollbackFor = PspException.class)
    public MerchantPayoutAccount assertCanPayout(long merchantId, String currency) {
        String ccy = AccountKey.normaliseCurrency(currency);
        MerchantProfile p = profile(merchantId);
        if (!"VERIFIED".equals(p.getVerificationStatus()))
            throw PspException.forbidden("MERCHANT_NOT_VERIFIED", "Merchant verification is " + p.getVerificationStatus());
        if ("PAYOUTS".equals(p.getSuspensionScope()) || "FULL".equals(p.getSuspensionScope()))
            throw PspException.forbidden("PAYOUTS_SUSPENDED", "Payouts are suspended for this merchant");
        MerchantPayoutAccount acct = payoutAccounts.findByMerchantIdAndCurrencyAndStatus(merchantId, ccy, "ACTIVE").stream()
                .findFirst().orElseThrow(() -> PspException.unprocessable("NO_PAYOUT_ACCOUNT", "No verified " + ccy + " payout account"));
        if (acct.getCoolingOffUntil() != null && acct.getCoolingOffUntil().isAfter(LocalDateTime.now()))
            throw PspException.unprocessable("PAYOUT_ACCOUNT_COOLING_OFF", "Payout account changed recently; payouts resume at " + acct.getCoolingOffUntil());
        return acct;
    }

    public MerchantFundsService.FundsPolicy fundsPolicy(long merchantId) {
        MerchantProfile p = profile(merchantId);
        return new MerchantFundsService.FundsPolicy(p.getHoldDays(), p.getReserveBps(), p.getReserveDays());
    }

    private long[] volumeSince(long merchantId, String ccy, LocalDateTime since) {
        List<Object[]> rows = limits.checkoutVolumeSince(merchantId, ccy, since);
        if (rows == null || rows.isEmpty()) return new long[2];
        Object[] r = rows.get(0);
        return new long[]{((Number) r[0]).longValue(), ((Number) r[1]).longValue()};
    }

    private Map<String, Long> limitMap(long merchantId, String ccy) {
        Map<String, Long> m = new HashMap<>();
        for (MerchantLimit l : limits.findByMerchantIdAndCurrency(merchantId, ccy)) m.put(l.getLimitType(), l.getLimitValue());
        return m;
    }

    // ── Suspension (immediate) and reinstatement (four-eyes) ──────────────

    @Transactional
    public MerchantProfile suspend(long merchantId, String scope, String reason, Actor actor) {
        if (!SUSPENSION_SCOPES.contains(scope) || "NONE".equals(scope))
            throw PspException.badRequest("INVALID_SCOPE", "Scope must be COLLECTIONS, PAYOUTS or FULL");
        if (reason == null || reason.isBlank()) throw PspException.badRequest("REASON_REQUIRED", "A suspension reason is required");
        MerchantProfile p = profile(merchantId);
        String before = p.getSuspensionScope();
        p.setSuspensionScope(scope);
        p.setSuspensionReason(reason);
        p.setSuspendedBy(actor.id());
        p.setSuspendedAt(LocalDateTime.now());
        profiles.save(p);
        audit.record(actor, "MERCHANT_SUSPENDED", "MERCHANT", String.valueOf(merchantId),
                Map.of("from", before, "to", scope, "reason", reason));
        outbox.publish(EventTypes.MERCHANT_SUSPENDED, "MERCHANT", String.valueOf(merchantId),
                Map.of("merchantId", merchantId, "scope", scope, "reason", reason));
        return p;
    }

    @Transactional
    public ApprovalRequest requestReinstatement(long merchantId, String reason, Actor actor) {
        MerchantProfile p = profile(merchantId);
        if ("NONE".equals(p.getSuspensionScope())) throw PspException.conflict("NOT_SUSPENDED", "Merchant is not suspended");
        return approvals.request(APPROVE_REINSTATE, String.valueOf(merchantId), json(Map.of("merchantId", merchantId)),
                "Lift " + p.getSuspensionScope() + " suspension on merchant " + merchantId + " (was: " + p.getSuspensionReason() + ")",
                reason, actor);
    }

    // ── Verification & risk ──────────────────────────────────────────────

    @Transactional
    public ApprovalRequest requestVerification(long merchantId, String status, String reason, Actor actor) {
        if (!VERIFICATION.contains(status)) throw PspException.badRequest("INVALID_STATUS", "Status must be one of " + VERIFICATION);
        profile(merchantId);
        return approvals.request(APPROVE_VERIFICATION, String.valueOf(merchantId),
                json(Map.of("merchantId", merchantId, "status", status)),
                "Set verification of merchant " + merchantId + " to " + status, reason, actor);
    }

    /** Raising risk tightens controls at once; lowering needs approval via the funds-policy flow. */
    @Transactional
    public MerchantProfile setRiskClass(long merchantId, String riskClass, String reason, Actor actor) {
        if (!RISK_CLASSES.contains(riskClass)) throw PspException.badRequest("INVALID_RISK_CLASS", "Must be one of " + RISK_CLASSES);
        MerchantProfile p = profile(merchantId);
        String before = p.getRiskClass();
        if (rank(riskClass) < rank(before)) {
            throw PspException.badRequest("USE_APPROVAL", "Lowering risk class needs four-eyes approval: use the funds-policy request");
        }
        RiskPolicy pol = RiskPolicy.defaultsFor(riskClass);
        p.setRiskClass(riskClass);
        p.setRiskReason(reason);
        p.setHoldDays(Math.max(p.getHoldDays(), pol.holdDays()));
        p.setReserveBps(Math.max(p.getReserveBps(), pol.reserveBps()));
        p.setReserveDays(Math.max(p.getReserveDays(), pol.reserveDays()));
        LocalDateTime due = LocalDateTime.now().plusMonths(pol.kycReviewMonths());
        if (p.getKycReviewDueAt() == null || due.isBefore(p.getKycReviewDueAt())) p.setKycReviewDueAt(due);
        profiles.save(p);
        audit.record(actor, "MERCHANT_RISK_RAISED", "MERCHANT", String.valueOf(merchantId),
                Map.of("from", before, "to", riskClass, "reason", String.valueOf(reason)));
        return p;
    }

    @Transactional
    public ApprovalRequest requestFundsPolicy(long merchantId, String riskClass, int holdDays, int reserveBps, int reserveDays,
                                              String reason, Actor actor) {
        if (riskClass != null && !RISK_CLASSES.contains(riskClass)) throw PspException.badRequest("INVALID_RISK_CLASS", "Must be one of " + RISK_CLASSES);
        if (holdDays < 0 || holdDays > 90 || reserveBps < 0 || reserveBps > 5000 || reserveDays < 0 || reserveDays > 365)
            throw PspException.badRequest("INVALID_POLICY", "holdDays 0-90, reserveBps 0-5000, reserveDays 0-365");
        profile(merchantId);
        Map<String, Object> payload = new HashMap<>();
        payload.put("merchantId", merchantId);
        payload.put("riskClass", riskClass);
        payload.put("holdDays", holdDays);
        payload.put("reserveBps", reserveBps);
        payload.put("reserveDays", reserveDays);
        return approvals.request(APPROVE_FUNDS_POLICY, String.valueOf(merchantId), json(payload),
                String.format("Merchant %d funds policy: risk=%s hold=%dd reserve=%dbps for %dd", merchantId, riskClass, holdDays, reserveBps, reserveDays),
                reason, actor);
    }

    // ── Products & limits ────────────────────────────────────────────────

    @Transactional
    public ApprovalRequest requestProduct(long merchantId, String method, String currency, boolean enable, String reason, Actor actor) {
        profile(merchantId);
        String m = method.toUpperCase();
        String c = AccountKey.normaliseCurrency(currency);
        return approvals.request(APPROVE_PRODUCT, merchantId + ":" + m + ":" + c,
                json(Map.of("merchantId", merchantId, "method", m, "currency", c, "enable", enable)),
                (enable ? "Approve " : "Withdraw ") + m + " " + c + " for merchant " + merchantId, reason, actor);
    }

    /** Withdrawing a product is protective and takes effect at once. */
    @Transactional
    public void disableProduct(long merchantId, String method, String currency, String reason, Actor actor) {
        products.findByMerchantIdAndPaymentMethodCodeAndCurrency(merchantId, method.toUpperCase(), AccountKey.normaliseCurrency(currency))
                .ifPresent(p -> {
                    p.setStatus("DISABLED");
                    products.save(p);
                    audit.record(actor, "MERCHANT_PRODUCT_DISABLED", "MERCHANT", String.valueOf(merchantId),
                            Map.of("method", method, "currency", currency, "reason", String.valueOf(reason)));
                });
    }

    void approveProductInternal(long merchantId, String method, String currency, String approver) {
        MerchantProduct p = products.findByMerchantIdAndPaymentMethodCodeAndCurrency(merchantId, method, currency).orElseGet(MerchantProduct::new);
        p.setMerchantId(merchantId);
        p.setPaymentMethodCode(method);
        p.setCurrency(currency);
        p.setStatus("ACTIVE");
        p.setApprovedBy(approver);
        p.setApprovedAt(LocalDateTime.now());
        products.save(p);
    }

    @Transactional
    public ApprovalRequest requestLimit(long merchantId, String currency, String limitType, long value, String reason, Actor actor) {
        if (!LIMIT_TYPES.contains(limitType)) throw PspException.badRequest("INVALID_LIMIT_TYPE", "Must be one of " + LIMIT_TYPES);
        if (value < 0) throw PspException.badRequest("INVALID_LIMIT", "Limit must be >= 0");
        profile(merchantId);
        String c = AccountKey.normaliseCurrency(currency);
        return approvals.request(APPROVE_LIMIT, merchantId + ":" + c + ":" + limitType,
                json(Map.of("merchantId", merchantId, "currency", c, "limitType", limitType, "value", value)),
                "Set " + limitType + " for merchant " + merchantId + " (" + c + ") to " + value, reason, actor);
    }

    public List<MerchantProduct> products(long merchantId) { return products.findByMerchantId(merchantId); }
    public List<MerchantLimit> limits(long merchantId) { return limits.findByMerchantId(merchantId); }

    // ── Payout accounts ──────────────────────────────────────────────────

    public record PayoutAccountRequest(String currency, String method, String bankName, String bankCode, String branchCode,
                                       String accountName, String accountNumber, String mobileNumber) {}

    @Transactional
    public MerchantPayoutAccount addPayoutAccount(long merchantId, PayoutAccountRequest r, Actor actor) {
        String method = r.method() == null ? "" : r.method().toUpperCase();
        if (!PAYOUT_METHODS.contains(method)) throw PspException.badRequest("INVALID_PAYOUT_METHOD", "Must be one of " + PAYOUT_METHODS);
        boolean bank = method.equals("BANK_RTGS") || method.equals("ZIPIT");
        if (r.accountName() == null || r.accountName().isBlank()) throw PspException.badRequest("ACCOUNT_NAME_REQUIRED", "accountName is required");
        if (bank && (r.accountNumber() == null || r.accountNumber().isBlank() || r.bankName() == null))
            throw PspException.badRequest("BANK_DETAILS_REQUIRED", "Bank payouts need bankName and accountNumber");
        if (!bank && (r.mobileNumber() == null || !r.mobileNumber().matches("^\\+?263\\d{9}$")))
            throw PspException.badRequest("MOBILE_REQUIRED", "Wallet payouts need a +263 mobile number");

        MerchantPayoutAccount a = new MerchantPayoutAccount();
        a.setPayoutRef(Refs.newRef("pa"));
        a.setMerchantId(merchantId);
        a.setCurrency(AccountKey.normaliseCurrency(r.currency()));
        a.setMethod(method);
        a.setBankName(r.bankName());
        a.setBankCode(r.bankCode());
        a.setBranchCode(r.branchCode());
        a.setAccountName(r.accountName().trim());
        if (bank) {
            String acct = r.accountNumber().replaceAll("\\s", "");
            a.setAccountNumber(acct);
            a.setAccountNumberLast4(acct.length() > 4 ? acct.substring(acct.length() - 4) : acct);
        } else {
            a.setMobileNumber(r.mobileNumber());
            a.setAccountNumberLast4(r.mobileNumber().substring(r.mobileNumber().length() - 4));
        }
        a.setStatus("PENDING_VERIFICATION");
        a.setCreatedBy(actor.id());
        payoutAccounts.save(a);

        audit.record(actor, "PAYOUT_ACCOUNT_ADDED", "MERCHANT", String.valueOf(merchantId),
                Map.of("payoutRef", a.getPayoutRef(), "method", method, "last4", a.getAccountNumberLast4()));
        approvals.request(APPROVE_PAYOUT_ACCOUNT, a.getPayoutRef(), json(Map.of("payoutRef", a.getPayoutRef())),
                "Verify " + method + " payout account ****" + a.getAccountNumberLast4() + " (" + a.getAccountName() + ") for merchant " + merchantId,
                "New payout destination", actor);
        return a;
    }

    public List<MerchantPayoutAccount> payoutAccounts(long merchantId) {
        return payoutAccounts.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    int coolingOffHours() { return coolingOffHours; }

    private static int rank(String risk) {
        return switch (risk) { case "LOW" -> 0; case "MEDIUM" -> 1; case "HIGH" -> 2; default -> 3; };
    }

    private String json(Object o) {
        try { return om.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Masked view for APIs: never return full account numbers. */
    public static Map<String, Object> view(MerchantPayoutAccount a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("payoutRef", a.getPayoutRef());
        m.put("currency", a.getCurrency());
        m.put("method", a.getMethod());
        m.put("bankName", a.getBankName());
        m.put("accountName", a.getAccountName());
        m.put("accountNumber", "****" + a.getAccountNumberLast4());
        m.put("status", a.getStatus());
        m.put("verifiedBy", a.getVerifiedBy());
        m.put("verifiedAt", a.getVerifiedAt());
        m.put("coolingOffUntil", a.getCoolingOffUntil());
        m.put("createdAt", a.getCreatedAt());
        return m;
    }

    static String mask(String s) {
        return FieldCrypto.mask(s, 4);
    }
}
