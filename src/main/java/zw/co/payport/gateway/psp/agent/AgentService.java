package zw.co.payport.gateway.psp.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.payport.gateway.psp.common.*;
import zw.co.payport.gateway.psp.kyc.CustomerProfile;
import zw.co.payport.gateway.psp.kyc.KycService;
import zw.co.payport.gateway.psp.ledger.LedgerService;
import zw.co.payport.gateway.psp.ledger.domain.AccountCategory;
import zw.co.payport.gateway.psp.ledger.domain.AccountKey;
import zw.co.payport.gateway.psp.ledger.domain.WalletPostingRules;
import zw.co.payport.gateway.psp.platform.approval.ApprovalRequest;
import zw.co.payport.gateway.psp.platform.approval.ApprovalService;
import zw.co.payport.gateway.psp.platform.audit.AuditService;
import zw.co.payport.gateway.psp.platform.crypto.FieldCrypto;
import zw.co.payport.gateway.psp.platform.outbox.EventTypes;
import zw.co.payport.gateway.psp.platform.outbox.OutboxService;
import zw.co.payport.gateway.psp.wallet.TariffMath;
import zw.co.payport.gateway.psp.wallet.WalletService;
import zw.co.payport.gateway.psp.wallet.WalletTransfer;
import zw.co.payport.gateway.psp.wallet.WalletTransferRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Agent network: onboarding, float, cash-in / cash-out with controls, and commissions.
 *
 * Agent limits are set in USD minor units; ZWG activity is measured at its USD equivalent.
 *
 * Controls on every agent transaction:
 *  - agent ACTIVE, KYC verified, agent PIN (lockout after 3 wrong attempts)
 *  - per-transaction limit and daily cash-in / cash-out totals per agent
 *  - cash-in limited by the agent's e-float (the ledger refuses to take AGENT_FLOAT below zero)
 *  - cash-out limited so the agent's float never exceeds maxFloat (concentration risk)
 *  - the customer's own KYC tier limits and the fraud engine
 *
 * Float purchases and redemptions create / destroy e-money, so they need a
 * matching bank movement and four-eyes approval.
 */
@Service
public class AgentService {

    public static final String APPROVE_ACTIVATE = "AGENT_ACTIVATE";
    public static final String APPROVE_FLOAT_PURCHASE = "AGENT_FLOAT_PURCHASE";
    public static final String APPROVE_FLOAT_REDEMPTION = "AGENT_FLOAT_REDEMPTION";
    public static final String APPROVE_LIMITS = "AGENT_LIMITS";
    private static final int MAX_PIN_FAILURES = 3;

    public record OnboardRequest(String agentType, Long parentAgentId, String businessName, String contactName, String msisdn,
                                 String nationalId, String province, String district, String gpsLat, String gpsLng) {}

    private final AgentRepository agents;
    private final WalletService wallets;
    private final WalletTransferRepository transfers;
    private final KycService kyc;
    private final LedgerService ledger;
    private final ApprovalService approvals;
    private final AuditService audit;
    private final OutboxService outbox;
    private final RiskPorts.Screener screener;
    private final FieldCrypto crypto;
    private final ObjectMapper om;
    private final TransactionTemplate newTx;
    private final zw.co.payport.gateway.psp.fx.FxRateService fx;

    public AgentService(AgentRepository agents, WalletService wallets, WalletTransferRepository transfers, KycService kyc,
                        LedgerService ledger, ApprovalService approvals, AuditService audit, OutboxService outbox,
                        RiskPorts.Screener screener, FieldCrypto crypto, ObjectMapper om, PlatformTransactionManager txManager,
                        zw.co.payport.gateway.psp.fx.FxRateService fx) {
        this.fx = fx;
        this.agents = agents;
        this.wallets = wallets;
        this.transfers = transfers;
        this.kyc = kyc;
        this.ledger = ledger;
        this.approvals = approvals;
        this.audit = audit;
        this.outbox = outbox;
        this.screener = screener;
        this.crypto = crypto;
        this.om = om;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ── Onboarding ───────────────────────────────────────────────────────

    @Transactional
    public ApprovalRequest onboard(OnboardRequest r, Actor actor) {
        String type = r.agentType() == null ? "AGENT" : r.agentType().toUpperCase();
        if (!Set.of("AGENT", "SUPER_AGENT").contains(type)) throw PspException.badRequest("INVALID_TYPE", "AGENT or SUPER_AGENT");
        String msisdn = KycService.normaliseMsisdn(r.msisdn());
        if (agents.findByMsisdn(msisdn).isPresent()) throw PspException.conflict("AGENT_EXISTS", "An agent with this number exists");
        if (r.parentAgentId() != null) {
            Agent parent = agents.findById(r.parentAgentId()).orElseThrow(() -> PspException.notFound("Super-agent", r.parentAgentId()));
            if (!"SUPER_AGENT".equals(parent.getAgentType())) throw PspException.badRequest("PARENT_NOT_SUPER_AGENT", "Parent must be a super-agent");
        }
        Agent a = new Agent();
        a.setAgentRef(Refs.newRef("agt"));
        a.setAgentCode(newAgentCode());
        a.setAgentType(type);
        a.setParentAgentId(r.parentAgentId());
        a.setBusinessName(r.businessName());
        a.setContactName(r.contactName());
        a.setMsisdn(msisdn);
        a.setNationalIdIndex(r.nationalId() == null ? null : crypto.blindIndex(r.nationalId()));
        a.setProvince(r.province());
        a.setDistrict(r.district());
        a.setGpsLat(r.gpsLat());
        a.setGpsLng(r.gpsLng());
        a.setStatus("PENDING");
        a.setKycVerified(false);
        a.setRiskRating("MEDIUM");
        // Conservative starting limits; compliance raises them through AGENT_LIMITS approvals.
        a.setPerTxnLimitMinor(50_000);
        a.setDailyCashInLimitMinor(500_000);
        a.setDailyCashOutLimitMinor(500_000);
        a.setMaxFloatMinor(1_000_000);
        a.setPinFailures(0);
        a.setCreatedBy(actor.id());
        agents.save(a);

        RiskPorts.ScreeningOutcome s = screener.screen("AGENT", a.getAgentRef(), r.contactName() != null ? r.contactName() : r.businessName(), null, "ZW");
        if (!"CLEAR".equals(s.status())) a.setRiskRating("HIGH");
        agents.save(a);
        audit.record(actor, "AGENT_ONBOARDED", "AGENT", a.getAgentRef(), Map.of("type", type, "screening", s.status()));
        return approvals.request(APPROVE_ACTIVATE, a.getAgentRef(), json(Map.of("agentRef", a.getAgentRef())),
                "Activate " + type + " " + a.getBusinessName() + " (" + msisdn + "), screening " + s.status(), null, actor);
    }

    private String newAgentCode() {
        for (int i = 0; i < 20; i++) {
            String code = Refs.digits(6);
            if (code.charAt(0) != '0' && agents.findByAgentCode(code).isEmpty()) return code;
        }
        throw new IllegalStateException("Could not allocate an agent code");
    }

    @Transactional
    public void setPin(String agentRef, String pin, Actor actor) {
        Agent a = byRef(agentRef);
        a.setPinHash(Pins.hash(pin));
        a.setPinFailures(0);
        a.setLockedUntil(null);
        agents.save(a);
        audit.record(actor, "AGENT_PIN_SET", "AGENT", agentRef, Map.of());
    }

    @Transactional
    public Agent suspend(String agentRef, String reason, Actor actor) {
        Agent a = byRef(agentRef);
        a.setStatus("SUSPENDED");
        a.setStatusReason(reason);
        agents.save(a);
        audit.record(actor, "AGENT_SUSPENDED", "AGENT", agentRef, Map.of("reason", String.valueOf(reason)));
        return a;
    }

    @Transactional
    public ApprovalRequest requestLimits(String agentRef, long perTxn, long dailyIn, long dailyOut, long maxFloat, String reason, Actor actor) {
        byRef(agentRef);
        if (perTxn <= 0 || dailyIn <= 0 || dailyOut <= 0 || maxFloat <= 0) throw PspException.badRequest("INVALID_LIMITS", "Limits must be positive");
        return approvals.request(APPROVE_LIMITS, agentRef, json(Map.of("agentRef", agentRef, "perTxn", perTxn, "dailyIn", dailyIn,
                "dailyOut", dailyOut, "maxFloat", maxFloat)), "Agent " + agentRef + " limits: txn " + perTxn + ", in " + dailyIn
                + ", out " + dailyOut + ", float " + maxFloat, reason, actor);
    }

    // ── Cash-in / cash-out ───────────────────────────────────────────────

    /** Agent takes cash from a customer and credits the customer's wallet from the agent's e-float. */
    @Transactional
    public WalletService.TransferResult cashIn(String agentCode, String agentPin, String customerMsisdn, String currency,
                                               long amountMinor, String channel) {
        String ccy = AccountKey.normaliseCurrency(currency);
        Agent a = activeAgent(agentCode);
        verifyAgentPin(a, agentPin);
        positive(amountMinor);
        long usd = fx.usdEquivalent(amountMinor, ccy);
        if (usd > a.getPerTxnLimitMinor()) throw PspException.unprocessable("AGENT_TXN_LIMIT", "Above this agent's per-transaction limit");
        if (agentTotalUsd(a.getId(), "CASH_IN") + usd > a.getDailyCashInLimitMinor()) throw PspException.unprocessable("AGENT_DAILY_CASH_IN", "Agent daily cash-in limit reached");

        CustomerProfile c = kyc.requireActive(customerMsisdn);
        wallets.checkCreditLimit(c, ccy, amountMinor);
        RiskPorts.RiskDecision d = wallets.assess("CASH_IN", channel, c, "agent:" + a.getId(), null, a.getId(), ccy, amountMinor);
        TariffMath.Charges ch = wallets.charges("CASH_IN", ccy, amountMinor);

        String ref = Refs.newRef("wtx");
        LedgerService.PostedEntry e = ledger.post(WalletPostingRules.cashIn(ref, a.getId(), c.getCustomerRef(), ccy, amountMinor,
                ch.agentCommissionMinor()), "agent:" + a.getAgentCode());
        WalletTransfer t = record(ref, "CASH_IN", channel, "agent:" + a.getId(), c.getCustomerRef(), a.getId(), ccy, amountMinor,
                0, ch.agentCommissionMinor(), d.action(), e.entryRef(), a.getMsisdn());
        outbox.publish(EventTypes.AGENT_CASH_IN, "WALLET_TRANSFER", ref, Map.of("transferRef", ref, "agentCode", a.getAgentCode(),
                "agentMsisdn", a.getMsisdn(), "customerMsisdn", c.getMsisdn(), "currency", ccy, "amountMinor", amountMinor,
                "commissionMinor", ch.agentCommissionMinor()));
        return new WalletService.TransferResult(ref, t.getStatus(), amountMinor, 0, 0, ccy,
                ledger.balance(AccountKey.agent(AccountCategory.AGENT_FLOAT, a.getId(), ccy)), d.action());
    }

    /** Customer authorises with their wallet PIN; e-value moves to the agent who hands over cash. */
    @Transactional
    public WalletService.TransferResult cashOut(String customerMsisdn, String customerPin, String agentCode, String currency,
                                                long amountMinor, String channel) {
        String ccy = AccountKey.normaliseCurrency(currency);
        Agent a = activeAgent(agentCode);
        positive(amountMinor);
        CustomerProfile c = kyc.requireActive(customerMsisdn);
        wallets.verifyPin("CUSTOMER", c.getCustomerRef(), customerPin);
        long usd = fx.usdEquivalent(amountMinor, ccy);
        if (usd > a.getPerTxnLimitMinor()) throw PspException.unprocessable("AGENT_TXN_LIMIT", "Above this agent's per-transaction limit");
        if (agentTotalUsd(a.getId(), "CASH_OUT") + usd > a.getDailyCashOutLimitMinor()) throw PspException.unprocessable("AGENT_DAILY_CASH_OUT", "Agent daily cash-out limit reached");
        if (floatUsd(a.getId()) + usd > a.getMaxFloatMinor()) throw PspException.unprocessable("AGENT_FLOAT_CEILING", "Agent cannot hold more float; try another agent");

        TariffMath.Charges ch = wallets.charges("CASH_OUT", ccy, amountMinor);
        wallets.checkDebitLimits(c, ccy, amountMinor + ch.customerFeeMinor());
        RiskPorts.RiskDecision d = wallets.assess("CASH_OUT", channel, c, "agent:" + a.getId(), null, a.getId(), ccy, amountMinor);

        String ref = Refs.newRef("wtx");
        LedgerService.PostedEntry e = ledger.post(WalletPostingRules.cashOut(ref, a.getId(), c.getCustomerRef(), ccy, amountMinor,
                ch.customerFeeMinor(), ch.agentCommissionMinor()), c.getCustomerRef());
        WalletTransfer t = record(ref, "CASH_OUT", channel, c.getCustomerRef(), "agent:" + a.getId(), a.getId(), ccy, amountMinor,
                ch.customerFeeMinor(), ch.agentCommissionMinor(), d.action(), e.entryRef(), c.getMsisdn());
        outbox.publish(EventTypes.AGENT_CASH_OUT, "WALLET_TRANSFER", ref, Map.of("transferRef", ref, "agentCode", a.getAgentCode(),
                "agentMsisdn", a.getMsisdn(), "customerMsisdn", c.getMsisdn(), "currency", ccy, "amountMinor", amountMinor,
                "feeMinor", ch.customerFeeMinor()));
        return new WalletService.TransferResult(ref, t.getStatus(), amountMinor, ch.customerFeeMinor(), 0, ccy,
                wallets.balance(c.getCustomerRef(), ccy), d.action());
    }

    // ── Float management ─────────────────────────────────────────────────

    /** Agent deposited cash at the trust bank. Four-eyes: e-money is only created against a confirmed bank deposit. */
    @Transactional
    public ApprovalRequest requestFloatPurchase(String agentRef, String currency, long amountMinor, String bankDepositRef,
                                                String trustCashAccount, Actor actor) {
        Agent a = byRef(agentRef);
        positive(amountMinor);
        if (bankDepositRef == null || bankDepositRef.isBlank()) throw PspException.badRequest("DEPOSIT_REF_REQUIRED", "Bank deposit reference required");
        if (floatUsd(a.getId()) + fx.usdEquivalent(amountMinor, currency) > a.getMaxFloatMinor())
            throw PspException.unprocessable("AGENT_FLOAT_CEILING", "Purchase would exceed the agent's maximum float");
        return approvals.request(APPROVE_FLOAT_PURCHASE, agentRef + ":" + bankDepositRef,
                json(Map.of("agentId", a.getId(), "currency", AccountKey.normaliseCurrency(currency), "amountMinor", amountMinor,
                        "depositRef", bankDepositRef, "cashAccount", trustCashAccount)),
                "Credit " + amountMinor + " " + currency + " float to agent " + a.getAgentCode() + " for deposit " + bankDepositRef, null, actor);
    }

    @Transactional
    public ApprovalRequest requestFloatRedemption(String agentRef, String currency, long amountMinor, String trustCashAccount, Actor actor) {
        Agent a = byRef(agentRef);
        positive(amountMinor);
        long bal = ledger.balance(AccountKey.agent(AccountCategory.AGENT_FLOAT, a.getId(), currency));
        if (bal < amountMinor) throw PspException.unprocessable("INSUFFICIENT_FLOAT", "Agent float is " + bal);
        String ref = Refs.newRef("afr");
        return approvals.request(APPROVE_FLOAT_REDEMPTION, agentRef + ":" + ref,
                json(Map.of("agentId", a.getId(), "currency", AccountKey.normaliseCurrency(currency), "amountMinor", amountMinor,
                        "redemptionRef", ref, "cashAccount", trustCashAccount)),
                "Redeem " + amountMinor + " " + currency + " float for agent " + a.getAgentCode(), null, actor);
    }

    /** Super-agent rebalances float to one of its own agents. */
    @Transactional
    public String transferFloat(String superAgentCode, String superAgentPin, String toAgentCode, String currency, long amountMinor) {
        Agent sup = activeAgent(superAgentCode);
        verifyAgentPin(sup, superAgentPin);
        if (!"SUPER_AGENT".equals(sup.getAgentType())) throw PspException.forbidden("NOT_SUPER_AGENT", "Only super-agents can transfer float");
        Agent to = activeAgent(toAgentCode);
        if (!sup.getId().equals(to.getParentAgentId())) throw PspException.forbidden("NOT_YOUR_AGENT", "That agent is not in your network");
        positive(amountMinor);
        if (floatUsd(to.getId()) + fx.usdEquivalent(amountMinor, currency) > to.getMaxFloatMinor())
            throw PspException.unprocessable("AGENT_FLOAT_CEILING", "Receiving agent would exceed maximum float");
        String ref = Refs.newRef("afx");
        LedgerService.PostedEntry e = ledger.post(WalletPostingRules.agentFloatTransfer(ref, sup.getId(), to.getId(), currency, amountMinor),
                "agent:" + sup.getAgentCode());
        record(ref, "FLOAT_TRANSFER", "USSD", "agent:" + sup.getId(), "agent:" + to.getId(), to.getId(), AccountKey.normaliseCurrency(currency),
                amountMinor, 0, 0, "ALLOW", e.entryRef(), sup.getMsisdn());
        return ref;
    }

    /** Pays every agent's accrued commission into its float. Monthly, idempotent per agent+month. */
    @Transactional
    public int payCommissions(String period, Actor actor) {
        int paid = 0;
        for (Agent a : agents.findByStatus("ACTIVE")) {
            for (String ccy : List.of("USD", "ZWG")) {
                long due = ledger.balance(AccountKey.agent(AccountCategory.AGENT_COMMISSION_PAYABLE, a.getId(), ccy));
                if (due <= 0) continue;
                ledger.post(WalletPostingRules.agentCommissionPaid(a.getId() + ":" + period + ":" + ccy, a.getId(), ccy, due), actor.id());
                paid++;
            }
        }
        audit.record(actor, "AGENT_COMMISSIONS_PAID", "AGENT_COMMISSION", period, Map.of("payouts", paid));
        return paid;
    }

    public Map<String, Long> balances(String agentRef) {
        Agent a = byRef(agentRef);
        Map<String, Long> m = new LinkedHashMap<>();
        for (String ccy : List.of("USD", "ZWG")) {
            m.put("float" + ccy, ledger.balance(AccountKey.agent(AccountCategory.AGENT_FLOAT, a.getId(), ccy)));
            m.put("commission" + ccy, ledger.balance(AccountKey.agent(AccountCategory.AGENT_COMMISSION_PAYABLE, a.getId(), ccy)));
        }
        return m;
    }

    // ── helpers ──────────────────────────────────────────────────────────

    public Agent byRef(String ref) {
        return agents.findByAgentRef(ref).orElseThrow(() -> PspException.notFound("Agent", ref));
    }

    public Agent activeAgent(String agentCode) {
        Agent a = agents.findByAgentCode(agentCode).orElseThrow(() -> PspException.notFound("Agent code", agentCode));
        if (!"ACTIVE".equals(a.getStatus())) throw PspException.forbidden("AGENT_" + a.getStatus(), "This agent is not active");
        if (!a.isKycVerified()) throw PspException.forbidden("AGENT_NOT_VERIFIED", "Agent KYC is not verified");
        return a;
    }

    public void verifyAgentPin(Agent a, String pin) {
        if (a.getLockedUntil() != null && a.getLockedUntil().isAfter(LocalDateTime.now()))
            throw PspException.forbidden("PIN_LOCKED", "Agent PIN locked until " + a.getLockedUntil().toLocalTime().withNano(0));
        if (!Pins.matches(pin, a.getPinHash())) {
            newTx.executeWithoutResult(s -> agents.findById(a.getId()).ifPresent(x -> {
                x.setPinFailures(x.getPinFailures() + 1);
                if (x.getPinFailures() >= MAX_PIN_FAILURES) x.setLockedUntil(LocalDateTime.now().plusMinutes(30));
                agents.save(x);
            }));
            throw PspException.forbidden("WRONG_PIN", "Wrong agent PIN");
        }
        if (a.getPinFailures() > 0) {
            newTx.executeWithoutResult(s -> agents.findById(a.getId()).ifPresent(x -> { x.setPinFailures(0); x.setLockedUntil(null); agents.save(x); }));
        }
    }

    private long agentTotal(Long agentId, String type, String ccy) {
        List<Object[]> r = transfers.agentTotalsSince(agentId, type, ccy, LocalDate.now().atStartOfDay());
        return r == null || r.isEmpty() ? 0 : ((Number) r.get(0)[0]).longValue();
    }

    /** Agent limits are set in USD; ZWG activity counts at its USD equivalent. */
    private long agentTotalUsd(Long agentId, String type) {
        return agentTotal(agentId, type, "USD") + fx.usdEquivalent(agentTotal(agentId, type, "ZWG"), "ZWG");
    }

    private long floatUsd(Long agentId) {
        return ledger.balance(AccountKey.agent(AccountCategory.AGENT_FLOAT, agentId, "USD"))
                + fx.usdEquivalent(Math.max(0, ledger.balance(AccountKey.agent(AccountCategory.AGENT_FLOAT, agentId, "ZWG"))), "ZWG");
    }

    private WalletTransfer record(String ref, String type, String channel, String from, String to, Long agentId, String ccy,
                                  long amount, long fee, long commission, String risk, String entryRef, String by) {
        WalletTransfer t = new WalletTransfer();
        t.setTransferRef(ref);
        t.setType(type);
        t.setChannel(channel == null ? "USSD" : channel.toUpperCase());
        t.setFromRef(from);
        t.setToRef(to);
        t.setAgentId(agentId);
        t.setCurrency(ccy);
        t.setAmountMinor(amount);
        t.setFeeMinor(fee);
        t.setTaxMinor(0);
        t.setCommissionMinor(commission);
        t.setStatus("COMPLETED");
        t.setRiskDecision(risk);
        t.setJournalEntryRef(entryRef);
        t.setInitiatedBy(by);
        return transfers.save(t);
    }

    private static void positive(long amountMinor) {
        if (amountMinor <= 0) throw PspException.badRequest("INVALID_AMOUNT", "Amount must be greater than zero");
    }

    private String json(Object o) {
        try { return om.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
