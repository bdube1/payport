package zw.co.payport.gateway.billers;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import zw.co.payport.gateway.service.adaptor.esolutions.bulawayo.BulawayoBillAdaptor;
import zw.co.payport.gateway.service.adaptor.esolutions.moonlight.MoonlightAdaptor;
import zw.co.payport.gateway.service.adaptor.esolutions.nyaradzo.NyaradzoAdaptor;
import zw.co.payport.gateway.service.adaptor.esolutions.zesa.ZesaTokenAdaptor;
import zw.co.payport.gateway.service.adaptor.esolutions.zol.ZolPaymentAdaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The eSolutions billers expressed through the biller framework. ZESA and Nyaradzo are the
 * fully specified examples (account rules, currency rules, payability); the others declare
 * conservative formats until their specs are confirmed.
 */
@Configuration
public class EsolutionsBillers {

    /** Same biller outside Spring (contract tests). */
    public BillerAdaptor zesaBillerForTest(ZesaTokenAdaptor zesa) {
        return zesaBiller(zesa);
    }

    @Bean
    BillerAdaptor zesaBiller(ZesaTokenAdaptor zesa) {
        BillerAdaptor.Profile profile = new BillerAdaptor.Profile("ZESA", "ZESA prepaid electricity", "ELECTRICITY", "Meter number",
                "\\d{11}", "A ZESA meter number has exactly 11 digits.", List.of("USD", "ZWG"), 1, 0, true);
        return new BillerAdaptor() {
            public Profile profile() { return profile; }

            @Override
            public String validateAccount(String account) {
                return ZesaTokenAdaptor.validateMeter(account);   // the adaptor's own rule (UAT test 16)
            }

            public Account lookup(String account, String currency, String sourcePhone) throws Exception {
                ZesaTokenAdaptor.ZesaCustomerInfo info = zesa.getCustomerInfo(account.trim(), currency == null ? "USD" : currency);
                boolean payable = currency == null || info.isPaymentCurrencyAllowed(currency);
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("address", info.customerAddress());
                d.put("meterCurrency", info.meterCurrency());
                return new Account(info.meterNumber(), info.customerName(), info.meterCurrency(), info.getAllowedPaymentCurrencies(), payable,
                        payable ? null : "This meter only accepts " + String.join(" or ", info.getAllowedPaymentCurrencies()), d);
            }
        };
    }

    @Bean
    BillerAdaptor nyaradzoBiller(NyaradzoAdaptor nyaradzo) {
        BillerAdaptor.Profile profile = new BillerAdaptor.Profile("NYARADZO", "Nyaradzo funeral policy", "INSURANCE", "Policy number",
                "[A-Za-z0-9/-]{4,30}", "Use the policy number exactly as it appears on your policy documents.", List.of("USD", "ZWG"), 1, 0, true);
        return new BillerAdaptor() {
            public Profile profile() { return profile; }

            public Account lookup(String account, String currency, String sourcePhone) throws Exception {
                NyaradzoAdaptor.NyaradzoCustomerInfo info = nyaradzo.getCustomerInfo(account.trim(), sourcePhone);
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("policyStatus", info.policyStatus());
                d.put("balanceMinor", info.balanceCents());
                d.put("monthlyPremiumMinor", info.monthlyPremiumCents());
                d.put("additionalInfo", info.additionalInfo());
                boolean currencyOk = currency == null || info.currency() == null || info.currency().equalsIgnoreCase(currency);
                boolean payable = info.isPolicyActive() && currencyOk;
                String why = !info.isPolicyActive() ? "This policy is not active (" + info.policyStatus() + ")"
                        : !currencyOk ? "This policy is paid in " + info.currency() : null;
                return new Account(info.policyNumber(), info.holderName(), info.currency(),
                        info.currency() == null ? profile.currencies() : List.of(info.currency().toUpperCase()), payable, why, d);
            }
        };
    }

    @Bean
    BillerAdaptor zolBiller(ZolPaymentAdaptor zol) {
        BillerAdaptor.Profile profile = new BillerAdaptor.Profile("ZOL", "ZOL internet", "INTERNET", "Account number",
                "[A-Za-z0-9-]{3,30}", "Use the account number on your ZOL invoice.", List.of("USD", "ZWG"), 1, 0, true);
        return simple(profile, (a, c, p) -> {
            ZolPaymentAdaptor.ZolCustomerInfo i = zol.getCustomerInfo(a.trim());
            return new String[]{i.accountNumber(), i.customerName()};
        });
    }

    @Bean
    BillerAdaptor bulawayoBiller(BulawayoBillAdaptor byo) {
        BillerAdaptor.Profile profile = new BillerAdaptor.Profile("BULAWAYO", "City of Bulawayo", "MUNICIPAL", "Account number",
                "[A-Za-z0-9-]{3,30}", "Use the account number on your council bill.", List.of("USD", "ZWG"), 1, 0, true);
        return simple(profile, (a, c, p) -> {
            BulawayoBillAdaptor.BulawayoCustomerInfo i = byo.getCustomerInfo(a.trim(), c == null ? "USD" : c);
            return new String[]{i.accountNumber(), i.customerName()};
        });
    }

    @Bean
    BillerAdaptor moonlightBiller(MoonlightAdaptor moonlight) {
        BillerAdaptor.Profile profile = new BillerAdaptor.Profile("MOONLIGHT", "Moonlight funeral policy", "INSURANCE", "Policy number",
                "[A-Za-z0-9/-]{4,30}", "Use the policy number exactly as it appears on your policy documents.", List.of("USD", "ZWG"), 1, 0, true);
        return simple(profile, (a, c, p) -> {
            MoonlightAdaptor.MoonlightCustomerInfo i = moonlight.getCustomerInfo(a.trim(), p);
            return new String[]{i.policyNumber(), i.holderName()};
        });
    }

    interface Lookup { String[] apply(String account, String currency, String phone) throws Exception; }

    private static BillerAdaptor simple(BillerAdaptor.Profile profile, Lookup lookup) {
        return new BillerAdaptor() {
            public Profile profile() { return profile; }

            public Account lookup(String account, String currency, String sourcePhone) throws Exception {
                String[] r = lookup.apply(account, currency, sourcePhone);
                boolean ok = currency == null || profile.currencies().contains(currency.toUpperCase());
                return new Account(r[0], r[1], currency, profile.currencies(), ok, ok ? null : profile.name() + " does not accept " + currency, Map.of());
            }
        };
    }
}
