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
            for (String c : new String[] { "USD", "ZWG" }) {
                try {
                    result.put(c, getBalanceForCurrency(c).get("balance"));
                } catch (IllegalStateException e) {
                    result.put(c, null);
                    result.put(c + "_error", e.getMessage());
                }
            }
            result.put("currency", "MULTI");
            result.put("timestamp", LocalDateTime.now().toString());
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

        // A balance is shown only when eSolutions returned one; anything else is an error, never a $0.00.
        if (resp == null) {
            log.error("[BALANCE] Received null response from eSolutions for currency: {}", currency);
            throw new IllegalStateException("eSolutions " + currency + ": no response");
        }
        log.info("[BALANCE] Response code: {}, narrative: {}", resp.getResponseCode(), resp.getNarrative());
        if (!resp.isSuccess()) {
            log.warn("[BALANCE] Failed to get {} balance: {}", currency, resp.getFailureMessage());
            throw new IllegalStateException("eSolutions " + currency + ": " + resp.getFailureMessage());
        }
        String vendorBalance = resp.getVendorBalance();
        String[] parts = vendorBalance == null ? new String[0] : vendorBalance.split("\\|");
        if (parts.length < 4) {
            log.warn("[BALANCE] Invalid vendor balance format for {}: {}", currency, vendorBalance);
            throw new IllegalStateException("eSolutions " + currency + ": no balance in the response");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountName", parts[0]);
        result.put("accountNumber", parts[1]);
        result.put("currency", parts[2]);
        try {
            result.put("balance", Double.parseDouble(parts[3].trim()) / 100.0);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("eSolutions " + currency + ": unreadable balance");
        }
        result.put("balanceCents", parts[3].trim());
        result.put("success", true);
        result.put("message", resp.getNarrative());
        log.info("[BALANCE] {} balance: {} ({} cents)", currency, result.get("balance"), parts[3]);
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