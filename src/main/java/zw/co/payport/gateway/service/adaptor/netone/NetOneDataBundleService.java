package zw.co.payport.gateway.service.adaptor.netone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;

/**
 * NetOne Data Bundle Service
 *
 * Based on NetOne E-Top-Up Webservice Specification V2.8.3
 *
 * Fetches live data bundles directly from the NetOne Pinless EVD API.
 * Supports both ZWG and USD bundles from separate endpoints:
 *
 *   ZWG bundles : GET {base-url}/get-data-bundles
 *   USD bundles : GET {base-url}/get-data-bundles-usd
 *   ZWG recharge: POST {base-url}/recharge-data
 *   USD recharge: POST {base-url}/recharge-data-usd
 *
 * Bundles are cached for 5 minutes to avoid hammering the MNO API on every page load.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NetOneDataBundleService {

    @Value("${payport.netone.base-url}")
    private String baseUrl;

    @Value("${payport.netone.access-code}")
    private String accessCode;

    @Value("${payport.netone.access-password}")
    private String accessPassword;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

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
    }

    // ============================================================
    // 1. FETCH AVAILABLE DATA BUNDLES - Sections 12 & 13
    // ============================================================

    @Cacheable(value = "netone-bundles", key = "#currency", unless = "#result.isEmpty()")
    public List<DataBundle> fetchBundles(String currency) {
        try {
            String endpoint;
            HttpHeaders headers = createHeaders("BUNDLE-LIST-" + System.currentTimeMillis());
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response;

            logBundleRequest(currency);

            if ("ZWG".equalsIgnoreCase(currency)) {
                endpoint = baseUrl + "/get-data-bundles";
                response = restTemplate.exchange(endpoint, HttpMethod.GET, entity, String.class);
            } else {
                endpoint = baseUrl + "/get-data-bundles-usd";
                response = restTemplate.exchange(endpoint, HttpMethod.GET, entity, String.class);
            }

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("[NETONE-DATA] Bundle fetch returned {}", response.getStatusCode());
                return List.of();
            }

            logBundleResponse(response.getBody());
            return parseBundleResponse(response.getBody(), currency);

        } catch (Exception e) {
            log.error("[NETONE-DATA] Failed to fetch {} bundles: {}", currency, e.getMessage());
            return List.of();
        }
    }

    // ============================================================
    // 2. DATA BUNDLE RECHARGE - Sections 10 & 11
    // ============================================================

    public DataRechargeResult rechargeData(String targetMobile, String productCode,
                                           long amountCents, String currency,
                                           String agentReference) {
        String normalizedMobile = normalisePhone(targetMobile);
        double amountInDollars = amountCents / 100.0;
        
        String endpoint = "ZWG".equalsIgnoreCase(currency)
                ? baseUrl + "/recharge-data"
                : baseUrl + "/recharge-data-usd";

        logRechargeRequest(agentReference, normalizedMobile, productCode, amountInDollars, currency);

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("targetMobile", normalizedMobile);
            body.put("productCode", productCode);
            body.put("amount", amountInDollars);

            HttpHeaders headers = createHeaders(agentReference);
            headers.setContentType(MediaType.APPLICATION_JSON);
            String jsonBody = objectMapper.writeValueAsString(body);
            HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    endpoint, HttpMethod.POST, entity, String.class);

            return parseRechargeResult(response, agentReference, productCode);

        } catch (Exception e) {
            log.error("[NETONE-DATA] Recharge failed for ref={}: {}", agentReference, e.getMessage());
            return DataRechargeResult.failed("NetOne data recharge failed: " + e.getMessage());
        }
    }

    // ============================================================
    // LOGGING METHODS
    // ============================================================

    private void logBundleRequest(String currency) {
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    NETONE DATA BUNDLES REQUEST                   ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Currency:      {}", currency);
        log.info("║ Endpoint:      {}/{}", baseUrl, 
                "ZWG".equalsIgnoreCase(currency) ? "get-data-bundles" : "get-data-bundles-usd");
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logBundleResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            int replyCode = root.path("ReplyCode").asInt(-1);
            String replyMsg = root.path("ReplyMsg").asText("");
            int bundleCount = root.path("Bundles").isArray() ? root.path("Bundles").size() : 0;
            
            String statusIcon = replyCode == 2 ? "✅ SUCCESS" : "⚠️ RESPONSE";
            String description = REPLY_CODE_DESCRIPTIONS.getOrDefault(String.valueOf(replyCode), "Unknown status");
            
            log.info("╔══════════════════════════════════════════════════════════════════╗");
            log.info("║                    NETONE DATA BUNDLES RESPONSE                 ║");
            log.info("╠══════════════════════════════════════════════════════════════════╣");
            log.info("║ Status:        {} (Code: {})", statusIcon, replyCode);
            log.info("║ Description:   {}", description);
            log.info("║ Bundles Found: {}", bundleCount);
            log.info("║ Message:       {}", replyMsg);
            log.info("╚══════════════════════════════════════════════════════════════════╝");
        } catch (Exception e) {
            log.info("╔══════════════════════════════════════════════════════════════════╗");
            log.info("║                    NETONE DATA BUNDLES RESPONSE                 ║");
            log.info("╠══════════════════════════════════════════════════════════════════╣");
            log.info("║ Response: {}", response != null ? response.substring(0, Math.min(200, response.length())) : "null");
            log.info("╚══════════════════════════════════════════════════════════════════╝");
        }
    }

    private void logRechargeRequest(String reference, String mobile, String productCode, double amount, String currency) {
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    NETONE DATA RECHARGE REQUEST                  ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Reference:     {}", reference);
        log.info("║ Mobile:        {}", maskPhone(mobile));
        log.info("║ Product Code:  {}", productCode);
        log.info("║ Amount:        {} {}", amount, currency);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logRechargeResponse(String replyCode, String replyMsg, String transactionId, Double walletBalance) {
        String statusIcon = "2".equals(replyCode) ? "✅ SUCCESS" : 
                           "0".equals(replyCode) ? "⏳ PENDING" :
                           "1".equals(replyCode) ? "🔄 BUSY" : "❌ FAILED";
        String description = REPLY_CODE_DESCRIPTIONS.getOrDefault(replyCode, "Unknown status");
        
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    NETONE DATA RECHARGE RESPONSE                 ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Status:        {} (Code: {})", statusIcon, replyCode);
        log.info("║ Description:   {}", description);
        log.info("║ Transaction ID: {}", transactionId != null ? transactionId : "N/A");
        if (walletBalance != null) {
            log.info("║ Wallet Balance: ${}", walletBalance);
        }
        log.info("║ Message:       {}", replyMsg);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    // ============================================================
    // RESPONSE PARSERS
    // ============================================================

    private List<DataBundle> parseBundleResponse(String json, String currency) {
        try {
            JsonNode root = objectMapper.readTree(json);
            List<DataBundle> bundles = new ArrayList<>();

            int replyCode = root.path("ReplyCode").asInt(-1);
            if (replyCode != 0 && replyCode != 2) {
                log.warn("[NETONE-DATA] Non-success ReplyCode: {}", replyCode);
                return List.of();
            }

            JsonNode bundlesNode = root.get("Bundles");
            if (bundlesNode == null || !bundlesNode.isArray()) {
                log.warn("[NETONE-DATA] No Bundles array in response");
                return List.of();
            }

            for (JsonNode item : bundlesNode) {
                DataBundle bundle = parseBundle(item, currency);
                if (bundle != null && bundle.bundleCode() != null && !bundle.bundleCode().isEmpty()) {
                    bundles.add(bundle);
                }
            }

            log.info("[NETONE-DATA] Parsed {} bundles for {}", bundles.size(), currency);
            return bundles;

        } catch (Exception e) {
            log.error("[NETONE-DATA] Failed to parse bundle response: {}", e.getMessage());
            return List.of();
        }
    }

    private DataBundle parseBundle(JsonNode item, String currency) {
        try {
            String productCode = item.path("ProductCode").asText();
            String name = item.path("Name").asText();
            String description = item.path("Description").asText();
            double amount = item.path("Amount").asDouble();
            String validityPeriod = item.path("ValidityPeriod").asText();
            
            if (productCode.isEmpty()) {
                return null;
            }

            // Use Name as the bundle name, fallback to Description
            String bundleName = name.isEmpty() ? description : name;
            
            // Extract data size from name or description
            String dataSize = extractDataSize(bundleName);
            
            // Format validity period
            String validity = formatValidity(validityPeriod);

            return new DataBundle(
                    productCode,
                    bundleName,
                    dataSize,
                    validity,
                    Math.round(amount * 100),
                    currency
            );
        } catch (Exception e) {
            log.warn("[NETONE-DATA] Skipping unparseable bundle: {}", item);
            return null;
        }
    }

    private DataRechargeResult parseRechargeResult(ResponseEntity<String> response,
                                                   String reference, String productCode) {
        try {
            if (!response.getStatusCode().is2xxSuccessful()) {
                return DataRechargeResult.failed("HTTP " + response.getStatusCode());
            }
            if (response.getBody() == null) {
                return DataRechargeResult.failed("Empty response from NetOne");
            }

            JsonNode root = objectMapper.readTree(response.getBody());

            int replyCode = root.path("ReplyCode").asInt(-1);
            String replyMsg = root.path("ReplyMsg").asText("");
            String transactionId = root.path("TransactionId").asText();
            if (transactionId.isEmpty()) {
                transactionId = root.path("Reference").asText();
            }
            if (transactionId.isEmpty()) {
                transactionId = reference;
            }
            
            Double walletBalance = null;
            if (root.has("WalletBalance") && !root.path("WalletBalance").isMissingNode()) {
                walletBalance = root.path("WalletBalance").asDouble();
            }

            boolean success = replyCode == 0 || replyCode == 2;
            
            logRechargeResponse(String.valueOf(replyCode), replyMsg, transactionId, walletBalance);

            if (success) {
                return DataRechargeResult.success(transactionId, replyMsg, response.getBody());
            } else {
                return DataRechargeResult.failed(replyMsg);
            }
        } catch (Exception e) {
            log.error("[NETONE-DATA] Failed to parse recharge result: {}", e.getMessage());
            return DataRechargeResult.failed("Response parse error: " + e.getMessage());
        }
    }

    // ============================================================
    // HELPER METHODS
    // ============================================================

    private String extractDataSize(String bundleName) {
        if (bundleName == null) return "";
        // Look for patterns like "500MB", "1GB", "2.5GB"
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?\\s*(?:MB|GB))", 
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = pattern.matcher(bundleName);
        if (matcher.find()) {
            return matcher.group(1).toUpperCase();
        }
        return "";
    }

    private String formatValidity(String validityPeriod) {
        if (validityPeriod == null || validityPeriod.isEmpty()) return "";
        if (validityPeriod.matches("^\\d+$")) {
            int days = Integer.parseInt(validityPeriod);
            return days + (days == 1 ? " day" : " days");
        }
        return validityPeriod;
    }

    private HttpHeaders createHeaders(String agentReference) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-access-code", accessCode);
        headers.set("x-access-password", accessPassword);
        headers.set("x-agent-reference", agentReference);
        return headers;
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

    // ============================================================
    // DTOs
    // ============================================================

    public record DataBundle(
            String bundleCode,
            String name,
            String dataSize,
            String validity,
            long amountCents,
            String currency
    ) {}

    public static class DataRechargeResult {
        public final boolean success;
        public final String transactionId;
        public final String message;
        public final String rawResponse;

        private DataRechargeResult(boolean success, String transactionId,
                                   String message, String rawResponse) {
            this.success = success;
            this.transactionId = transactionId;
            this.message = message;
            this.rawResponse = rawResponse;
        }

        public static DataRechargeResult success(String txId, String msg, String raw) {
            return new DataRechargeResult(true, txId, msg, raw);
        }

        public static DataRechargeResult failed(String msg) {
            return new DataRechargeResult(false, null, msg, null);
        }

        @Override
        public String toString() {
            return String.format("DataRechargeResult{success=%s, txId=%s, message=%s}",
                    success, transactionId, message);
        }
    }
}
