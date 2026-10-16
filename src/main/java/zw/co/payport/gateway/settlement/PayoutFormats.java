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
        for (MarketRegistry.Market m : markets.all()) {
            if (!byFormat.containsKey(m.bank().payoutFormat())) {
                throw new IllegalStateException(m.country() + ": no adapter for payout format " + m.bank().payoutFormat());
            }
        }
    }

    public PayoutFileAdapter forCurrency(String currency) {
        return byFormat.get(markets.requireForCurrency(currency).bank().payoutFormat());
    }
}
