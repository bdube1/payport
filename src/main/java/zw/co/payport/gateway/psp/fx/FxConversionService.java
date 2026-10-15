package zw.co.payport.gateway.psp.fx;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.psp.common.Refs;
import zw.co.payport.gateway.psp.kyc.CustomerProfile;
import zw.co.payport.gateway.psp.kyc.KycService;
import zw.co.payport.gateway.psp.ledger.LedgerService;
import zw.co.payport.gateway.psp.ledger.domain.WalletPostingRules;
import zw.co.payport.gateway.psp.wallet.WalletService;

import java.util.List;

/**
 * Customer USD <-> ZWG conversions between their own wallets. Two
 * single-currency ledger entries in one database transaction, at the quoted
 * rate, with the spread booked as FX revenue.
 */
@Service
public class FxConversionService {

    private final FxRateService fx;
    private final FxConversionRepository conversions;
    private final LedgerService ledger;
    private final KycService kyc;
    private final WalletService wallets;

    public FxConversionService(FxRateService fx, FxConversionRepository conversions, LedgerService ledger, KycService kyc,
                               WalletService wallets) {
        this.fx = fx;
        this.conversions = conversions;
        this.ledger = ledger;
        this.kyc = kyc;
        this.wallets = wallets;
    }

    /** Customer converts between their own USD and ZWG wallets. */
    @Transactional
    public FxConversion convert(String msisdn, String from, String to, long amountMinor, String pin, String channel) {
        CustomerProfile c = kyc.requireActive(msisdn);
        wallets.verifyPin("CUSTOMER", c.getCustomerRef(), pin);
        FxRateService.Quote q = fx.quote(from, to, amountMinor);
        wallets.checkDebitLimits(c, q.from(), amountMinor);
        wallets.checkCreditLimit(c, q.to(), q.toAmountMinor());
        wallets.assess("FX", channel, c, "fx", null, null, q.from(), amountMinor);

        String ref = Refs.newRef("fxc");
        WalletPostingRules.FxLegs legs = WalletPostingRules.conversion(ref, c.getCustomerRef(), q.from(), amountMinor, q.to(),
                q.midAmountMinor(), q.toAmountMinor());
        LedgerService.PostedEntry sell = ledger.post(legs.sell(), c.getCustomerRef());
        LedgerService.PostedEntry buy = ledger.post(legs.buy(), c.getCustomerRef());

        FxConversion x = new FxConversion();
        x.setConversionRef(ref);
        x.setCustomerRef(c.getCustomerRef());
        x.setFromCcy(q.from());
        x.setToCcy(q.to());
        x.setFromAmountMinor(amountMinor);
        x.setToAmountMinor(q.toAmountMinor());
        x.setMidAmountMinor(q.midAmountMinor());
        x.setCustomerRate(q.customerRate());
        x.setMidRate(q.midRate());
        x.setRateRef(q.rateRef());
        x.setSellEntryRef(sell.entryRef());
        x.setBuyEntryRef(buy.entryRef());
        return conversions.save(x);
    }

    public List<FxConversion> history(String customerRef) {
        return conversions.findTop50ByCustomerRefOrderByCreatedAtDesc(customerRef);
    }
}
