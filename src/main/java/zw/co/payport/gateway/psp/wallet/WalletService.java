package zw.co.payport.gateway.psp.wallet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.psp.common.*;
import zw.co.payport.gateway.psp.fees.FeeService;
import zw.co.payport.gateway.psp.fees.PostingTaxCodes;
import zw.co.payport.gateway.psp.fees.TaxRate;
import zw.co.payport.gateway.psp.integration.PayoutRail;
import zw.co.payport.gateway.psp.integration.PayoutRails;
import zw.co.payport.gateway.psp.kyc.CustomerProfile;
import zw.co.payport.gateway.psp.kyc.KycService;
import zw.co.payport.gateway.psp.kyc.KycTierLimit;
import zw.co.payport.gateway.psp.ledger.LedgerService;
import zw.co.payport.gateway.psp.ledger.MerchantFundsService;
import zw.co.payport.gateway.psp.ledger.domain.AccountKey;
import zw.co.payport.gateway.psp.ledger.domain.FeeQuote;
import zw.co.payport.gateway.psp.ledger.domain.WalletPostingRules;
import zw.co.payport.gateway.psp.merchant.MerchantControlService;
import zw.co.payport.gateway.psp.platform.outbox.EventTypes;
import zw.co.payport.gateway.psp.platform.outbox.OutboxService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Customer e-money wallets: balances, P2P transfers, paying merchants and
 * withdrawals. Every operation:
 *   1. checks the wallet and customer are active, and the PIN
 *   2. checks KYC tier limits (per transaction, daily, monthly, max balance)
 *   3. asks the fraud engine (BLOCK stops it, REVIEW proceeds and opens a case)
 *   4. posts one balanced ledger entry (which itself refuses overdrafts)
 *   5. records a WalletTransfer and an outbox event for notifications/monitoring
 * The wallet balance is the CUSTOMER_WALLET ledger account; it is not stored anywhere else.
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);
    static final List<String> DEBIT_TYPES = List.of("P2P", "WALLET_MERCHANT_PAYMENT", "CASH_OUT", "WITHDRAWAL", "FX");
    private static final int MAX_PIN_FAILURES = 3;

    public record TransferResult(String transferRef, String status, long amountMinor, long feeMinor, long taxMinor,
                                 String currency, long balanceAfterMinor, String riskDecision) {}

    private final WalletRepository wallets;
    private final WalletTransferRepository transfers;
    private final WalletTariffRepository tariffs;
    private final KycService kyc;
    private final LedgerService ledger;
    private final MerchantFundsService merchantFunds;
    private final MerchantControlService merchants;
    private final FeeService fees;
    private final RiskPorts.RiskAssessor risk;
    private final PayoutRails rails;
    private final OutboxService outbox;
    private final BreakReporter breaks;
    private final org.springframework.transaction.support.TransactionTemplate newTx;

    public WalletService(WalletRepository wallets, WalletTransferRepository transfers, WalletTariffRepository tariffs,
                         KycService kyc, LedgerService ledger, MerchantFundsService merchantFunds, MerchantControlService merchants,
                         FeeService fees, RiskPorts.RiskAssessor risk, PayoutRails rails, OutboxService outbox, BreakReporter breaks,
                         org.springframework.transaction.PlatformTransactionManager txManager) {
        this.wallets = wallets;
        this.transfers = transfers;
        this.tariffs = tariffs;
        this.kyc = kyc;
        this.ledger = ledger;
        this.merchantFunds = merchantFunds;
        this.merchants = merchants;
        this.fees = fees;
        this.risk = risk;
        this.rails = rails;
        this.outbox = outbox;
        this.breaks = breaks;
        this.newTx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ── Opening & PIN ─────────────────────────────────────────────────────

    @Transactional
    public Wallet open(String ownerType, String ownerRef, String currency) {
        String ccy = AccountKey.normaliseCurrency(currency);
        return wallets.findByOwnerTypeAndOwnerRefAndCurrency(ownerType, ownerRef, ccy).orElseGet(() -> {
            Wallet w = new Wallet();
            w.setWalletRef(Refs.newRef("wal"));
            w.setOwnerType(ownerType);
            w.setOwnerRef(ownerRef);
            w.setCurrency(ccy);
            w.setStatus("ACTIVE");
            w.setPinFailures(0);
            try {
                return wallets.saveAndFlush(w);
            } catch (DataIntegrityViolationException raced) {
                return wallets.findByOwnerTypeAndOwnerRefAndCurrency(ownerType, ownerRef, ccy).orElseThrow();
            }
        });
    }

    /** Registers a customer and opens USD + ZWG wallets with their PIN. */
    @Transactional
    public CustomerProfile registerCustomer(String msisdn, String firstName, String lastName, LocalDate dob, String pin, Actor actor) {
        Pins.validate(pin);
        CustomerProfile c = kyc.register(msisdn, firstName, lastName, dob, null, actor);
        String hash = Pins.hash(pin);
        for (String ccy : List.of("USD", "ZWG")) {
            Wallet w = open("CUSTOMER", c.getCustomerRef(), ccy);
            w.setPinHash(hash);
            wallets.save(w);
        }
        return c;
    }

    /**
     * Checks a wallet PIN. Failures are counted in their own transaction so
     * they persist even though the calling payment transaction rolls back.
     */
    public void verifyPin(String ownerType, String ownerRef, String pin) {
        List<Wallet> ws = wallets.findByOwnerTypeAndOwnerRef(ownerType, ownerRef);
        if (ws.isEmpty()) throw PspException.notFound("Wallet", ownerRef);
        Wallet w = ws.get(0);
        if (w.getLockedUntil() != null && w.getLockedUntil().isAfter(LocalDateTime.now())) {
            throw PspException.forbidden("PIN_LOCKED", "Too many wrong PINs. Try again after " + w.getLockedUntil().toLocalTime().withNano(0));
        }
        if (!Pins.matches(pin, w.getPinHash())) {
            Integer f = newTx.execute(s -> {
                int failures = 0;
                for (Wallet x : wallets.findByOwnerTypeAndOwnerRef(ownerType, ownerRef)) {
                    failures = x.getPinFailures() + 1;
                    x.setPinFailures(failures);
                    if (failures >= MAX_PIN_FAILURES) x.setLockedUntil(LocalDateTime.now().plusMinutes(30));
                    wallets.save(x);
                }
                return failures;
            });
            throw PspException.forbidden("WRONG_PIN", f != null && f >= MAX_PIN_FAILURES ? "Wrong PIN. Wallet locked for 30 minutes." : "Wrong PIN");
        }
        if (w.getPinFailures() > 0) {
            newTx.executeWithoutResult(s -> {
                for (Wallet x : wallets.findByOwnerTypeAndOwnerRef(ownerType, ownerRef)) { x.setPinFailures(0); x.setLockedUntil(null); wallets.save(x); }
            });
        }
    }

    @Transactional
    public void changePin(String customerRef, String oldPin, String newPin) {
        verifyPin("CUSTOMER", customerRef, oldPin);
        String h = Pins.hash(newPin);
        for (Wallet w : wallets.findByOwnerTypeAndOwnerRef("CUSTOMER", customerRef)) { w.setPinHash(h); wallets.save(w); }
    }

    public long balance(String customerRef, String currency) {
        return ledger.balance(AccountKey.customer(customerRef, currency));
    }

    // ── P2P ───────────────────────────────────────────────────────────────

    @Transactional
    public TransferResult sendMoney(String fromMsisdn, String toMsisdn, String currency, long amountMinor, String pin,
                                    String channel, String clientRef) {
        String ccy = AccountKey.normaliseCurrency(currency);
        CustomerProfile from = kyc.requireActive(fromMsisdn);
        CustomerProfile to = kyc.requireActive(toMsisdn);
        if (from.getCustomerRef().equals(to.getCustomerRef())) throw PspException.badRequest("SAME_WALLET", "You cannot send money to yourself");
        requireWallet(from.getCustomerRef(), ccy);
        requireWallet(to.getCustomerRef(), ccy);
        verifyPin("CUSTOMER", from.getCustomerRef(), pin);
        positive(amountMinor);

        TariffMath.Charges ch = charges("P2P", ccy, amountMinor);
        long tax = transferTax(ccy, amountMinor);
        checkDebitLimits(from, ccy, amountMinor + ch.customerFeeMinor() + tax);
        checkCreditLimit(to, ccy, amountMinor);
        RiskPorts.RiskDecision d = assess("P2P", channel, from, to.getCustomerRef(), null, null, ccy, amountMinor);

        String ref = transferRef(clientRef);
        LedgerService.PostedEntry e = ledger.post(WalletPostingRules.p2p(ref, from.getCustomerRef(), to.getCustomerRef(), ccy,
                amountMinor, ch.customerFeeMinor(), tax), from.getCustomerRef());
        WalletTransfer t = record(ref, "P2P", channel, from.getCustomerRef(), to.getCustomerRef(), null, null, ccy, amountMinor,
                ch.customerFeeMinor(), tax, 0, d.action(), e.entryRef(), from.getMsisdn());
        notify(t, from.getMsisdn(), to.getMsisdn());
        return result(t, from.getCustomerRef());
    }

    // ── Pay a merchant from the wallet ───────────────────────────────────

    @Transactional
    public TransferResult payMerchant(String fromMsisdn, long merchantId, String currency, long amountMinor, String pin,
                                      String channel, String clientRef) {
        String ccy = AccountKey.normaliseCurrency(currency);
        CustomerProfile from = kyc.requireActive(fromMsisdn);
        requireWallet(from.getCustomerRef(), ccy);
        verifyPin("CUSTOMER", from.getCustomerRef(), pin);
        positive(amountMinor);
        merchants.assertCanCollect(merchantId, "WALLET", ccy, amountMinor);
        checkDebitLimits(from, ccy, amountMinor);
        RiskPorts.RiskDecision d = assess("WALLET_MERCHANT_PAYMENT", channel, from, "merchant:" + merchantId, merchantId, null, ccy, amountMinor);

        String ref = transferRef(clientRef);
        FeeQuote q = fees.quote(merchantId, "WALLET", ccy, amountMinor);
        LedgerService.PostedEntry e = merchantFunds.recordWalletCollection(ref, merchantId, from.getCustomerRef(), ccy, q,
                merchants.fundsPolicy(merchantId), from.getCustomerRef());
        WalletTransfer t = record(ref, "WALLET_MERCHANT_PAYMENT", channel, from.getCustomerRef(), "merchant:" + merchantId, merchantId,
                null, ccy, amountMinor, 0, 0, 0, d.action(), e.entryRef(), from.getMsisdn());
        notify(t, from.getMsisdn(), null);
        return result(t, from.getCustomerRef());
    }

    // ── Withdraw to bank / other wallet ──────────────────────────────────

    /**
     * Funds leave the wallet the moment the rail is called (posted first, so
     * the customer cannot spend them twice). A rail REJECTED reverses the
     * entry; UNKNOWN is kept as sent and escalated for confirmation.
     */
    @Transactional
    public TransferResult withdraw(String fromMsisdn, String currency, long amountMinor, String pin, String railCode,
                                   String cashAccount, PayoutRail.PayoutRequest destinationTemplate, String channel, String clientRef) {
        String ccy = AccountKey.normaliseCurrency(currency);
        CustomerProfile from = kyc.requireActive(fromMsisdn);
        requireWallet(from.getCustomerRef(), ccy);
        verifyPin("CUSTOMER", from.getCustomerRef(), pin);
        positive(amountMinor);
        TariffMath.Charges ch = charges("WITHDRAWAL", ccy, amountMinor);
        checkDebitLimits(from, ccy, amountMinor + ch.customerFeeMinor());
        RiskPorts.RiskDecision d = assess("WITHDRAWAL", channel, from, destinationTemplate.accountNumber() != null
                ? destinationTemplate.accountNumber() : destinationTemplate.mobileNumber(), null, null, ccy, amountMinor);

        String ref = transferRef(clientRef);
        LedgerService.PostedEntry e = ledger.post(WalletPostingRules.withdrawal(ref, from.getCustomerRef(), ccy, amountMinor,
                ch.customerFeeMinor(), cashAccount), from.getCustomerRef());
        PayoutRail rail = rails.require(railCode);
        PayoutRail.PayoutResult r = rail.submit(new PayoutRail.PayoutRequest(ref, ccy, amountMinor,
                destinationTemplate.beneficiaryName() != null ? destinationTemplate.beneficiaryName() : from.getFirstName() + " " + from.getLastName(),
                destinationTemplate.bankName(), destinationTemplate.bankCode(), destinationTemplate.branchCode(),
                destinationTemplate.accountNumber(), destinationTemplate.mobileNumber(), "PayPort withdrawal " + ref));
        String status = switch (r.status()) {
            case "COMPLETED" -> "COMPLETED";
            case "ACCEPTED" -> "COMPLETED";
            case "UNKNOWN" -> "PENDING_CONFIRMATION";
            default -> "FAILED";
        };
        if ("FAILED".equals(status)) {
            ledger.reverse(e.entryRef(), "Rail rejected withdrawal: " + r.message(), "SYSTEM:wallet");
        } else if ("PENDING_CONFIRMATION".equals(status)) {
            breaks.report("WALLET", "WITHDRAWAL_UNCONFIRMED", ref, ccy, amountMinor, null,
                    "Rail " + railCode + " gave no definitive answer: " + r.message());
        }
        WalletTransfer t = record(ref, "WITHDRAWAL", channel, from.getCustomerRef(), railCode + ":" + r.railReference(), null, null,
                ccy, amountMinor, ch.customerFeeMinor(), 0, 0, d.action(), e.entryRef(), from.getMsisdn());
        t.setStatus(status);
        t.setExternalRef(r.railReference());
        if ("FAILED".equals(status)) t.setFailureReason(r.message());
        transfers.save(t);
        notify(t, from.getMsisdn(), null);
        return result(t, from.getCustomerRef());
    }

    public List<WalletTransfer> history(String customerRef, int limit) {
        return transfers.history(customerRef, PageRequest.of(0, Math.min(limit, 200)));
    }

    // ── Shared checks (also used by AgentService and FxService) ──────────

    public void checkDebitLimits(CustomerProfile c, String ccy, long totalDebitMinor) {
        KycTierLimit l = kyc.tierLimit(c.getKycTier(), ccy);
        if (totalDebitMinor > l.getMaxTxnMinor())
            throw PspException.unprocessable("TIER_TXN_LIMIT", "This exceeds your " + c.getKycTier() + " limit per transaction. Upgrade your KYC to send more.");
        long[] day = sumDebits(c.getCustomerRef(), ccy, LocalDate.now().atStartOfDay());
        if (day[0] + totalDebitMinor > l.getDailyDebitMinor())
            throw PspException.unprocessable("TIER_DAILY_LIMIT", "This exceeds your daily limit.");
        if (day[1] + 1 > l.getDailyCount())
            throw PspException.unprocessable("TIER_DAILY_COUNT", "You have reached today's number of transactions.");
        long[] month = sumDebits(c.getCustomerRef(), ccy, LocalDate.now().withDayOfMonth(1).atStartOfDay());
        if (month[0] + totalDebitMinor > l.getMonthlyDebitMinor())
            throw PspException.unprocessable("TIER_MONTHLY_LIMIT", "This exceeds your monthly limit.");
    }

    public void checkCreditLimit(CustomerProfile c, String ccy, long creditMinor) {
        KycTierLimit l = kyc.tierLimit(c.getKycTier(), ccy);
        long bal = balance(c.getCustomerRef(), ccy);
        if (bal + creditMinor > l.getMaxBalanceMinor())
            throw PspException.unprocessable("RECIPIENT_BALANCE_LIMIT", "The recipient's wallet cannot hold this much at their KYC tier.");
    }

    public RiskPorts.RiskDecision assess(String type, String channel, CustomerProfile from, String counterparty, Long merchantId,
                                         Long agentId, String ccy, long amountMinor) {
        RiskPorts.RiskDecision d = risk.assess(new RiskPorts.RiskContext(type, channel, "CUSTOMER", from.getCustomerRef(), counterparty,
                merchantId, agentId, ccy, amountMinor, from.getMsisdn(), null, null));
        if (d.blocked()) throw PspException.forbidden("RISK_BLOCKED", "This transaction cannot be completed. Please contact support.");
        return d;
    }

    public TariffMath.Charges charges(String operation, String ccy, long amountMinor) {
        List<TariffMath.Band> bands = tariffs.findByOperationAndCurrencyAndStatus(operation, ccy, "ACTIVE").stream()
                .map(t -> new TariffMath.Band(t.getBandMinMinor(), t.getBandMaxMinor(), t.getCustomerFeeFixedMinor(),
                        t.getCustomerFeeBps(), t.getAgentCommissionFixedMinor(), t.getAgentCommissionBps()))
                .sorted(Comparator.comparingLong(TariffMath.Band::minMinor)).toList();
        return TariffMath.charges(bands, amountMinor);
    }

    public long transferTax(String ccy, long amountMinor) {
        Optional<TaxRate> imtt = fees.activeTax(PostingTaxCodes.IMTT, ccy, LocalDateTime.now());
        return imtt.map(t -> TariffMath.transferTax(amountMinor, t.getRateBps(), t.getThresholdMinor(), t.getCapMinor())).orElse(0L);
    }

    long[] sumDebits(String customerRef, String ccy, LocalDateTime since) {
        List<Object[]> rows = transfers.debitsSince(customerRef, ccy, since, DEBIT_TYPES);
        if (rows == null || rows.isEmpty()) return new long[2];
        return new long[]{((Number) rows.get(0)[0]).longValue(), ((Number) rows.get(0)[1]).longValue()};
    }

    void requireWallet(String customerRef, String ccy) {
        Wallet w = wallets.findByOwnerTypeAndOwnerRefAndCurrency("CUSTOMER", customerRef, ccy)
                .orElseGet(() -> open("CUSTOMER", customerRef, ccy));
        if (!"ACTIVE".equals(w.getStatus())) throw PspException.forbidden("WALLET_" + w.getStatus(), "This wallet is " + w.getStatus().toLowerCase());
    }

    WalletTransfer record(String ref, String type, String channel, String fromRef, String toRef, Long merchantId, Long agentId,
                          String ccy, long amount, long fee, long tax, long commission, String risk, String entryRef, String initiatedBy) {
        WalletTransfer t = new WalletTransfer();
        t.setTransferRef(ref);
        t.setType(type);
        t.setChannel(channel == null ? "API" : channel.toUpperCase());
        t.setFromRef(fromRef);
        t.setToRef(toRef);
        t.setMerchantId(merchantId);
        t.setAgentId(agentId);
        t.setCurrency(ccy);
        t.setAmountMinor(amount);
        t.setFeeMinor(fee);
        t.setTaxMinor(tax);
        t.setCommissionMinor(commission);
        t.setStatus("COMPLETED");
        t.setRiskDecision(risk);
        t.setJournalEntryRef(entryRef);
        t.setInitiatedBy(initiatedBy);
        return transfers.save(t);
    }

    void notify(WalletTransfer t, String payerMsisdn, String payeeMsisdn) {
        Map<String, Object> p = new HashMap<>();
        p.put("transferRef", t.getTransferRef());
        p.put("type", t.getType());
        p.put("status", t.getStatus());
        p.put("currency", t.getCurrency());
        p.put("amountMinor", t.getAmountMinor());
        p.put("feeMinor", t.getFeeMinor());
        p.put("taxMinor", t.getTaxMinor());
        p.put("payerMsisdn", payerMsisdn);
        if (payeeMsisdn != null) p.put("payeeMsisdn", payeeMsisdn);
        if (t.getAgentId() != null) p.put("agentId", t.getAgentId());
        if (t.getMerchantId() != null) p.put("merchantId", t.getMerchantId());
        outbox.publish(EventTypes.WALLET_TRANSFER_COMPLETED, "WALLET_TRANSFER", t.getTransferRef(), p);
    }

    TransferResult result(WalletTransfer t, String customerRef) {
        return new TransferResult(t.getTransferRef(), t.getStatus(), t.getAmountMinor(), t.getFeeMinor(), t.getTaxMinor(),
                t.getCurrency(), balance(customerRef, t.getCurrency()), t.getRiskDecision());
    }

    static String transferRef(String clientRef) {
        return Refs.newRef("wtx");
    }

    static void positive(long amountMinor) {
        if (amountMinor <= 0) throw PspException.badRequest("INVALID_AMOUNT", "Amount must be greater than zero");
    }
}
