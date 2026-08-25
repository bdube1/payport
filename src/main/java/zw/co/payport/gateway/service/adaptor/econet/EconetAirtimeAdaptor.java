package zw.co.payport.gateway.service.adaptor.econet;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.xmlrpc.client.XmlRpcClient;
import org.apache.xmlrpc.client.XmlRpcClientConfigImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.service.adaptor.FulfilmentProviderAdaptor;

import java.net.URL;
import java.util.*;

@Slf4j
@Component
public class EconetAirtimeAdaptor implements FulfilmentProviderAdaptor {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${payport.econet.endpoint}")
    private String endpoint;

    @Value("${payport.econet.port}")
    private int port;

    @Value("${payport.econet.xmlrpc-path}")
    private String xmlrpcPath;

    @Value("${payport.econet.timeout}")
    private int timeout;

    @Value("${payport.econet.usd-username}")
    private String usdUsername;

    @Value("${payport.econet.usd-password}")
    private String usdPassword;

    @Value("${payport.econet.zwg-username}")
    private String zwgUsername;

    @Value("${payport.econet.zwg-password}")
    private String zwgPassword;

    @Value("${payport.econet.usd-currency-code}")
    private int usdCurrencyCode;

    @Value("${payport.econet.zwg-currency-code}")
    private int zwgCurrencyCode;

    @Value("${payport.econet.provider-code}")
    private int providerCode;

    @Override
    public String getProviderCode() {
        return "ECONET";
    }

    private String getUsername(String currency) {
        return "USD".equalsIgnoreCase(currency) ? usdUsername : zwgUsername;
    }

    private String getPassword(String currency) {
        return "USD".equalsIgnoreCase(currency) ? usdPassword : zwgPassword;
    }

    private int getCurrencyCode(String currency) {
        return "USD".equalsIgnoreCase(currency) ? usdCurrencyCode : zwgCurrencyCode;
    }

    private String getCurrencyName(int currencyCode) {
        return currencyCode == usdCurrencyCode ? "USD" : "ZWG";
    }

    @Override
    public FulfilmentResult deliver(FulfilmentRequest request) throws Exception {
        log.info("[ECONET] Delivering to {} for reference {}", 
                maskPhone(request.getRecipientIdentifier()), request.getPayportReference());

        String username = getUsername(request.getCurrency());
        String password = getPassword(request.getCurrency());
        int currencyCode = getCurrencyCode(request.getCurrency());
        String msisdn = normalisePhone(request.getRecipientIdentifier());
        int amountInCents = (int) request.getAmountCents();

        // Check if this is a DATA bundle by looking at productTypeCode
        String productTypeCode = request.getProductTypeCode();
        
        // Parse productConfig JSON if present
        Map<String, Object> configMap = null;
        if (request.getProductConfig() != null && !request.getProductConfig().isEmpty()) {
            try {
                configMap = objectMapper.readValue(request.getProductConfig(), Map.class);
            } catch (Exception e) {
                log.warn("[ECONET] Failed to parse productConfig: {}", e.getMessage());
            }
        }

        // Check if this is a DATA bundle (not airtime)
        if (productTypeCode != null && productTypeCode.startsWith("DATA_")) {
            // Extract product code from config
            String productCode = null;
            int quantity = 1;
            int accountType = 0;
            
            if (configMap != null) {
                if (configMap.containsKey("productCode")) {
                    productCode = (String) configMap.get("productCode");
                } else if (configMap.containsKey("bundleCode")) {
                    productCode = (String) configMap.get("bundleCode");
                }
                if (configMap.containsKey("quantity")) {
                    quantity = ((Number) configMap.get("quantity")).intValue();
                }
                if (configMap.containsKey("accountType")) {
                    accountType = ((Number) configMap.get("accountType")).intValue();
                }
            }
            
            if (productCode == null || productCode.isEmpty()) {
                log.error("[ECONET] Product code not found for bundle request");
                return FulfilmentResult.builder()
                        .success(false)
                        .message("Product code not configured for DATA_ECONET")
                        .rawResponse("Missing productCode in productConfig")
                        .build();
            }
            
            log.info("[ECONET] Purchasing bundle for MSISDN: {}, Product: {}, Amount: {} cents", 
                    maskPhone(msisdn), productCode, amountInCents);
            return deliverBundle(request, username, password, msisdn, amountInCents, currencyCode, 
                                productCode, quantity, accountType);
        }

        // Default: airtime delivery
        log.info("[ECONET] Delivering airtime to {}", maskPhone(msisdn));
        return deliverAirtime(request, username, password, msisdn, amountInCents, currencyCode);
    }

    private FulfilmentResult deliverAirtime(FulfilmentRequest request, String username, String password, 
                                            String msisdn, int amountInCents, int currencyCode) throws Exception {
        try {
            XmlRpcClient client = createXmlRpcClient();
            Map<String, Object> structParams = new LinkedHashMap<>();
            structParams.put("MSISDN", msisdn);
            structParams.put("Amount", amountInCents);
            structParams.put("Currency", currencyCode);
            structParams.put("Reference", request.getPayportReference());
            structParams.put("ProviderCode", providerCode);
            
            logLoadValueRequest(username, msisdn, amountInCents, currencyCode, request.getPayportReference());
            
            Object[] xmlRpcParams = new Object[]{username, password, structParams};
            Object response = client.execute("load_value", xmlRpcParams);
            
            logLoadValueResponse(response);
            return parseResponse(response, request.getPayportReference(), request.getCurrency());
        } catch (Exception e) {
            log.error("[ECONET] XML-RPC delivery failed: {}", e.getMessage(), e);
            return FulfilmentResult.builder()
                    .success(false)
                    .message("Econet delivery failed: " + e.getMessage())
                    .rawResponse(e.getMessage())
                    .build();
        }
    }

    private FulfilmentResult deliverBundle(FulfilmentRequest request, String username, String password,
                                           String msisdn, int amountInCents, int currencyCode,
                                           String productCode, int quantity, int accountType) throws Exception {
        try {
            XmlRpcClient client = createXmlRpcClient();
            Map<String, Object> structParams = new LinkedHashMap<>();
            structParams.put("MSISDN", msisdn);
            structParams.put("ProviderCode", providerCode);
            structParams.put("AccountType", accountType);
            structParams.put("Currency", currencyCode);
            structParams.put("Amount", amountInCents);
            structParams.put("ProductCode", productCode);
            structParams.put("Reference", request.getPayportReference());
            structParams.put("Quantity", quantity);
            
            logBundleRequest(username, msisdn, accountType, currencyCode, amountInCents, productCode, request.getPayportReference(), quantity);
            
            Object[] xmlRpcParams = new Object[]{username, password, structParams};
            Object response = client.execute("load_bundle", xmlRpcParams);
            
            logBundleResponse(response);
            
            Map<String, Object> responseMap = extractResponseMap(response);
            if (responseMap == null) {
                return FulfilmentResult.builder()
                        .success(false)
                        .message("Unexpected response format from Econet")
                        .rawResponse(response.toString())
                        .build();
            }
            
            Integer status = getIntFromMap(responseMap, "Status");
            Integer statusCode = getIntFromMap(responseMap, "StatusCode");
            String serial = getStringFromMap(responseMap, "Serial");
            String description = getStringFromMap(responseMap, "Description");
            
            boolean success = (status != null && status == 1) && (statusCode != null && statusCode == 0);
            
            if (success) {
                log.info("[ECONET] ✅ Bundle purchase successful - Serial: {}", serial);
            } else {
                log.warn("[ECONET] ❌ Bundle purchase failed - Status: {}, StatusCode: {}, Description: {}", status, statusCode, description);
            }
            
            return FulfilmentResult.builder()
                    .success(success)
                    .fulfilmentReference(serial != null ? serial : request.getPayportReference())
                    .message(description != null ? description : (success ? "Success" : "Failed"))
                    .rawResponse(response.toString())
                    .build();
                    
        } catch (Exception e) {
            log.error("[ECONET] Bundle purchase failed: {}", e.getMessage(), e);
            return FulfilmentResult.builder()
                    .success(false)
                    .message("Econet bundle purchase failed: " + e.getMessage())
                    .rawResponse(e.getMessage())
                    .build();
        }
    }

    private void logBundleRequest(String username, String msisdn, int accountType, int currencyCode, 
                                   int amount, String productCode, String reference, int quantity) {
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    ECONET LOAD BUNDLE REQUEST                    ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ MSISDN:        {}", msisdn);
        log.info("║ ProviderCode:  {}", providerCode);
        log.info("║ AccountType:   {} (0=IN, 1=Wallet)", accountType);
        log.info("║ Currency:      {} ({})", currencyCode, getCurrencyName(currencyCode));
        log.info("║ Amount:        {} cents", amount);
        log.info("║ ProductCode:   {}", productCode);
        log.info("║ Reference:     {}", reference);
        log.info("║ Quantity:      {}", quantity);
        log.info("║ Username:      {}", username);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logBundleResponse(Object response) {
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    ECONET LOAD BUNDLE RESPONSE                   ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Response: {}", response);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    // ============================================================
    // 1. ACCOUNT BALANCE ENQUIRY
    // ============================================================

    public BalanceResult getAccountBalance(String msisdn, String currency) throws Exception {
        String username = getUsername(currency);
        String password = getPassword(currency);
        String normalizedMsisdn = normalisePhone(msisdn);
        
        log.info("[ECONET] Checking account balance for MSISDN: {}", maskPhone(normalizedMsisdn));
        
        try {
            XmlRpcClient client = createXmlRpcClient();
            Map<String, Object> structParams = new LinkedHashMap<>();
            structParams.put("MSISDN", normalizedMsisdn);
            structParams.put("ProviderCode", providerCode);
            
            logBalanceRequest(username, normalizedMsisdn);
            
            Object[] xmlRpcParams = new Object[]{username, password, structParams};
            Object response = client.execute("account_balance_enquiry", xmlRpcParams);
            
            logBalanceResponse(response);
            return parseBalanceResponse(response);
        } catch (Exception e) {
            log.error("[ECONET] Balance enquiry failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    private void logBalanceRequest(String username, String msisdn) {
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                 ECONET ACCOUNT BALANCE REQUEST                   ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ MSISDN:        {}", msisdn);
        log.info("║ ProviderCode:  {}", providerCode);
        log.info("║ Username:      {}", username);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logBalanceResponse(Object response) {
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                 ECONET ACCOUNT BALANCE RESPONSE                   ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Response: {}", response);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    @SuppressWarnings("unchecked")
    private BalanceResult parseBalanceResponse(Object response) {
        BalanceResult result = new BalanceResult();
        
        try {
            List<Object> balanceEntries = new ArrayList<>();
            
            if (response instanceof Object[]) {
                // The actual shape being returned, per production logs --
                // a raw array of balance-entry Maps directly, e.g.
                // [{Currency=840, Amount=499580, AccountType=100}, ...].
                // The List-wrapping-a-nested-array branch below was the
                // only path checked, so this real shape fell through
                // silently every time -- accountType parsing never ran,
                // which is exactly why the balance always came back null
                // despite the correct data being right there in the log.
                for (Object obj : (Object[]) response) {
                    if (obj instanceof Map) balanceEntries.add(obj);
                }
            } else if (response instanceof List) {
                List<Object> params = (List<Object>) response;
                if (!params.isEmpty()) {
                    Object first = params.get(0);
                    if (first instanceof Object[]) {
                        for (Object obj : (Object[]) first) {
                            if (obj instanceof Map) balanceEntries.add(obj);
                        }
                    } else if (first instanceof List) {
                        for (Object obj : (List<Object>) first) {
                            if (obj instanceof Map) balanceEntries.add(obj);
                        }
                    } else if (first instanceof Map) {
                        balanceEntries.add(first);
                    }
                }
            }
            
            for (Object entry : balanceEntries) {
                if (entry instanceof Map) {
                    Map<String, Object> balanceMap = (Map<String, Object>) entry;
                    Integer accountType = getIntFromMap(balanceMap, "AccountType");
                    Integer amount = getIntFromMap(balanceMap, "Amount");
                    Integer currency = getIntFromMap(balanceMap, "Currency");
                    
                    if (accountType != null) {
                        if (accountType == 100) {
                            result.setCompanyAccountAmount(amount);
                            result.setCompanyAccountCurrency(currency);
                        } else if (accountType == 101) {
                            result.setAvailableAmount(amount);
                            result.setAvailableCurrency(currency);
                        } else if (accountType == 102) {
                            result.setUserAvailableAmount(amount);
                            result.setUserAvailableCurrency(currency);
                        }
                    }
                }
            }
            
            log.info("[ECONET] Balance - Company: {} cents (${}), Available: {} cents (${})", 
                    result.getCompanyAccountAmount(),
                    result.getCompanyAccountAmount() != null ? result.getCompanyAccountAmount() / 100.0 : 0,
                    result.getAvailableAmount(),
                    result.getAvailableAmount() != null ? result.getAvailableAmount() / 100.0 : 0);
                    
        } catch (Exception e) {
            log.error("[ECONET] Failed to parse balance response: {}", e.getMessage());
        }
        
        return result;
    }

    // ============================================================
    // 2. VALIDATE MSISDN
    // ============================================================

    public ValidationResult validateMsisdn(String msisdn, String currency) throws Exception {
        String username = getUsername(currency);
        String password = getPassword(currency);
        String normalizedMsisdn = normalisePhone(msisdn);
        
        log.info("[ECONET] Validating MSISDN: {}", maskPhone(normalizedMsisdn));
        
        try {
            XmlRpcClient client = createXmlRpcClient();
            Map<String, Object> structParams = new LinkedHashMap<>();
            structParams.put("MSISDN", normalizedMsisdn);
            structParams.put("ProviderCode", providerCode);
            
            logValidateRequest(username, normalizedMsisdn);
            
            Object[] xmlRpcParams = new Object[]{username, password, structParams};
            Object response = client.execute("validate_msisdn", xmlRpcParams);
            
            logValidateResponse(response);
            return parseValidationResponse(response);
        } catch (Exception e) {
            log.error("[ECONET] Validation failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    private void logValidateRequest(String username, String msisdn) {
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    ECONET VALIDATE MSISDN REQUEST                ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ MSISDN:        {}", msisdn);
        log.info("║ ProviderCode:  {}", providerCode);
        log.info("║ Username:      {}", username);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logValidateResponse(Object response) {
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    ECONET VALIDATE MSISDN RESPONSE                ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Response: {}", response);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    @SuppressWarnings("unchecked")
    private ValidationResult parseValidationResponse(Object response) {
        Map<String, Object> responseMap = extractResponseMap(response);
        if (responseMap == null) {
            return new ValidationResult(false, -1, "Failed to parse response");
        }
        
        Integer status = getIntFromMap(responseMap, "Status");
        Integer statusCode = getIntFromMap(responseMap, "StatusCode");
        String description = getStringFromMap(responseMap, "Description");
        
        boolean isValid = (status != null && status == 1) && (statusCode != null && statusCode == 0);
        return new ValidationResult(isValid, statusCode != null ? statusCode : -1, description);
    }

    // ============================================================
    // 3. LOAD AIRTIME (load_value)
    // ============================================================

    private void logLoadValueRequest(String username, String msisdn, int amount, int currencyCode, String reference) {
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    ECONET LOAD VALUE REQUEST                     ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ MSISDN:        {}", msisdn);
        log.info("║ ProviderCode:  {}", providerCode);
        log.info("║ Amount:        {} cents", amount);
        log.info("║ Currency:      {} ({})", currencyCode, getCurrencyName(currencyCode));
        log.info("║ Reference:     {}", reference);
        log.info("║ Username:      {}", username);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logLoadValueResponse(Object response) {
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    ECONET LOAD VALUE RESPONSE                    ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Response: {}", response);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    // ============================================================
    // 4. TRANSACTION STATUS ENQUIRY
    // ============================================================

    public TransactionStatusResult getTransactionStatus(String reference, String currency) throws Exception {
        String username = getUsername(currency);
        String password = getPassword(currency);
        
        log.info("[ECONET] Getting transaction status for reference: {}", reference);
        
        try {
            XmlRpcClient client = createXmlRpcClient();
            Map<String, Object> structParams = new LinkedHashMap<>();
            structParams.put("Reference", reference);
            
            logTransactionStatusRequest(username, reference);
            
            Object[] xmlRpcParams = new Object[]{username, password, structParams};
            Object response = client.execute("get_transaction_status", xmlRpcParams);
            
            logTransactionStatusResponse(response);
            return parseTransactionStatusResponse(response);
        } catch (Exception e) {
            log.error("[ECONET] Transaction status enquiry failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    private void logTransactionStatusRequest(String username, String reference) {
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║              ECONET TRANSACTION STATUS REQUEST                   ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Reference:     {}", reference);
        log.info("║ Username:      {}", username);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logTransactionStatusResponse(Object response) {
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║              ECONET TRANSACTION STATUS RESPONSE                  ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Response: {}", response);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private TransactionStatusResult parseTransactionStatusResponse(Object response) {
        Map<String, Object> responseMap = extractResponseMap(response);
        if (responseMap == null) {
            return new TransactionStatusResult(false, -1, null, "Failed to parse response");
        }
        
        Integer status = getIntFromMap(responseMap, "Status");
        Integer statusCode = getIntFromMap(responseMap, "StatusCode");
        String serial = getStringFromMap(responseMap, "Serial");
        String description = getStringFromMap(responseMap, "Description");
        
        boolean isSuccess = (status != null && status == 1) && (statusCode != null && statusCode == 0);
        return new TransactionStatusResult(isSuccess, statusCode, serial, description);
    }

    // ============================================================
    // 5. ACCOUNT TRANSFER
    // ============================================================

    public TransferResult accountTransfer(TransferRequest transferRequest) throws Exception {
        String currency = transferRequest.getCurrency();
        String username = getUsername(currency);
        String password = getPassword(currency);
        int currencyCode = getCurrencyCode(currency);
        
        log.info("[ECONET] Account transfer - From: {}, To: {}, Amount: {} cents", 
                transferRequest.getFromCompanyId(), transferRequest.getToCompanyId(), transferRequest.getAmount());
        
        try {
            XmlRpcClient client = createXmlRpcClient();
            Map<String, Object> structParams = new LinkedHashMap<>();
            structParams.put("FromCompanyID", transferRequest.getFromCompanyId());
            structParams.put("ToCompanyID", transferRequest.getToCompanyId());
            structParams.put("Currency", currencyCode);
            structParams.put("Amount", transferRequest.getAmount());
            structParams.put("Reference", transferRequest.getReference());
            
            logTransferRequest(transferRequest, username, currencyCode);
            
            Object[] xmlRpcParams = new Object[]{username, password, structParams};
            Object response = client.execute("account_transfer", xmlRpcParams);
            
            logTransferResponse(response);
            return parseTransferResponse(response);
        } catch (Exception e) {
            log.error("[ECONET] Account transfer failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    private void logTransferRequest(TransferRequest req, String username, int currencyCode) {
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    ECONET ACCOUNT TRANSFER REQUEST               ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ FromCompanyID: {}", req.getFromCompanyId());
        log.info("║ ToCompanyID:   {}", req.getToCompanyId());
        log.info("║ Currency:      {} ({})", currencyCode, getCurrencyName(currencyCode));
        log.info("║ Amount:        {} cents", req.getAmount());
        log.info("║ Reference:     {}", req.getReference());
        log.info("║ Username:      {}", username);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logTransferResponse(Object response) {
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    ECONET ACCOUNT TRANSFER RESPONSE              ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Response: {}", response);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private TransferResult parseTransferResponse(Object response) {
        Map<String, Object> responseMap = extractResponseMap(response);
        if (responseMap == null) {
            return new TransferResult(false, -1, null, "Failed to parse response");
        }
        
        Integer status = getIntFromMap(responseMap, "Status");
        Integer statusCode = getIntFromMap(responseMap, "StatusCode");
        String serial = getStringFromMap(responseMap, "Serial");
        String description = getStringFromMap(responseMap, "Description");
        
        boolean success = (status != null && status == 1) && (statusCode != null && statusCode == 0);
        return new TransferResult(success, statusCode, serial, description);
    }

    @Override
    public FulfilmentStatusResult queryDelivery(String fulfilmentReference) throws Exception {
        return FulfilmentStatusResult.builder()
                .status("UNKNOWN")
                .fulfilmentReference(fulfilmentReference)
                .message("Status query not implemented for Econet")
                .build();
    }

    // ============================================================
    // HELPER METHODS
    // ============================================================

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractResponseMap(Object response) {
        if (response instanceof Map) {
            return (Map<String, Object>) response;
        } else if (response instanceof List) {
            List<Object> params = (List<Object>) response;
            if (!params.isEmpty() && params.get(0) instanceof Map) {
                return (Map<String, Object>) params.get(0);
            }
        } else if (response instanceof Object[]) {
            Object[] arr = (Object[]) response;
            if (arr.length > 0 && arr[0] instanceof Map) {
                return (Map<String, Object>) arr[0];
            }
        }
        return null;
    }

    private FulfilmentResult parseResponse(Object response, String fallbackRef, String currency) {
        Map<String, Object> responseMap = extractResponseMap(response);
        if (responseMap == null) {
            return FulfilmentResult.builder()
                    .success(false)
                    .message("Unexpected response format from Econet")
                    .rawResponse(response.toString())
                    .build();
        }
        
        Integer status = getIntFromMap(responseMap, "Status");
        Integer statusCode = getIntFromMap(responseMap, "StatusCode");
        String serial = getStringFromMap(responseMap, "Serial");
        String description = getStringFromMap(responseMap, "Description");
        
        boolean success = (status != null && status == 1) && (statusCode != null && statusCode == 0);
        
        if (success) {
            log.info("[ECONET] ✅ Delivery successful - Serial: {}", serial);
        } else {
            log.warn("[ECONET] ❌ Delivery failed - Status: {}, StatusCode: {}, Description: {}", status, statusCode, description);
        }

        // A duplicate response (8060/8211/8206) doesn't mean this
        // attempt failed -- it means Econet has already seen this exact
        // reference before, which is exactly what their own duplicate
        // protection is designed to catch on a retry. Blindly recording
        // it as a fresh failure risks reporting "failed" on something
        // that may have already actually succeeded. Resolve it properly
        // via their own Transaction Status Enquiry (get_transaction_status)
        // -- built specifically, per their spec, "for the case where the
        // submitted transaction request timed-out and the final status
        // are unknown", which is exactly this situation -- rather than
        // guessing either way.
        if (!success && statusCode != null && (statusCode == 8060 || statusCode == 8211 || statusCode == 8206)) {
            log.info("[ECONET] Duplicate response for {} -- querying actual status before deciding the outcome", fallbackRef);
            try {
                TransactionStatusResult queryResult = getTransactionStatus(fallbackRef, currency);
                log.info("[ECONET] Duplicate resolution for {} -- query says success={}, statusCode={}",
                        fallbackRef, queryResult.isSuccess(), queryResult.getStatusCode());

                if (queryResult.isSuccess()) {
                    return FulfilmentResult.builder()
                            .success(true)
                            .fulfilmentReference(queryResult.getSerial() != null ? queryResult.getSerial() : fallbackRef)
                            .message(queryResult.getDescription() != null ? queryResult.getDescription() : "Success")
                            .rawResponse(response.toString() + " | Resolved via status query: " + queryResult.getDescription())
                            .build();
                }
                // Query itself confirms it genuinely failed -- fall
                // through to the normal failure path below, using the
                // query's own code/description since it's the more
                // authoritative, current answer.
                statusCode = queryResult.getStatusCode();
                description = queryResult.getDescription();
            } catch (Exception e) {
                // Couldn't resolve it either way -- don't guess. Falls
                // through to the normal failure path below with the
                // original duplicate message, which already tells ops
                // this needs a manual check ("already been processed").
                log.error("[ECONET] Could not resolve duplicate status for {}: {}", fallbackRef, e.getMessage(), e);
            }
        }

        // Was always the raw Description text, e.g. "(8030) IN failure"
        // -- unhelpful and matched nothing in the generic text-pattern
        // translator downstream. This uses the exact numeric StatusCode
        // against Econet's own published table first, only falling back
        // to the raw description if the code isn't one we have a
        // translation for.
        String customerMessage = success ? null : translateEconetStatusCode(statusCode, description);

        return FulfilmentResult.builder()
                .success(success)
                .fulfilmentReference(serial != null ? serial : fallbackRef)
                .message(success ? (description != null ? description : "Success") : description)
                .customerMessage(success ? null : customerMessage)
                .rawResponse(response.toString())
                .build();
    }

    /**
     * Exact code-based translation using Econet's own published status
     * code table (Emerald XMLRPC Interface Spec v1.2.3, section 7.1) --
     * far more reliable than matching against the free-text Description
     * field, which is often generic or unhelpful (e.g. "(8030) IN
     * failure" matches nothing useful as text, but if the code were
     * documented we'd know exactly what it meant). Codes genuinely not
     * in the published table (8030 is one -- it falls between the
     * documented 8024 and 8031 but isn't itself listed) fall through to
     * the generic message rather than guessing at an undocumented code's
     * meaning.
     *
     * Deliberately distinguishes customer-caused problems (specific,
     * actionable message) from Payport/Econet-side account or
     * infrastructure problems (generic "try again" message) -- codes
     * like 905/8104 ("Insufficient Credit"/"Insufficient Funds") refer to
     * Payport's own Econet float account, not the customer's mobile
     * money balance, since the customer already paid Payport directly
     * before this delivery step even runs. Telling a customer "your
     * balance is low" for a problem that's actually Payport's own
     * account would be both wrong and confusing.
     */
    private String translateEconetStatusCode(Integer statusCode, String rawDescription) {
        if (statusCode == null) return null;

        return switch (statusCode) {
            // Customer-actionable -- specific enough to tell them what to do differently.
            case 907, 8009 -> "The amount entered isn't valid for this transaction.";
            case 8031 -> "This amount is below the minimum allowed for this transaction.";
            case 8032 -> "This amount exceeds the maximum allowed for this transaction.";
            case 906 -> "This transaction exceeds a limit on this account.";
            case 8063 -> "This exceeds the monthly transaction amount limit.";
            case 8064 -> "The daily transaction count limit has been reached.";
            case 8065 -> "The monthly transaction count limit has been reached.";
            case 8060, 8211, 8206 -> "This transaction has already been processed.";
            case 8062 -> "This request has expired. Please try again.";
            case 8002, 8213 -> "This number isn't registered with Econet.";
            case 990, 8210 -> "We didn't receive a response in time. Please check your balance before trying again, in case this was still charged.";

            // Payport-side account/config or Econet-side infrastructure
            // problems -- never implies the customer's own money or
            // balance is the issue.
            case 702, 703, 704, 901, 902, 903, 970, 971, 972, 8214, 8215 ->
                    "There's a temporary problem with this payment method. Our team has been notified.";
            case 905, 8104, 8212 ->
                    "This payment method is temporarily unavailable. Please try again shortly.";
            case 8200, 8201, 8202, 8205, 8207 ->
                    "Econet's system is temporarily unavailable. Please try again shortly.";
            case 8208 -> "This transaction is still being processed by Econet.";
            case 8209 -> "This product is temporarily out of stock.";
            case 8061 -> "This transaction is still pending with Econet.";

            // Genuinely undocumented in Econet's own published spec (e.g.
            // 8030) -- rather than invent a meaning, fall through to null
            // so the caller uses the safe generic message instead.
            default -> null;
        };
    }

    private XmlRpcClient createXmlRpcClient() throws Exception {
        XmlRpcClientConfigImpl config = new XmlRpcClientConfigImpl();
        String urlStr = String.format("http://%s:%d%s", endpoint, port, xmlrpcPath);
        config.setServerURL(new URL(urlStr));
        config.setConnectionTimeout(timeout);
        config.setReplyTimeout(timeout);
        
        XmlRpcClient client = new XmlRpcClient();
        client.setConfig(config);
        return client;
    }

    private String normalisePhone(String phone) {
        if (phone == null || phone.trim().isEmpty()) return "";
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return "";
        if (digits.startsWith("263")) return digits;
        if (digits.startsWith("0") && digits.length() == 10) return "263" + digits.substring(1);
        if (digits.length() == 9) return "263" + digits;
        if (digits.length() > 9) return "263" + digits.substring(digits.length() - 9);
        return digits;
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.isEmpty()) return "****";
        String cleaned = phone.replaceAll("[^0-9]", "");
        if (cleaned.length() < 6) return "****";
        return cleaned.substring(0, 4) + "****" + cleaned.substring(cleaned.length() - 3);
    }

    private Integer getIntFromMap(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        if (val instanceof Integer) return (Integer) val;
        if (val instanceof String) {
            try { return Integer.parseInt((String) val); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private String getStringFromMap(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    // ============================================================
    // INNER CLASSES
    // ============================================================

    public static class BalanceResult {
        private Integer companyAccountAmount;
        private Integer companyAccountCurrency;
        private Integer availableAmount;
        private Integer availableCurrency;
        private Integer userAvailableAmount;
        private Integer userAvailableCurrency;

        public Integer getCompanyAccountAmount() { return companyAccountAmount; }
        public void setCompanyAccountAmount(Integer amount) { this.companyAccountAmount = amount; }
        public Integer getCompanyAccountCurrency() { return companyAccountCurrency; }
        public void setCompanyAccountCurrency(Integer currency) { this.companyAccountCurrency = currency; }
        public Integer getAvailableAmount() { return availableAmount; }
        public void setAvailableAmount(Integer amount) { this.availableAmount = amount; }
        public Integer getAvailableCurrency() { return availableCurrency; }
        public void setAvailableCurrency(Integer currency) { this.availableCurrency = currency; }
        public Integer getUserAvailableAmount() { return userAvailableAmount; }
        public void setUserAvailableAmount(Integer amount) { this.userAvailableAmount = amount; }
        public Integer getUserAvailableCurrency() { return userAvailableCurrency; }
        public void setUserAvailableCurrency(Integer currency) { this.userAvailableCurrency = currency; }
    }

    public static class ValidationResult {
        private final boolean valid;
        private final int statusCode;
        private final String description;
        public ValidationResult(boolean valid, int statusCode, String description) {
            this.valid = valid; this.statusCode = statusCode; this.description = description;
        }
        public boolean isValid() { return valid; }
        public int getStatusCode() { return statusCode; }
        public String getDescription() { return description; }
    }

    public static class BundleRequest {
        private final String msisdn;
        private final int amountCents;
        private final String currency;
        private final String productCode;
        private final String reference;
        private final int accountType;
        private final int quantity;
        public BundleRequest(String msisdn, int amountCents, String currency, String productCode, String reference, int accountType, int quantity) {
            this.msisdn = msisdn; this.amountCents = amountCents; this.currency = currency;
            this.productCode = productCode; this.reference = reference; this.accountType = accountType; this.quantity = quantity;
        }
        public String getMsisdn() { return msisdn; }
        public int getAmountCents() { return amountCents; }
        public String getCurrency() { return currency; }
        public String getProductCode() { return productCode; }
        public String getReference() { return reference; }
        public int getAccountType() { return accountType; }
        public int getQuantity() { return quantity; }
    }

    public static class BundleResult {
        private final boolean success;
        private final int statusCode;
        private final String serial;
        private final String providerReference;
        private final String description;
        public BundleResult(boolean success, int statusCode, String serial, String providerReference, String description) {
            this.success = success; this.statusCode = statusCode; this.serial = serial;
            this.providerReference = providerReference; this.description = description;
        }
        public boolean isSuccess() { return success; }
        public int getStatusCode() { return statusCode; }
        public String getSerial() { return serial; }
        public String getProviderReference() { return providerReference; }
        public String getDescription() { return description; }
    }

    public static class TransactionStatusResult {
        private final boolean success;
        private final int statusCode;
        private final String serial;
        private final String description;
        public TransactionStatusResult(boolean success, int statusCode, String serial, String description) {
            this.success = success; this.statusCode = statusCode; this.serial = serial; this.description = description;
        }
        public boolean isSuccess() { return success; }
        public int getStatusCode() { return statusCode; }
        public String getSerial() { return serial; }
        public String getDescription() { return description; }
    }

    public static class TransferRequest {
        private final int fromCompanyId;
        private final int toCompanyId;
        private final int amount;
        private final String currency;
        private final String reference;
        public TransferRequest(int fromCompanyId, int toCompanyId, int amount, String currency, String reference) {
            this.fromCompanyId = fromCompanyId; this.toCompanyId = toCompanyId; this.amount = amount; this.currency = currency; this.reference = reference;
        }
        public int getFromCompanyId() { return fromCompanyId; }
        public int getToCompanyId() { return toCompanyId; }
        public int getAmount() { return amount; }
        public String getCurrency() { return currency; }
        public String getReference() { return reference; }
    }

    public static class TransferResult {
        private final boolean success;
        private final int statusCode;
        private final String serial;
        private final String description;
        public TransferResult(boolean success, int statusCode, String serial, String description) {
            this.success = success; this.statusCode = statusCode; this.serial = serial; this.description = description;
        }
        public boolean isSuccess() { return success; }
        public int getStatusCode() { return statusCode; }
        public String getSerial() { return serial; }
        public String getDescription() { return description; }
    }
}
