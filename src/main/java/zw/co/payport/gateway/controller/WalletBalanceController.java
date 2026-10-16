package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.service.adaptor.econet.EconetAirtimeAdaptor;
import zw.co.payport.gateway.service.adaptor.telecel.TelecelAirtimeAdaptor;
import zw.co.payport.gateway.service.adaptor.telecel.TelecelSoapClient;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Wallet/Float Balance Controller
 * GET /api/v1/admin/wallet-balances        - All provider balances
 * GET /api/v1/admin/wallet-balances/{code} - Single provider balance
 */
@Slf4j
@RestController
@org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('OPS','ADMIN','FINANCE','COMPLIANCE')")
@RequestMapping("/api/v1/admin/wallet-balances")
@RequiredArgsConstructor
public class WalletBalanceController {

    private final RestTemplate restTemplate;
    private final EconetAirtimeAdaptor econetAdaptor;
    private final TelecelAirtimeAdaptor telecelAdaptor;

    @Value("${payport.netone.base-url}")
    private String netoneBaseUrl;

    @Value("${payport.netone.access-code}")
    private String netoneAccessCode;

    @Value("${payport.netone.access-password}")
    private String netoneAccessPassword;

    @Value("${payport.econet.endpoint:}")
    private String econetEndpoint;

    @Value("${payport.econet.company-msisdn:}")
    private String econetCompanyMsisdn;

    /**
     * Get all provider balances in one call.
     *
     * Runs all five provider checks (NetOne, Econet USD+ZWG, Telecel
     * USD+ZWG) in PARALLEL, not sequentially, and each one is resolved
     * against its own 15s timeout independently -- a slow or hanging
     * provider only affects its own entry, not the other four. Combined
     * with RestTemplateConfig's connect/read timeouts (also previously
     * unbounded), a call that would have hung indefinitely in the
     * background now fails fast and predictably.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAllBalances(HttpServletRequest req) {
        String requestId = requestId(req);
        log.info("[WALLET] Fetching all provider balances, requestId: {}", requestId);

        Map<String, Object> balances = new LinkedHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(5);

        try {
            CompletableFuture<Map<String, Object>> netone = CompletableFuture.supplyAsync(this::fetchNetOneBalance, pool);
            CompletableFuture<Map<String, Object>> econetUsd = CompletableFuture.supplyAsync(() -> fetchEconetBalance("USD"), pool);
            CompletableFuture<Map<String, Object>> econetZwg = CompletableFuture.supplyAsync(() -> fetchEconetBalance("ZWG"), pool);
            CompletableFuture<Map<String, Object>> telecelUsd = CompletableFuture.supplyAsync(() -> fetchTelecelBalance("USD"), pool);
            CompletableFuture<Map<String, Object>> telecelZwg = CompletableFuture.supplyAsync(() -> fetchTelecelBalance("ZWG"), pool);

            // Was CompletableFuture.allOf(...).get(25, SECONDS) -- a
            // single slow provider timing out threw for the WHOLE
            // response, meaning four providers that succeeded fine still
            // came back as nothing at all. Each one is now resolved
            // independently, so one slow or failing provider only
            // affects its own entry, not the other four.
            balances.put("NETONE_USD", resolveWithTimeout(netone, "NETONE", "USD"));
            balances.put("ECONET_USD", resolveWithTimeout(econetUsd, "ECONET", "USD"));
            balances.put("ECONET_ZWG", resolveWithTimeout(econetZwg, "ECONET", "ZWG"));
            balances.put("TELECEL_USD", resolveWithTimeout(telecelUsd, "TELECEL", "USD"));
            balances.put("TELECEL_ZWG", resolveWithTimeout(telecelZwg, "TELECEL", "ZWG"));

            log.info("[WALLET] Successfully fetched {} provider balances", balances.size());
            return ResponseEntity.ok(ApiResponse.success(balances, requestId));

        } catch (Exception e) {
            log.error("[WALLET] Unexpected error fetching all balances: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("INTERNAL_ERROR",
                            "Failed to fetch wallet balances: " + e.getMessage(),
                            requestId));
        } finally {
            // Always shut down -- a leaked thread pool on every request to
            // this endpoint would exhaust threads over time even if each
            // individual request otherwise succeeds.
            pool.shutdown();
        }
    }

    /** Each provider gets its own timeout window rather than sharing one all-or-nothing ceiling -- a slow NetOne no longer hides a healthy Econet. Set to 40s, safely above RestTemplateConfig's own ~30s worst case (10s connect + 20s read) -- this must never be shorter than what the underlying HTTP call is itself allowed to take, or a call that's still legitimately working gets reported as failed before it was ever actually going to fail on its own. */
    private Map<String, Object> resolveWithTimeout(CompletableFuture<Map<String, Object>> future, String provider, String currency) {
        try {
            return future.get(40, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("[WALLET] {} ({}) balance fetch did not complete in time: {}", provider, currency, e.getMessage());
            Map<String, Object> result = new HashMap<>();
            result.put("provider", provider);
            result.put("currency", currency);
            result.put("balance", null);
            result.put("status", "ERROR");
            result.put("message", "Timed out waiting for a response");
            result.put("fetchedAt", java.time.LocalDateTime.now().toString());
            return result;
        }
    }

    /**
     * Econet company float balance -- queried in both USD and ZWG, since
     * the adaptor genuinely supports both via separate credentials per
     * currency (confirmed in EconetAirtimeAdaptor -- this isn't guessed).
     * NetOne is genuinely USD-only at the API level (no currency
     * parameter exists to pass). Telecel supports both too, per separate
     * USD/ZWG agent accounts -- see fetchTelecelBalance below.
     */
    private Map<String, Object> fetchEconetBalance(String currency) {
        Map<String, Object> result = new HashMap<>();
        result.put("provider", "ECONET");
        result.put("currency", currency);
        result.put("fetchedAt", java.time.LocalDateTime.now().toString());

        if (econetCompanyMsisdn == null || econetCompanyMsisdn.isBlank()) {
            result.put("balance", null);
            result.put("status", "NOT_CONFIGURED");
            result.put("message", "Set ECONET_COMPANY_MSISDN to enable this");
            return result;
        }

        try {
            EconetAirtimeAdaptor.BalanceResult bal = econetAdaptor.getAccountBalance(econetCompanyMsisdn, currency);
            Integer amountCents = bal.getCompanyAccountAmount();
            if (amountCents != null) {
                result.put("balance", amountCents / 100.0);
                result.put("status", "OK");
            } else {
                result.put("balance", null);
                result.put("status", "ERROR");
                result.put("message", "Econet returned no balance figure");
            }
        } catch (Exception e) {
            log.error("[WALLET] Econet balance fetch failed ({}): {}", currency, e.getMessage(), e);
            result.put("balance", null);
            result.put("status", "ERROR");
            result.put("message", e.getMessage());
        }
        return result;
    }

    /** Telecel agent wallet balance -- no extra config needed beyond what
     *  the airtime adaptor already uses (agent code + MPIN). */
    /**
     * Telecel float balance -- confirmed to support both USD and ZWG as
     * separate agent accounts (not a single account with a currency
     * parameter). ZWG credentials aren't issued yet, so that side stays
     * honestly NOT_CONFIGURED until Telecel provides them, rather than
     * reusing the USD account and mislabeling its balance as ZWG.
     */
    private Map<String, Object> fetchTelecelBalance(String currency) {
        Map<String, Object> result = new HashMap<>();
        result.put("provider", "TELECEL");
        result.put("currency", currency);
        result.put("fetchedAt", java.time.LocalDateTime.now().toString());

        try {
            TelecelSoapClient.TelecelBalanceResult bal = telecelAdaptor.getBalance(currency);
            if (bal == null) {
                result.put("balance", null);
                result.put("status", "NOT_CONFIGURED");
                result.put("message", "ZWG".equalsIgnoreCase(currency)
                        ? "Set TELECEL_ZWG_AGENT_CODE / TELECEL_ZWG_MPIN once Telecel issues ZWG credentials"
                        : "Telecel credentials not configured");
                return result;
            }
            if (bal.isSuccess() && bal.getBalanceRaw() != null) {
                try {
                    result.put("balance", Double.parseDouble(bal.getBalanceRaw()));
                    result.put("status", "OK");
                } catch (NumberFormatException nfe) {
                    result.put("balance", null);
                    result.put("status", "ERROR");
                    result.put("message", "Could not parse Telecel balance: " + bal.getBalanceRaw());
                }
            } else {
                result.put("balance", null);
                result.put("status", "ERROR");
                result.put("message", bal.getResultDescription() != null ? bal.getResultDescription() : "Telecel balance query failed");
            }
        } catch (Exception e) {
            log.error("[WALLET] Telecel balance fetch failed ({}): {}", currency, e.getMessage(), e);
            result.put("balance", null);
            result.put("status", "ERROR");
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * Get single provider balance. currency is only meaningful for Econet
     * (the only one of the three whose API actually supports both) --
     * ignored for NetOne/Telecel since their APIs have no currency
     * parameter to pass it to.
     */
    @GetMapping("/{providerCode}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getBalance(
            @PathVariable String providerCode,
            @RequestParam(defaultValue = "USD") String currency,
            HttpServletRequest req) {

        String requestId = requestId(req);
        String provider = providerCode.toUpperCase();
        log.info("[WALLET] Fetching balance for provider: {}, currency: {}, requestId: {}", provider, currency, requestId);

        try {
            Map<String, Object> balance;

            switch (provider) {
                case "NETONE":
                    balance = fetchNetOneBalance();
                    break;
                case "ECONET":
                    balance = fetchEconetBalance(currency);
                    break;
                case "TELECEL":
                    balance = fetchTelecelBalance(currency);
                    break;
                default:
                    log.warn("[WALLET] Unknown provider requested: {}", provider);
                    balance = new HashMap<>();
                    balance.put("error", "Unknown provider: " + provider);
                    balance.put("status", "ERROR");
                    balance.put("message", "Provider not supported");
            }

            return ResponseEntity.ok(ApiResponse.success(balance, requestId));

        } catch (Exception e) {
            log.error("[WALLET] Unexpected error fetching balance for {}: {}", provider, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("INTERNAL_ERROR",
                            "Failed to fetch balance for " + provider + ": " + e.getMessage(),
                            requestId));
        }
    }

    /**
     * Fetch NetOne wallet balance from their API
     */
    private Map<String, Object> fetchNetOneBalance() {
        log.info("[WALLET] Fetching NetOne balance");
        log.debug("[WALLET] NetOne Base URL: {}", netoneBaseUrl);
        log.debug("[WALLET] NetOne Access Code: {}", netoneAccessCode);
        log.debug("[WALLET] NetOne Password length: {}",
                netoneAccessPassword != null ? netoneAccessPassword.length() : 0);

        // Validate configuration
        if (netoneBaseUrl == null || netoneBaseUrl.isEmpty() ||
                netoneAccessCode == null || netoneAccessCode.isEmpty() ||
                netoneAccessPassword == null || netoneAccessPassword.isEmpty()) {

            log.error("[WALLET] NetOne configuration is incomplete");
            Map<String, Object> errorMap = new HashMap<>();
            errorMap.put("provider", "NETONE");
            errorMap.put("currency", "USD");
            errorMap.put("balance", null);
            errorMap.put("status", "NOT_CONFIGURED");
            errorMap.put("message", "NetOne API credentials not configured");
            errorMap.put("fetchedAt", java.time.LocalDateTime.now().toString());
            return errorMap;
        }

        try {
            // Generate a unique reference for this request (required by NetOne API)
            String uniqueReference = "WALLET-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);

            // Prepare headers with unique x-agent-reference (CRITICAL FIX)
            HttpHeaders headers = new HttpHeaders();
            headers.set("x-access-code", netoneAccessCode);
            headers.set("x-access-password", netoneAccessPassword);
            headers.set("x-agent-reference", uniqueReference);
            headers.set("Accept", "application/json");

            log.info("[WALLET] Using agent reference: {}", uniqueReference);

            HttpEntity<Void> entity = new HttpEntity<>(headers);

            // Use the correct endpoint - from working independent integration
            String url = netoneBaseUrl + "/wallet-balance";

            log.info("[WALLET] Attempting NetOne balance URL: {}", url);

            try {
                ResponseEntity<Map> response = restTemplate.exchange(
                        url, HttpMethod.GET, entity, Map.class
                );

                log.info("[WALLET] NetOne response status: {}", response.getStatusCode());
                log.debug("[WALLET] NetOne response body: {}", response.getBody());

                Map<?, ?> body = response.getBody();
                if (body == null) {
                    log.warn("[WALLET] Empty response body from NetOne");
                    Map<String, Object> errorMap = new HashMap<>();
                    errorMap.put("provider", "NETONE");
                    errorMap.put("currency", "USD");
                    errorMap.put("balance", null);
                    errorMap.put("status", "ERROR");
                    errorMap.put("message", "Empty response from NetOne");
                    errorMap.put("fetchedAt", java.time.LocalDateTime.now().toString());
                    return errorMap;
                }

                // Try to extract balance from various possible field names
                Object balance = extractBalanceFromResponse(body);

                Map<String, Object> result = new HashMap<>();
                result.put("provider", "NETONE");
                result.put("currency", "USD");
                result.put("balance", balance);
                result.put("status", balance != null ? "OK" : "ERROR");
                result.put("message", balance != null ? "Balance fetched successfully" : "No balance field in response");
                result.put("rawResponse", body.toString());
                result.put("fetchedAt", java.time.LocalDateTime.now().toString());

                if (balance != null) {
                    log.info("[WALLET] Successfully extracted NetOne balance: {}", balance);
                } else {
                    log.warn("[WALLET] No balance field found in response: {}", body.keySet());
                }

                return result;

            } catch (HttpClientErrorException e) {
                log.error("[WALLET] HTTP Client error: {} - {}",
                        e.getStatusCode(), e.getResponseBodyAsString());

                Map<String, Object> errorMap = new HashMap<>();
                errorMap.put("provider", "NETONE");
                errorMap.put("currency", "USD");
                errorMap.put("balance", null);
                errorMap.put("status", "ERROR");
                errorMap.put("message", "NetOne API error: " + e.getStatusCode() + " - " + e.getResponseBodyAsString());
                errorMap.put("fetchedAt", java.time.LocalDateTime.now().toString());
                return errorMap;

            } catch (HttpServerErrorException e) {
                log.error("[WALLET] HTTP Server error: {} - {}",
                        e.getStatusCode(), e.getResponseBodyAsString());

                Map<String, Object> errorMap = new HashMap<>();
                errorMap.put("provider", "NETONE");
                errorMap.put("currency", "USD");
                errorMap.put("balance", null);
                errorMap.put("status", "ERROR");
                errorMap.put("message", "NetOne server error: " + e.getStatusCode());
                errorMap.put("fetchedAt", java.time.LocalDateTime.now().toString());
                return errorMap;

            } catch (RestClientException e) {
                log.error("[WALLET] REST client error: {}", e.getMessage());

                Map<String, Object> errorMap = new HashMap<>();
                errorMap.put("provider", "NETONE");
                errorMap.put("currency", "USD");
                errorMap.put("balance", null);
                errorMap.put("status", "ERROR");
                errorMap.put("message", "NetOne connection error: " + e.getMessage());
                errorMap.put("fetchedAt", java.time.LocalDateTime.now().toString());
                return errorMap;
            }

        } catch (Exception e) {
            log.error("[WALLET] Unexpected error in fetchNetOneBalance: {}", e.getMessage(), e);

            Map<String, Object> errorMap = new HashMap<>();
            errorMap.put("provider", "NETONE");
            errorMap.put("currency", "USD");
            errorMap.put("balance", null);
            errorMap.put("status", "ERROR");
            errorMap.put("message", "Internal error: " + e.getMessage());
            errorMap.put("fetchedAt", java.time.LocalDateTime.now().toString());
            return errorMap;
        }
    }

    /**
     * Extract balance from NetOne response body
     */
    private Object extractBalanceFromResponse(Map<?, ?> body) {
        // Check for common field names in the response
        String[] possibleFields = {
                "balance", "Balance", "walletBalance", "WalletBalance",
                "availableBalance", "AvailableBalance", "data", "Data"
        };

        for (String field : possibleFields) {
            Object value = body.get(field);
            if (value != null) {
                // If it's a nested object, check inside it
                if (value instanceof Map) {
                    Object nestedBalance = ((Map<?, ?>) value).get("balance");
                    if (nestedBalance != null) return nestedBalance;

                    nestedBalance = ((Map<?, ?>) value).get("amount");
                    if (nestedBalance != null) return nestedBalance;
                }

                // Try to parse as number
                if (value instanceof Number) {
                    return value;
                }

                // Try to parse string as number
                if (value instanceof String) {
                    try {
                        return Double.parseDouble((String) value);
                    } catch (NumberFormatException e) {
                        // Not a number, continue
                    }
                }

                return value;
            }
        }

        // Check for reply codes indicating error
        Object replyCode = body.get("ReplyCode");
        Object replyMessage = body.get("ReplyMessage");

        if (replyCode != null && !"0".equals(replyCode.toString())) {
            log.warn("[WALLET] NetOne returned error code: {} - {}", replyCode, replyMessage);
        }

        return null;
    }

    /**
     * Generate or extract request ID
     */
    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}