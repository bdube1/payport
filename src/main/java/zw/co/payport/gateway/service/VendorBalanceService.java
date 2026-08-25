package zw.co.payport.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.service.adaptor.esolutions.ESolutionsClient;
import zw.co.payport.gateway.service.adaptor.esolutions.ESolutionsResponse;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VendorBalanceService {

    private final ESolutionsClient esolutionsClient;

    /**
     * Get vendor balance from eSolutions
     *
     * @param currency Currency code (USD or ZWG) - if null, returns both currencies
     * @return Map containing balance information
     */
    public Map<String, Object> getBalance(String currency) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();

        if (currency == null || currency.isBlank()) {
            // Get both USD and ZWG balances with UNIQUE references
            Map<String, Object> usdBalance = getBalanceForCurrency("USD");
            Map<String, Object> zwgBalance = getBalanceForCurrency("ZWG");

            result.put("USD", usdBalance.get("balance"));
            result.put("ZWG", zwgBalance.get("balance"));
            result.put("currency", "MULTI");
            result.put("timestamp", LocalDateTime.now().toString());
            log.info("[BALANCE] Retrieved both balances - USD: {}, ZWG: {}",
                    usdBalance.get("balance"), zwgBalance.get("balance"));
        } else {
            // Get balance for specific currency
            Map<String, Object> balanceData = getBalanceForCurrency(currency.toUpperCase());
            result.put("balance", balanceData.get("balance"));
            result.put("currency", currency.toUpperCase());
            if (balanceData.containsKey("accountName")) {
                result.put("accountName", balanceData.get("accountName"));
                result.put("accountNumber", balanceData.get("accountNumber"));
            }
            result.put("timestamp", LocalDateTime.now().toString());
            log.info("[BALANCE] Retrieved {} balance: {}", currency.toUpperCase(), balanceData.get("balance"));
        }

        return result;
    }

    /**
     * Get balance for a specific currency from eSolutions
     */
    private Map<String, Object> getBalanceForCurrency(String currency) throws Exception {
        // Generate UNIQUE vendor reference for each request
        String vendorRef = buildUniqueVendorRef("BAL", currency);
        log.info("[BALANCE] Enquiry for currency: {}, vendorRef: {}", currency, vendorRef);

        ESolutionsResponse resp = esolutionsClient.balanceEnquiry(
                esolutionsClient.getVendorNumber(), currency, vendorRef);

        Map<String, Object> result = new LinkedHashMap<>();

        if (resp == null) {
            log.error("[BALANCE] Received null response from eSolutions for currency: {}", currency);
            result.put("success", false);
            result.put("balance", 0.0);
            result.put("currency", currency);
            result.put("error", "Null response from eSolutions");
            return result;
        }

        log.info("[BALANCE] Response code: {}, narrative: {}", resp.getResponseCode(), resp.getNarrative());

        if (resp.isSuccess()) {
            String vendorBalance = resp.getVendorBalance();

            if (vendorBalance != null && !vendorBalance.isBlank()) {
                String[] parts = vendorBalance.split("\\|");
                if (parts.length >= 4) {
                    result.put("accountName", parts[0]);
                    result.put("accountNumber", parts[1]);
                    result.put("currency", parts[2]);
                    result.put("balance", Double.parseDouble(parts[3]) / 100.0);
                    result.put("balanceCents", parts[3]);
                    log.info("[BALANCE] {} balance: {} ({} cents)", currency, result.get("balance"), parts[3]);
                } else {
                    result.put("balance", 0.0);
                    result.put("balanceCents", "0");
                    result.put("currency", currency);
                    log.warn("[BALANCE] Invalid vendor balance format for {}: {}", currency, vendorBalance);
                }
            } else {
                result.put("balance", 0.0);
                result.put("balanceCents", "0");
                result.put("currency", currency);
                log.warn("[BALANCE] No vendor balance data in response for {}", currency);
            }
            result.put("success", true);
            result.put("message", resp.getNarrative());
        } else {
            result.put("success", false);
            result.put("balance", 0.0);
            result.put("currency", currency);
            result.put("error", resp.getFailureMessage());
            log.warn("[BALANCE] Failed to get {} balance: {}", currency, resp.getFailureMessage());
        }

        return result;
    }

    /**
     * Generate a UNIQUE vendor reference for each request
     * Includes currency to ensure uniqueness between USD and ZWG requests
     */
    private String buildUniqueVendorRef(String prefix, String currency) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        String uniqueId = UUID.randomUUID().toString().substring(0, 6);
        return String.format("%s%s%s%s", prefix, currency, ts, uniqueId).substring(0, Math.min(20, prefix.length() + currency.length() + ts.length() + uniqueId.length()));
    }
}