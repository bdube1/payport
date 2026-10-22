package zw.co.payport.gateway.billers;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.integration.ProviderHealthService;
import zw.co.payport.gateway.integration.ProviderUnavailableException;
import zw.co.payport.gateway.model.response.ApiResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Biller catalogue and account lookup for every channel (web, app, USSD). Public like the
 * existing /api/v1/bills/customer-info (customers look up before paying), rate-limited per IP.
 * Lookups never move money.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/billers")
public class BillerController {

    private final BillerRegistry billers;
    private final ProviderHealthService health;

    public BillerController(BillerRegistry billers, ProviderHealthService health) {
        this.billers = billers;
        this.health = health;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<BillerAdaptor.Profile>>> catalogue(HttpServletRequest req) {
        return ResponseEntity.ok(ApiResponse.success(billers.catalogue(), rid(req)));
    }

    public record LookupBody(String accountNumber, String currency, String sourcePhone) {}

    @PostMapping("/{code}/lookup")
    public ResponseEntity<ApiResponse<Map<String, Object>>> lookup(@PathVariable String code, @RequestBody LookupBody b, HttpServletRequest req) {
        BillerAdaptor biller = billers.find(code).orElse(null);
        if (biller == null) return ResponseEntity.status(404).body(ApiResponse.error("UNKNOWN_BILLER", "Unknown biller " + code, rid(req)));
        String invalid = biller.validateAccount(b == null ? null : b.accountNumber());
        if (invalid != null) return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_ACCOUNT", invalid, rid(req)));
        long started = System.currentTimeMillis();
        try {
            health.acquire(biller.profile().code(), biller.profile().name());
            BillerAdaptor.Account a = biller.lookup(b.accountNumber().trim().replaceAll("\\s+", ""), b.currency(), b.sourcePhone());
            health.success(biller.profile().code(), System.currentTimeMillis() - started);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("biller", biller.profile().code());
            m.put("accountNumber", a.accountNumber());
            m.put("holderName", a.holderName());
            m.put("currency", a.currency());
            m.put("acceptedCurrencies", a.acceptedCurrencies());
            m.put("payable", a.payable());
            m.put("notPayableReason", a.notPayableReason());
            m.put("details", a.details());
            return ResponseEntity.ok(ApiResponse.success(m, rid(req)));
        } catch (ProviderUnavailableException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_ACCOUNT", e.getMessage(), rid(req)));
        } catch (Exception e) {
            // The biller answered "not found"/declined (a RuntimeException with its words) or the call failed.
            boolean transport = zw.co.payport.gateway.payments.SubmissionErrors.isOutcomeUnknown(e) || e instanceof java.io.IOException;
            if (transport) health.failure(biller.profile().code(), System.currentTimeMillis() - started, e.getClass().getSimpleName());
            else health.success(biller.profile().code(), System.currentTimeMillis() - started);
            log.warn("[BILLERS] {} lookup failed: {}", code, e.getMessage());
            return ResponseEntity.status(transport ? 503 : 422).body(ApiResponse.error(transport ? "BILLER_UNAVAILABLE" : "LOOKUP_FAILED",
                    transport ? biller.profile().name() + " is not responding. Please try again shortly." : e.getMessage(), rid(req)));
        }
    }

    private static String rid(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
