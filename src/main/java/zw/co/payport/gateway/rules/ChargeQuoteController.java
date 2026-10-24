package zw.co.payport.gateway.rules;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.co.payport.gateway.entity.ProductType;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.Web;
import zw.co.payport.gateway.repository.ProductTypeRepository;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The customer charge for a product payment, shown before the customer pays (app, web, USSD, WhatsApp; guests too). */
@RestController
public class ChargeQuoteController {

    private final ProductTypeRepository products;
    @Autowired(required = false) PartyRules rules;

    public ChargeQuoteController(ProductTypeRepository products) {
        this.products = products;
    }

    public PartyRules.Charge quote(String productCode, String currency, long amountCents, String channel) {
        if (rules == null || amountCents <= 0) return PartyRules.Charge.NONE;
        ProductType p = products.findByCodeAndIsActiveTrue(productCode == null ? "" : productCode.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalArgumentException("Unknown product"));
        String ch = zw.co.payport.gateway.enums.TransactionChannel.fromString(channel == null ? "WEB" : channel).name();
        return PartyRules.charge(rules.match("WALLET", List.of(p.getCode(), TransactionOrchestratorService.productGroup(p)),
                currency.trim().toUpperCase(Locale.ROOT), ch), amountCents);
    }

    @GetMapping("/api/v1/charges/quote")
    public ResponseEntity<ApiResponse<Map<String, Object>>> quoteRoute(@RequestParam String product, @RequestParam String currency,
                                                                       @RequestParam long amountCents, @RequestParam(required = false) String channel,
                                                                       HttpServletRequest req) {
        return Web.run(() -> {
            PartyRules.Charge c = quote(product, currency, amountCents, channel);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("amountCents", amountCents);
            out.put("chargeCents", c.charge());
            out.put("taxCents", c.tax());
            out.put("totalCents", amountCents + c.total());
            out.put("currency", currency.trim().toUpperCase(Locale.ROOT));
            return out;
        }, req);
    }
}
