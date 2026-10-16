package zw.co.payport.gateway.markets;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.payport.gateway.model.response.ApiResponse;

import java.util.List;
import java.util.Map;

/** Read-only view of the configured markets (status, entity, currencies, bank formats). */
@RestController
@RequestMapping("/api/v1/admin/markets")
@PreAuthorize("hasAnyRole('ADMIN','OPS','FINANCE','COMPLIANCE')")
public class MarketController {

    private final MarketRegistry markets;

    public MarketController(MarketRegistry markets) {
        this.markets = markets;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest req) {
        String rid = req.getHeader("X-Request-ID");
        return ResponseEntity.ok(ApiResponse.success(markets.summary(), rid != null ? rid : java.util.UUID.randomUUID().toString()));
    }
}
