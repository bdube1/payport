package zw.co.payport.gateway.settlement;

import org.springframework.stereotype.Component;
import zw.co.payport.gateway.markets.MarketRegistry;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Chooses the payout file format for a currency from its market's bank configuration. */
@Component
public class PayoutFormats {

    private final Map<String, PayoutFileAdapter> byFormat;
    private final MarketRegistry markets;

    public PayoutFormats(List<PayoutFileAdapter> adapters, MarketRegistry markets) {
        this.byFormat = adapters.stream().collect(Collectors.toMap(PayoutFileAdapter::format, Function.identity()));
        this.markets = markets;
    }

    public boolean supports(String format) {
        return byFormat.containsKey(format);
    }

    public PayoutFileAdapter forCurrency(String currency) {
        String format = markets.requireForCurrency(currency).bank().payoutFormat();
        PayoutFileAdapter a = byFormat.get(format);
        if (a == null) throw new IllegalStateException("No adapter for payout format " + format);
        return a;
    }
}
