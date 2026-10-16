package zw.co.payport.gateway.service.adaptor.netone;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import zw.co.payport.gateway.service.adaptor.FulfilmentProviderAdaptor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NetOne Airtime & Data Bundle Fulfilment Adaptor
 *
 * Based on NetOne E-Top-Up Webservice Specification V2.8.3
 *
 * Supported Operations:
 * - Wallet Balance (ZWG & USD)
 * - End User Balance
 * - Recharge Pinless (Airtime)
 * - Query Transaction
 * - Data Bundle Recharge (ZWG & USD)
 * - Get Available Data Bundles (ZWG & USD)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NetOneAirtimeAdaptor implements FulfilmentProviderAdaptor {

    @Value("${payport.netone.base-url}")
    private String baseUrl;

    @Value("${payport.netone.access-code}")
    private String accessCode;

    @Value("${payport.netone.access-password}")
    private String accessPassword;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final NetOneDataBundleService dataBundleService;

    // Response code descriptions
    private static final Map<String, String> REPLY_CODE_DESCRIPTIONS = new LinkedHashMap<>();
    static {
        REPLY_CODE_DESCRIPTIONS.put("2", "Success - Transaction completed successfully");
        REPLY_CODE_DESCRIPTIONS.put("0", "Pending - Transaction awaiting processing");
        REPLY_CODE_DESCRIPTIONS.put("1", "Busy - Transaction being processed");
        REPLY_CODE_DESCRIPTIONS.put("3", "Not Found - Transaction reference not found");
        REPLY_CODE_DESCRIPTIONS.put("206", "Service unavailable - Recharge platform is down");
        REPLY_CODE_DESCRIPTIONS.put("208", "Insufficient balance - Agent wallet balance too low");
        REPLY_CODE_DESCRIPTIONS.put("210", "Network error - Please try again later");
        REPLY_CODE_DESCRIPTIONS.put("216", "Duplicate request - Already received");
        REPLY_CODE_DESCRIPTIONS.put("217", "Invalid number - Please check and try again");
        REPLY_CODE_DESCRIPTIONS.put("222", "Amount limit - Min/Max recharge exceeded");
        REPLY_CODE_DESCRIPTIONS.put("409", "Bulk EVD successful - PINs generated");
    }

    @Override
    public String getProviderCode() { 
        return "NETONE"; 
    }

    @Override
    public FulfilmentResult deliver(FulfilmentRequest request) throws Exception {
        String productCode = request.getProductTypeCode();

        if (productCode != null && productCode.startsWith("DATA_")) {
            return deliverDataBundle(request);
        }
        return deliverAirtime(request);
    }

    // ============================================================
    // 1. WALLET BALANCE (ZWG) - Section 5.1
    // ============================================================

    public WalletBalanceResult getWalletBalance(String agentReference) throws Exception {
        log.info("[NETONE] Getting wallet balance");
        
        HttpHeaders headers = createHeaders(agentReference);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        String url = baseUrl + "/wallet-balance";

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), Map.class);
            
            String replyCode = getStringFromMap(responseBody, "ReplyCode");
            String replyMsg = getStringFromMap(responseBody, "ReplyMsg");
            Double walletBalance = getDoubleFromMap(responseBody, "WalletBalance");
            
            logWalletBalanceResponse(replyCode, replyMsg, walletBalance);
            
            return new WalletBalanceResult(replyCode, replyMsg, walletBalance);
        } catch (Exception e) {
            log.error("[NETONE] Wallet balance failed: {}", e.getMessage());
            throw e;
        }
    }

    // ============================================================
    // 2. WALLET BALANCE (USD) - Section 6
    // ============================================================

    public WalletBalanceResult getWalletBalanceUsd(String agentReference) throws Exception {
        log.info("[NETONE] Getting wallet balance USD");
        
        HttpHeaders headers = createHeaders(agentReference);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        String url = baseUrl + "/wallet-balance-usd";

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), Map.class);
            
            String replyCode = getStringFromMap(responseBody, "ReplyCode");
            String replyMsg = getStringFromMap(responseBody, "ReplyMsg");
            Double walletBalance = getDoubleFromMap(responseBody, "WalletBalance");
            
            logWalletBalanceUsdResponse(replyCode, replyMsg, walletBalance);
            
            return new WalletBalanceResult(replyCode, replyMsg, walletBalance);
        } catch (Exception e) {
            log.error("[NETONE] Wallet balance USD failed: {}", e.getMessage());
            throw e;
        }
    }

    // ============================================================
    // 3. END USER BALANCE - Section 7
    // ============================================================

    public EndUserBalanceResult getEndUserBalance(String mobileNumber, String agentReference) throws Exception {
        log.info("[NETONE] Getting end user balance for: {}", maskPhone(mobileNumber));
        
        String normalizedMobile = normalisePhone(mobileNumber);
        HttpHeaders headers = createHeaders(agentReference);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        String url = baseUrl + "/enduser-balance?targetMobile=" + normalizedMobile;

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), Map.class);
            
            String replyCode = getStringFromMap(responseBody, "ReplyCode");
            String replyMsg = getStringFromMap(responseBody, "ReplyMsg");
            Double mobileBalance = getDoubleFromMap(responseBody, "MobileBalance");
            String windowPeriod = getStringFromMap(responseBody, "WindowPeriod");
            
            logEndUserBalanceResponse(replyCode, replyMsg, mobileBalance, windowPeriod);
            
            return new EndUserBalanceResult(replyCode, replyMsg, mobileBalance, windowPeriod);
        } catch (Exception e) {
            log.error("[NETONE] End user balance failed: {}", e.getMessage());
            throw e;
        }
    }

    // ============================================================
    // 4. RECHARGE PINLESS (Airtime) - Section 8
    // ============================================================

    // Package-private and pure (no HTTP, no state) specifically so this can
    // be unit-tested directly — see NetOneAirtimeAdaptorMessageTest.
    // Prefers our own documented description for this code over NetOne's raw
    // replyMsg — REPLY_CODE_DESCRIPTIONS is sourced directly from NetOne's own
    // API spec and is consistently clearer than what they actually send back
    // in the field. Falls back to their raw text for any code not catalogued.
    static String buildAirtimeMessage(String replyCode, String replyMsg, boolean success) {
        // The customer sees NetOne's own wording (owner decision, Phase 2 review); our catalogued
        // description is only a fallback when NetOne sends no text.
        if (replyMsg != null && !replyMsg.isBlank()) return replyMsg.trim();
        String knownDescription = REPLY_CODE_DESCRIPTIONS.get(replyCode);
        return knownDescription != null ? knownDescription : (success ? "Success" : "NetOne could not complete the recharge");
    }

    private FulfilmentResult deliverAirtime(FulfilmentRequest request) throws Exception {
        String currency = request.getCurrency() != null ? request.getCurrency() : "USD";
        double amountInDollars = request.getAmountCents() / 100.0;
        String normalizedMobile = normalisePhone(request.getRecipientIdentifier());

        logAirtimeRequest(request.getPayportReference(), normalizedMobile, amountInDollars, currency);

        // Was hitting /recharge-pinless (the OLD, single-currency
        // endpoint) with an unofficial ?currency= query param that isn't
        // part of that endpoint's spec at all -- NetOne's API almost
        // certainly ignored it silently, meaning every recharge was
        // processed in whatever NetOne's own default currency is,
        // regardless of what the customer actually paid in. This is the
        // fix: /recharge-pinless-all (spec section 7), which properly
        // accepts StockAccount as a real body field -- "USD" or "ZWG" --
        // exactly matching NetOne's own migration notice.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountInDollars);
        body.put("targetMobile", normalizedMobile);
        body.put("StockAccount", "ZWG".equalsIgnoreCase(currency) ? "ZWG" : "USD");

        HttpHeaders headers = createHeaders(request.getPayportReference());
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);

        String url = baseUrl + "/recharge-pinless-all";

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), Map.class);

            String replyCode = getStringFromMap(responseBody, "ReplyCode");
            String replyMsg = getStringFromMap(responseBody, "ReplyMsg");
            // WalletBalance is a nested object on this endpoint --
            // { accountTypeId, StockAccount, balance } -- not the plain
            // number the old endpoint returned. getDoubleFromMap would
            // silently fail against this shape.
            Double walletBalance = extractStockAccountBalance(responseBody);
            Double amountRecharged = getDoubleFromMap(responseBody, "Amount");
            String transactionId = extractTransactionId(response.getBody(), request.getPayportReference());

            boolean success = "2".equals(replyCode);

            // Code 216 ("Duplicate request - Already received") doesn't
            // mean this attempt failed -- it means NetOne has already
            // seen this exact reference before, exactly what a retry
            // triggers if the original attempt actually reached them.
            // Same situation and same fix as Econet: resolve it via
            // their own query-transaction endpoint (section 9) rather
            // than recording a fresh failure on something that may have
            // already succeeded.
            if (!success && "216".equals(replyCode)) {
                log.info("[NETONE] Duplicate response for {} -- querying actual status before deciding the outcome", request.getPayportReference());
                try {
                    QueryTransactionResult queryResult = queryTransaction(request.getPayportReference(), request.getPayportReference());
                    boolean queriedSuccess = "2".equals(queryResult.getReplyCode());
                    log.info("[NETONE] Duplicate resolution for {} -- query replyCode={}, success={}",
                            request.getPayportReference(), queryResult.getReplyCode(), queriedSuccess);

                    if (queriedSuccess) {
                        return FulfilmentResult.builder()
                                .success(true)
                                .fulfilmentReference(request.getPayportReference())
                                .message(queryResult.getReplyMsg() != null ? queryResult.getReplyMsg() : "Success")
                                .rawResponse(response.getBody() + " | Resolved via query-transaction: " + queryResult.getRawReply())
                                .build();
                    }
                    // Query itself confirms it genuinely didn't go
                    // through -- fall through to the normal failure path
                    // below, using the query's own reply as the more
                    // authoritative, current answer.
                    replyCode = queryResult.getReplyCode();
                    replyMsg = queryResult.getReplyMsg();
                } catch (Exception e) {
                    // Couldn't resolve it either way -- don't guess.
                    // Falls through with the original duplicate message,
                    // which already tells ops this needs a manual check.
                    log.error("[NETONE] Could not resolve duplicate status for {}: {}", request.getPayportReference(), e.getMessage(), e);
                }
            }

            logAirtimeResponse(replyCode, replyMsg, walletBalance, amountRecharged, transactionId);

            String outMessage = buildAirtimeMessage(replyCode, replyMsg, success);

            return FulfilmentResult.builder()
                    .success(success)
                    .fulfilmentReference(transactionId)
                    .message(outMessage)
                    .rawResponse(response.getBody())
                    .build();

        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            // NetOne answers failures with HTTP 400 and a ReplyCode/ReplyMsg body (spec section 16): that is a
            // provider answer, not a transport error, so keep its code and wording.
            String errorBody = e.getResponseBodyAsString();
            String replyCode = null, replyMsg = null;
            try {
                Map<String, Object> m = objectMapper.readValue(errorBody, Map.class);
                replyCode = getStringFromMap(m, "ReplyCode");
                replyMsg = getStringFromMap(m, "ReplyMsg");
            } catch (Exception ignored) { }
            log.warn("[NETONE] Airtime HTTP {} replyCode={} msg={}", e.getStatusCode(), replyCode, replyMsg);
            if (replyCode == null) {
                return FulfilmentResult.builder().success(false)
                        .outcomeUnknown(e.getStatusCode().is5xxServerError())
                        .message(replyMsg != null ? replyMsg : "NetOne HTTP " + e.getStatusCode().value())
                        .rawResponse(errorBody).build();
            }
            return FulfilmentResult.builder().success(false).errorCode(replyCode)
                    .message(buildAirtimeMessage(replyCode, replyMsg, false))
                    .rawResponse(errorBody).build();
        } catch (Exception e) {
            log.error("[NETONE] Airtime delivery failed: {}", e.getMessage());
            return FulfilmentResult.builder()
                    .success(false)
                    .outcomeUnknown(zw.co.payport.gateway.payments.SubmissionErrors.isOutcomeUnknown(e))
                    .message("NetOne airtime failed: " + e.getMessage())
                    .rawResponse(e.getMessage())
                    .build();
        }
    }

    /** Recharge Pinless All returns WalletBalance as {accountTypeId, StockAccount, balance}, not a plain number -- see spec section 7.3. */
    @SuppressWarnings("unchecked")
    private Double extractStockAccountBalance(Map<String, Object> responseBody) {
        Object raw = responseBody.get("WalletBalance");
        if (raw instanceof Map) {
            Object balance = ((Map<String, Object>) raw).get("balance");
            if (balance instanceof Number) return ((Number) balance).doubleValue();
        } else if (raw instanceof Number) {
            // Tolerate the old flat-number shape too, in case a future API version reverts or varies by environment.
            return ((Number) raw).doubleValue();
        }
        return null;
    }

    // ============================================================
    // 5. QUERY TRANSACTION - Section 9
    // ============================================================

    public QueryTransactionResult queryTransaction(String agentReference, String originalAgentReference) throws Exception {
        log.info("[NETONE] Querying transaction for reference: {}", originalAgentReference);
        
        HttpHeaders headers = createHeaders(agentReference);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        String url = baseUrl + "/query-transaction?agentReference=" + originalAgentReference;

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), Map.class);
            
            String replyCode = getStringFromMap(responseBody, "ReplyCode");
            String replyMsg = getStringFromMap(responseBody, "ReplyMsg");
            String originalAgentRef = getStringFromMap(responseBody, "OriginalAgentReference");
            // The whole point of this endpoint is reconciliation -- this
            // is the actual payload of what NetOne originally replied to
            // the queried transaction, previously discarded entirely.
            Object rawReplyObj = responseBody.get("RawReply");
            String rawReply = rawReplyObj != null ? objectMapper.writeValueAsString(rawReplyObj) : null;

            logQueryResponse(replyCode, replyMsg, originalAgentRef);

            return new QueryTransactionResult(replyCode, replyMsg, originalAgentRef, rawReply);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // A 4xx with a ReplyCode body is still NetOne's answer (e.g. 3 = request not found).
            try {
                Map<String, Object> m = objectMapper.readValue(e.getResponseBodyAsString(), Map.class);
                String code = getStringFromMap(m, "ReplyCode");
                if (code != null) {
                    return new QueryTransactionResult(code, getStringFromMap(m, "ReplyMsg"), originalAgentReference, e.getResponseBodyAsString());
                }
            } catch (Exception ignored) { }
            log.error("[NETONE] Query transaction failed: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("[NETONE] Query transaction failed: {}", e.getMessage());
            throw e;
        }
    }

    // ============================================================
    // 6. DATA BUNDLE RECHARGE - Sections 10 & 11
    // ============================================================

    private FulfilmentResult deliverDataBundle(FulfilmentRequest request) throws Exception {
        String currency = request.getCurrency() != null ? request.getCurrency() : "USD";
        String bundleCode = extractBundleCode(request.getProductConfig());
        String normalizedMobile = normalisePhone(request.getRecipientIdentifier());

        if (bundleCode == null || bundleCode.isBlank()) {
            return FulfilmentResult.builder()
                    .success(false)
                    .message("Data bundle code is required in productConfig")
                    .build();
        }

        logDataBundleRequest(request.getPayportReference(), normalizedMobile, bundleCode, currency);

        NetOneDataBundleService.DataRechargeResult result = dataBundleService.rechargeData(
                request.getRecipientIdentifier(),
                bundleCode,
                request.getAmountCents(),
                currency,
                request.getPayportReference()
        );

        logDataBundleResponse(result);

        return FulfilmentResult.builder()
                .success(result.success)
                .fulfilmentReference(result.transactionId != null ? result.transactionId : request.getPayportReference())
                .message(result.message)
                .rawResponse(result.rawResponse)
                .build();
    }

    // ============================================================
    // 7. GET AVAILABLE DATA BUNDLES - Sections 12 & 13
    // ============================================================

    public AvailableBundlesResult getAvailableDataBundles(String agentReference, String currency) throws Exception {
        log.info("[NETONE] Getting available data bundles for currency: {}", currency);
        
        HttpHeaders headers = createHeaders(agentReference);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        String endpoint = "USD".equalsIgnoreCase(currency) ? "/get-data-bundles-usd" : "/get-data-bundles";
        String url = baseUrl + endpoint;

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), Map.class);
            
            String replyCode = getStringFromMap(responseBody, "ReplyCode");
            String replyMsg = getStringFromMap(responseBody, "ReplyMsg");
            Object bundles = responseBody.get("Bundles");
            
            logAvailableBundlesResponse(replyCode, replyMsg, bundles);
            
            return new AvailableBundlesResult(replyCode, replyMsg, bundles);
        } catch (Exception e) {
            log.error("[NETONE] Get available bundles failed: {}", e.getMessage());
            throw e;
        }
    }

    // ============================================================
    // 8. BULK EVD PIN PURCHASE - Section 15
    // ============================================================

    // ============================================================
    // 7B. QUERY PIN STOCK - Section 11
    // ============================================================

    public PinStockResult queryPinStock(String agentReference) throws Exception {
        log.info("[NETONE] Querying PIN stock");

        HttpHeaders headers = createHeaders(agentReference);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        String url = baseUrl + "/query-evd";

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), Map.class);

            String replyCode = getStringFromMap(responseBody, "ReplyCode");
            String replyMsg = getStringFromMap(responseBody, "ReplyMsg");
            Object inStock = responseBody.get("InStock");

            log.info("[NETONE] Pin stock query -- ReplyCode: {}, ReplyMsg: {}", replyCode, replyMsg);

            return new PinStockResult(replyCode, replyMsg, inStock);
        } catch (Exception e) {
            log.error("[NETONE] Query pin stock failed: {}", e.getMessage());
            throw e;
        }
    }

    public BulkEvdResult bulkEvdPurchase(BulkEvdRequest request) throws Exception {
        log.info("[NETONE] Bulk EVD purchase - BrandID: {}, Denomination: {}, Quantity: {}", 
                request.getBrandId(), request.getDenomination(), request.getQuantity());
        
        HttpHeaders headers = createHeaders(request.getAgentReference());
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("BrandID", request.getBrandId());
        body.put("Denomination", request.getDenomination());
        // Was "quantity" (lowercase) -- the spec's own example body
        // ('{"BrandID": 1, "Denomination": 0.1, "Quantity":7000}',
        // section 11.5) uses "Quantity" with a capital Q. JSON keys are
        // case-sensitive, so this field was very likely never actually
        // being read by NetOne's system at all.
        body.put("Quantity", request.getQuantity());
        
        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
        String url = baseUrl + "/bulk-evd";

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), Map.class);
            
            String replyCode = getStringFromMap(responseBody, "ReplyCode");
            String replyMsg = getStringFromMap(responseBody, "ReplyMsg");
            Double walletBalance = getDoubleFromMap(responseBody, "WalletBalance");
            
            logBulkEvdResponse(replyCode, replyMsg, walletBalance);
            
            return new BulkEvdResult(replyCode, replyMsg, walletBalance, response.getBody());
        } catch (Exception e) {
            log.error("[NETONE] Bulk EVD purchase failed: {}", e.getMessage());
            throw e;
        }
    }

    @Override
    public FulfilmentStatusResult queryDelivery(String fulfilmentReference) throws Exception {
        log.info("[NETONE] Querying delivery for reference: {}", fulfilmentReference);
        
        // x-agent-reference may be used only once (HTTP 429 otherwise), so every query gets a fresh one.
        QueryTransactionResult result = queryTransaction(fulfilmentReference + "-Q" + (System.currentTimeMillis() % 1_000_000_000L), fulfilmentReference);
        String code = result.getReplyCode();
        // Spec section 9.2.1: 2 success; 0 pending; 1 busy; 3 request not found (never received: safe to send again);
        // any other value is the original request's failure code.
        String status = "2".equals(code) ? "DELIVERED"
                : ("0".equals(code) || "1".equals(code) || code == null) ? "PENDING"
                : "3".equals(code) ? "NOT_FOUND"
                : zw.co.payport.gateway.payments.DeliveryOutcomeRules.classify("NETONE", code)
                        == zw.co.payport.gateway.payments.DeliveryOutcomeRules.Verdict.UNKNOWN ? "PENDING" : "FAILED";
        return FulfilmentStatusResult.builder()
                .status(status)
                .fulfilmentReference(fulfilmentReference)
                .message(result.getReplyMsg())
                .build();
    }

    // ============================================================
    // LOGGING METHODS
    // ============================================================

    private void logAirtimeRequest(String reference, String mobile, double amount, String currency) {
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    NETONE AIRTIME RECHARGE REQUEST               ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Reference:     {}", reference);
        log.info("║ Mobile:        {}", maskPhone(mobile));
        log.info("║ Amount:        {} {}", amount, currency);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logAirtimeResponse(String replyCode, String replyMsg, Double walletBalance, Double amount, String txId) {
        String statusIcon = "2".equals(replyCode) ? "✅ SUCCESS" : "❌ FAILED";
        String description = REPLY_CODE_DESCRIPTIONS.getOrDefault(replyCode, "Unknown status");
        
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    NETONE AIRTIME RECHARGE RESPONSE              ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Status:        {} (Code: {})", statusIcon, replyCode);
        log.info("║ Description:   {}", description);
        log.info("║ Transaction ID: {}", txId);
        if (walletBalance != null) {
            log.info("║ Wallet Balance: ${}", walletBalance);
        }
        if (amount != null) {
            log.info("║ Amount Recharged: ${}", amount);
        }
        log.info("║ Message:       {}", replyMsg);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logDataBundleRequest(String reference, String mobile, String bundleCode, String currency) {
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    NETONE DATA BUNDLE REQUEST                    ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Reference:     {}", reference);
        log.info("║ Mobile:        {}", maskPhone(mobile));
        log.info("║ Bundle Code:   {}", bundleCode);
        log.info("║ Currency:      {}", currency);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logDataBundleResponse(NetOneDataBundleService.DataRechargeResult result) {
        String statusIcon = result.success ? "✅ SUCCESS" : "❌ FAILED";
        
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    NETONE DATA BUNDLE RESPONSE                   ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Status:        {}", statusIcon);
        log.info("║ Transaction ID: {}", result.transactionId);
        log.info("║ Message:       {}", result.message);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logWalletBalanceResponse(String replyCode, String replyMsg, Double balance) {
        String statusIcon = "2".equals(replyCode) ? "✅ SUCCESS" : "⚠️ RESPONSE";
        
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    NETONE WALLET BALANCE RESPONSE                ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Status:        {} (Code: {})", statusIcon, replyCode);
        if (balance != null) {
            log.info("║ Wallet Balance: ${}", balance);
        }
        log.info("║ Message:       {}", replyMsg);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logWalletBalanceUsdResponse(String replyCode, String replyMsg, Double balance) {
        String statusIcon = "2".equals(replyCode) ? "✅ SUCCESS" : "⚠️ RESPONSE";
        
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    NETONE WALLET BALANCE USD RESPONSE            ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Status:        {} (Code: {})", statusIcon, replyCode);
        if (balance != null) {
            log.info("║ Wallet Balance: ${}", balance);
        }
        log.info("║ Message:       {}", replyMsg);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logEndUserBalanceResponse(String replyCode, String replyMsg, Double balance, String windowPeriod) {
        String statusIcon = "2".equals(replyCode) ? "✅ SUCCESS" : "⚠️ RESPONSE";
        
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    NETONE END USER BALANCE RESPONSE              ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Status:        {} (Code: {})", statusIcon, replyCode);
        if (balance != null) {
            log.info("║ Mobile Balance: ${}", balance);
        }
        if (windowPeriod != null) {
            log.info("║ Window Period: {}", windowPeriod);
        }
        log.info("║ Message:       {}", replyMsg);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logQueryResponse(String replyCode, String replyMsg, String originalRef) {
        String statusIcon = "2".equals(replyCode) ? "✅ SUCCESS" : 
                           "0".equals(replyCode) ? "⏳ PENDING" :
                           "1".equals(replyCode) ? "🔄 BUSY" : "❌ FAILED";
        String description = REPLY_CODE_DESCRIPTIONS.getOrDefault(replyCode, "Unknown status");
        
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    NETONE QUERY TRANSACTION RESPONSE             ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Status:        {} (Code: {})", statusIcon, replyCode);
        log.info("║ Description:   {}", description);
        log.info("║ Original Ref:  {}", originalRef);
        log.info("║ Message:       {}", replyMsg);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logAvailableBundlesResponse(String replyCode, String replyMsg, Object bundles) {
        String statusIcon = "2".equals(replyCode) ? "✅ SUCCESS" : "⚠️ RESPONSE";
        
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    NETONE AVAILABLE BUNDLES RESPONSE             ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Status:        {} (Code: {})", statusIcon, replyCode);
        log.info("║ Bundles:       {} available", bundles != null ? "Yes" : "No");
        log.info("║ Message:       {}", replyMsg);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logBulkEvdResponse(String replyCode, String replyMsg, Double walletBalance) {
        String statusIcon = "2".equals(replyCode) ? "✅ SUCCESS" : "❌ FAILED";
        String description = REPLY_CODE_DESCRIPTIONS.getOrDefault(replyCode, "Unknown status");
        
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    NETONE BULK EVD RESPONSE                      ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Status:        {} (Code: {})", statusIcon, replyCode);
        log.info("║ Description:   {}", description);
        if (walletBalance != null) {
            log.info("║ Wallet Balance: ${}", walletBalance);
        }
        log.info("║ Message:       {}", replyMsg);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    // ============================================================
    // HELPER METHODS
    // ============================================================

    private HttpHeaders createHeaders(String agentReference) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-access-code", accessCode);
        headers.set("x-access-password", accessPassword);
        headers.set("x-agent-reference", agentReference);
        return headers;
    }

    private String extractTransactionId(String body, String fallback) {
        try {
            Map<?, ?> map = objectMapper.readValue(body, Map.class);
            for (String key : new String[]{"transactionId", "txId", "reference", "id"}) {
                if (map.containsKey(key)) return map.get(key).toString();
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private String extractBundleCode(String productConfig) {
        if (productConfig == null || productConfig.isBlank()) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> cfg = objectMapper.readValue(productConfig, Map.class);
            Object code = cfg.get("bundleCode");
            return code != null ? code.toString() : null;
        } catch (Exception e) { 
            return null; 
        }
    }

    private String normalisePhone(String phone) {
        if (phone == null) return "";
        String digits = phone.replaceAll("\\D", "");
        if (digits.startsWith("263")) {
            digits = "0" + digits.substring(3);
        }
        if (!digits.startsWith("0") && digits.length() == 9) {
            digits = "0" + digits;
        }
        if (digits.length() > 10) {
            digits = digits.substring(digits.length() - 10);
        }
        return digits;
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) return "****";
        return phone.substring(0, 4) + "****" + phone.substring(phone.length() - 3);
    }

    private String getStringFromMap(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    private Double getDoubleFromMap(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        if (val instanceof Number) return ((Number) val).doubleValue();
        try {
            return Double.parseDouble(val.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ============================================================
    // INNER CLASSES
    // ============================================================

    public static class WalletBalanceResult {
        private final String replyCode;
        private final String replyMsg;
        private final Double walletBalance;

        public WalletBalanceResult(String replyCode, String replyMsg, Double walletBalance) {
            this.replyCode = replyCode;
            this.replyMsg = replyMsg;
            this.walletBalance = walletBalance;
        }
        public String getReplyCode() { return replyCode; }
        public String getReplyMsg() { return replyMsg; }
        public Double getWalletBalance() { return walletBalance; }
        public boolean isSuccess() { return "2".equals(replyCode); }
        public String getDescription() { return REPLY_CODE_DESCRIPTIONS.getOrDefault(replyCode, "Unknown status"); }
    }

    public static class EndUserBalanceResult {
        private final String replyCode;
        private final String replyMsg;
        private final Double mobileBalance;
        private final String windowPeriod;

        public EndUserBalanceResult(String replyCode, String replyMsg, Double mobileBalance, String windowPeriod) {
            this.replyCode = replyCode;
            this.replyMsg = replyMsg;
            this.mobileBalance = mobileBalance;
            this.windowPeriod = windowPeriod;
        }
        public String getReplyCode() { return replyCode; }
        public String getReplyMsg() { return replyMsg; }
        public Double getMobileBalance() { return mobileBalance; }
        public String getWindowPeriod() { return windowPeriod; }
        public boolean isSuccess() { return "2".equals(replyCode); }
    }

    public static class QueryTransactionResult {
        private final String replyCode;
        private final String replyMsg;
        private final String originalAgentReference;
        private final String rawReply;

        public QueryTransactionResult(String replyCode, String replyMsg, String originalAgentReference, String rawReply) {
            this.replyCode = replyCode;
            this.replyMsg = replyMsg;
            this.originalAgentReference = originalAgentReference;
            this.rawReply = rawReply;
        }
        public String getReplyCode() { return replyCode; }
        public String getReplyMsg() { return replyMsg; }
        public String getOriginalAgentReference() { return originalAgentReference; }
        /** The actual reconciliation payload -- what NetOne originally replied to the queried transaction, as a raw JSON string. See spec section 8.2. */
        public String getRawReply() { return rawReply; }
        public boolean isSuccess() { return "2".equals(replyCode); }
        public boolean isPending() { return "0".equals(replyCode); }
        public boolean isBusy() { return "1".equals(replyCode); }
    }

    public static class AvailableBundlesResult {
        private final String replyCode;
        private final String replyMsg;
        private final Object bundles;

        public AvailableBundlesResult(String replyCode, String replyMsg, Object bundles) {
            this.replyCode = replyCode;
            this.replyMsg = replyMsg;
            this.bundles = bundles;
        }
        public String getReplyCode() { return replyCode; }
        public String getReplyMsg() { return replyMsg; }
        public Object getBundles() { return bundles; }
        public boolean isSuccess() { return "2".equals(replyCode); }
    }

    /** Section 11 -- InStock is a collection of {BrandID, BrandName, Denomination, Quantity} per the spec. */
    public static class PinStockResult {
        private final String replyCode;
        private final String replyMsg;
        private final Object inStock;

        public PinStockResult(String replyCode, String replyMsg, Object inStock) {
            this.replyCode = replyCode;
            this.replyMsg = replyMsg;
            this.inStock = inStock;
        }
        public String getReplyCode() { return replyCode; }
        public String getReplyMsg() { return replyMsg; }
        public Object getInStock() { return inStock; }
        public boolean isSuccess() { return "2".equals(replyCode); }
    }

    public static class BulkEvdRequest {
        private String agentReference;
        private int brandId;
        private double denomination;
        private int quantity;

        public String getAgentReference() { return agentReference; }
        public void setAgentReference(String agentReference) { this.agentReference = agentReference; }
        public int getBrandId() { return brandId; }
        public void setBrandId(int brandId) { this.brandId = brandId; }
        public double getDenomination() { return denomination; }
        public void setDenomination(double denomination) { this.denomination = denomination; }
        public int getQuantity() { return quantity; }
        public void setQuantity(int quantity) { this.quantity = quantity; }
    }

    public static class BulkEvdResult {
        private final String replyCode;
        private final String replyMsg;
        private final Double walletBalance;
        private final String rawResponse;

        public BulkEvdResult(String replyCode, String replyMsg, Double walletBalance, String rawResponse) {
            this.replyCode = replyCode;
            this.replyMsg = replyMsg;
            this.walletBalance = walletBalance;
            this.rawResponse = rawResponse;
        }
        public String getReplyCode() { return replyCode; }
        public String getReplyMsg() { return replyMsg; }
        public Double getWalletBalance() { return walletBalance; }
        public String getRawResponse() { return rawResponse; }
        public boolean isSuccess() { return "2".equals(replyCode); }
    }
}
