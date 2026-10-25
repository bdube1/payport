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

    /** Thrown when the biller did not answer (transport): the customer is told to try again shortly. */
    public static class BillerUnavailableException extends RuntimeException {
        public BillerUnavailableException(String m) { super(m); }
    }

    /**
     * The one account lookup every channel uses (web, app, USSD, WhatsApp): format check, provider health, the
     * biller's own answer. IllegalArgumentException = the account is wrong; BillerUnavailableException = no answer;
     * any other RuntimeException carries the biller's words.
     */
    public BillerAdaptor.Account lookupAccount(String code, String accountNumber, String currency, String sourcePhone) {
        BillerAdaptor biller = billers.find(code).orElseThrow(() -> new IllegalArgumentException("Unknown biller " + code));
        String invalid = biller.validateAccount(accountNumber);
        if (invalid != null) throw new IllegalArgumentException(invalid);
        long started = System.currentTimeMillis();
        health.acquire(biller.profile().code(), biller.profile().name());
        try {
            BillerAdaptor.Account a = biller.lookup(accountNumber.trim().replaceAll("\\s+", ""), currency, sourcePhone);
            health.success(biller.profile().code(), System.currentTimeMillis() - started);
            return a;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            boolean transport = zw.co.payport.gateway.payments.SubmissionErrors.isOutcomeUnknown(e) || e instanceof java.io.IOException;
            if (transport) health.failure(biller.profile().code(), System.currentTimeMillis() - started, e.getClass().getSimpleName());
            else health.success(biller.profile().code(), System.currentTimeMillis() - started);
            log.warn("[BILLERS] {} lookup failed: {}", code, e.getMessage());
            if (transport) throw new BillerUnavailableException(biller.profile().name() + " is not responding. Please try again shortly.");
            throw e instanceof RuntimeException r ? r : new IllegalStateException(e.getMessage());
        }
    }

    @PostMapping("/{code}/lookup")
    public ResponseEntity<ApiResponse<Map<String, Object>>> lookup(@PathVariable String code, @RequestBody LookupBody b, HttpServletRequest req) {
        if (billers.find(code).isEmpty()) return ResponseEntity.status(404).body(ApiResponse.error("UNKNOWN_BILLER", "Unknown biller " + code, rid(req)));
        try {
            BillerAdaptor.Account a = lookupAccount(code, b == null ? null : b.accountNumber(), b == null ? null : b.currency(), b == null ? null : b.sourcePhone());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("biller", billers.find(code).get().profile().code());
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
        } catch (BillerUnavailableException e) {
            return ResponseEntity.status(503).body(ApiResponse.error("BILLER_UNAVAILABLE", e.getMessage(), rid(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.status(422).body(ApiResponse.error("LOOKUP_FAILED", e.getMessage(), rid(req)));
        }
    }

    private static String rid(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
