package zw.co.payport.gateway.markets;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.Web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Countries and currencies PayPort serves, for the drop-downs in the customer and merchant apps (public, read-only). */
@RestController
public class PublicMarketsController {

    private final MarketRegistry markets;

    public PublicMarketsController(MarketRegistry markets) {
        this.markets = markets;
    }

    @GetMapping("/api/v1/markets")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest req) {
        return Web.run(() -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (MarketRegistry.Market m : markets.all()) {
                if ("SUSPENDED".equals(m.status())) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("country", m.country());
                row.put("name", m.name());
                row.put("phoneCountryCode", m.phoneCountryCode());
                row.put("live", m.live());
                List<Map<String, Object>> ccys = new ArrayList<>();
                for (MarketRegistry.Currency c : m.currencies()) {
                    if (!c.enabled()) continue;
                    ccys.add(Map.of("code", c.code(), "symbol", c.symbol() == null ? c.code() : c.symbol(), "live", m.live() && c.live()));
                }
                row.put("currencies", ccys);
                out.add(row);
            }
            out.sort((a, b) -> String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name"))));
            return out;
        }, req);
    }
}
