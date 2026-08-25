package zw.co.payport.gateway.service.adaptor.telecel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TelecelSoapClient
 *
 * Sends raw SOAP/XML requests to the Telecel EstelTprServices endpoint.
 * Uses HttpURLConnection directly to give us full control over the SOAP envelope.
 * 
 * Supported Operations:
 * - getBalance (Check agent wallet balance)
 * - getTopup (Airtime recharge)
 * - getTransHistory (Transaction history)
 * - wallettransfer (Transfer between wallets)
 */
@Slf4j
@Component
public class TelecelSoapClient {

    @Value("${payport.telecel.endpoint}")
    private String endpoint;

    private static final int TIMEOUT_MS = 30000;

    // Status code descriptions
    private static final Map<String, String> RESULT_CODE_DESCRIPTIONS = new LinkedHashMap<>();
    static {
        RESULT_CODE_DESCRIPTIONS.put("0", "Success - Transaction completed successfully");
        RESULT_CODE_DESCRIPTIONS.put("100", "Success - Airtime recharge successful");
        RESULT_CODE_DESCRIPTIONS.put("101", "Insufficient Funds - Please fund your wallet");
        RESULT_CODE_DESCRIPTIONS.put("102", "Invalid Agent Code - Check your agent credentials");
        RESULT_CODE_DESCRIPTIONS.put("103", "Invalid MPIN - Check your MPIN");
        RESULT_CODE_DESCRIPTIONS.put("104", "Invalid Destination - Phone number not found");
        RESULT_CODE_DESCRIPTIONS.put("105", "Amount Limit Exceeded - Min/Max amount violation");
        RESULT_CODE_DESCRIPTIONS.put("106", "Duplicate Transaction - Reference already used");
        RESULT_CODE_DESCRIPTIONS.put("107", "System Error - Please try again later");
    }

    static {
        // Disable SSL verification for Telecel's self-signed cert
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] c, String a) {}
                        public void checkServerTrusted(X509Certificate[] c, String a) {}
                    }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            throw new RuntimeException("Failed to configure SSL bypass for Telecel", e);
        }
    }

    // ─── Public Methods ───────────────────────────────────────────────────────

    public TelecelBalanceResult getBalance(String agentCode, String mpin) {
        logBalanceRequest(agentCode);
        
        String soapBody = String.format(
                "<ser:getBalance>" +
                        "  <ser:balanceRequest>" +
                        "    <bal:agentCode>%s</bal:agentCode>" +
                        "    <bal:mpin>%s</bal:mpin>" +
                        "  </ser:balanceRequest>" +
                        "</ser:getBalance>",
                agentCode, mpin
        );

        try {
            String response = sendSoapRequest("getBalance", soapBody);
            return parseBalanceResponse(response);
        } catch (Exception e) {
            log.error("[TELECEL] getBalance failed: {}", e.getMessage());
            return TelecelBalanceResult.builder()
                    .success(false)
                    .resultCode("ERROR")
                    .resultDescription(e.getMessage())
                    .build();
        }
    }

    public TelecelTopupResult getTopup(String agentCode, String mpin,
                                       String destination, long amountCents,
                                       String agentTransId) {
        long amountDollars = amountCents / 100;
        
        logTopupRequest(agentCode, destination, amountDollars, agentTransId);

        String soapBody = String.format(
                "<ser:getTopup>" +
                        "  <ser:topupRequest>" +
                        "    <top:agentCode>%s</top:agentCode>" +
                        "    <top:mpin>%s</top:mpin>" +
                        "    <top:destination>%s</top:destination>" +
                        "    <top:amount>%d</top:amount>" +
                        "    <top:productCode>Core</top:productCode>" +
                        "    <top:agenttransid>%s</top:agenttransid>" +
                        "    <top:type>VOICE</top:type>" +
                        "  </ser:topupRequest>" +
                        "</ser:getTopup>",
                agentCode, mpin, destination, amountDollars, agentTransId
        );

        try {
            String response = sendSoapRequest("getTopup", soapBody);
            return parseTopupResponse(response);
        } catch (Exception e) {
            log.error("[TELECEL] getTopup failed: {}", e.getMessage());
            return TelecelTopupResult.builder()
                    .success(false)
                    .resultCode("ERROR")
                    .resultDescription(e.getMessage())
                    .rawResponse(e.getMessage())
                    .build();
        }
    }

    public TelecelTopupResult queryTransaction(String agentCode, String mpin, String agentTransId) {
        logQueryRequest(agentCode, agentTransId);
        
        String soapBody = String.format(
                "<ser:getTransHistory>" +
                        "  <ser:historyRequest>" +
                        "    <his:agentCode>%s</his:agentCode>" +
                        "    <his:mpin>%s</his:mpin>" +
                        "    <his:agenttransid>%s</his:agenttransid>" +
                        "  </ser:historyRequest>" +
                        "</ser:getTransHistory>",
                agentCode, mpin, agentTransId
        );

        try {
            String response = sendSoapRequest("getTransHistory", soapBody);
            return parseTopupResponse(response);
        } catch (Exception e) {
            log.error("[TELECEL] getTransHistory failed: {}", e.getMessage());
            return TelecelTopupResult.builder()
                    .success(false)
                    .resultCode("ERROR")
                    .resultDescription(e.getMessage())
                    .rawResponse(e.getMessage())
                    .build();
        }
    }

    public TelecelWalletTransferResult walletTransfer(String sourceAgentCode, String sourceMpin,
                                                       String destinationAgentCode, double amount,
                                                       String agentTransId, String comments) {
        logTransferRequest(sourceAgentCode, destinationAgentCode, amount, agentTransId);
        
        String soapBody = String.format(
                "<ser:wallettransfer>" +
                        "  <ser:wallettransferInfoRequest>" +
                        "    <wal:agentCode>%s</wal:agentCode>" +
                        "    <wal:mpin>%s</wal:mpin>" +
                        "    <wal:destination>%s</wal:destination>" +
                        "    <wal:amount>%f</wal:amount>" +
                        "    <wal:agenttransid>%s</wal:agenttransid>" +
                        "    <wal:comments>%s</wal:comments>" +
                        "  </ser:wallettransferInfoRequest>" +
                        "</ser:wallettransfer>",
                sourceAgentCode, sourceMpin, destinationAgentCode, amount, agentTransId,
                comments != null ? comments : ""
        );

        try {
            String response = sendSoapRequest("wallettransfer", soapBody);
            return parseWalletTransferResponse(response);
        } catch (Exception e) {
            log.error("[TELECEL] wallettransfer failed: {}", e.getMessage());
            return TelecelWalletTransferResult.builder()
                    .success(false)
                    .resultCode("ERROR")
                    .resultDescription(e.getMessage())
                    .rawResponse(e.getMessage())
                    .build();
        }
    }

    // ─── Logging Methods ──────────────────────────────────────────────────────

    private void logBalanceRequest(String agentCode) {
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    TELECEL BALANCE REQUEST                       ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Agent Code:    {}", agentCode);
        log.info("║ Operation:     getBalance");
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logTopupRequest(String agentCode, String destination, long amount, String ref) {
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    TELECEL TOPUP REQUEST                         ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Agent Code:    {}", agentCode);
        log.info("║ Destination:   {}", maskPhone(destination));
        log.info("║ Amount:        ${}.00", amount);
        log.info("║ Reference:     {}", ref);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logQueryRequest(String agentCode, String agentTransId) {
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    TELECEL QUERY REQUEST                         ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Agent Code:    {}", agentCode);
        log.info("║ Reference:     {}", agentTransId);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logTransferRequest(String source, String destination, double amount, String ref) {
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    TELECEL WALLET TRANSFER REQUEST               ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Source Agent:  {}", source);
        log.info("║ Destination:   {}", destination);
        log.info("║ Amount:        ${}", amount);
        log.info("║ Reference:     {}", ref);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logBalanceResponse(String resultCode, String resultDesc, String balance) {
        String statusIcon = "0".equals(resultCode) ? "✅ SUCCESS" : "⚠️ RESPONSE";
        String description = RESULT_CODE_DESCRIPTIONS.getOrDefault(resultCode, resultDesc);
        
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    TELECEL BALANCE RESPONSE                      ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Status:        {} (Code: {})", statusIcon, resultCode);
        log.info("║ Description:   {}", description);
        if (balance != null) {
            log.info("║ Balance:       {} (hex format)", balance);
        }
        log.info("║ Message:       {}", resultDesc);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logTopupResponse(String resultCode, String resultDesc, String transId) {
        boolean isSuccess = "100".equals(resultCode);
        String statusIcon = isSuccess ? "✅ SUCCESS" : "❌ FAILED";
        String description = RESULT_CODE_DESCRIPTIONS.getOrDefault(resultCode, resultDesc);
        
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    TELECEL TOPUP RESPONSE                        ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Status:        {} (Code: {})", statusIcon, resultCode);
        log.info("║ Description:   {}", description);
        if (transId != null) {
            log.info("║ Transaction ID: {}", transId);
        }
        log.info("║ Message:       {}", resultDesc);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private void logTransferResponse(String resultCode, String resultDesc, String transId, String balance) {
        boolean isSuccess = "0".equals(resultCode);
        String statusIcon = isSuccess ? "✅ SUCCESS" : "❌ FAILED";
        String description = RESULT_CODE_DESCRIPTIONS.getOrDefault(resultCode, resultDesc);
        
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║                    TELECEL WALLET TRANSFER RESPONSE              ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║ Status:        {} (Code: {})", statusIcon, resultCode);
        log.info("║ Description:   {}", description);
        if (transId != null) {
            log.info("║ Transfer ID:   {}", transId);
        }
        if (balance != null) {
            log.info("║ New Balance:   {}", balance);
        }
        log.info("║ Message:       {}", resultDesc);
        log.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    // ─── Raw SOAP HTTP ────────────────────────────────────────────────────────

    private String sendSoapRequest(String operation, String bodyContent) throws Exception {
        String soapEnvelope =
                "<soapenv:Envelope" +
                        "    xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"" +
                        "    xmlns:ser=\"http://services.estel.com\"" +
                        "    xmlns:top=\"http://topup.support.services.estel.com\"" +
                        "    xmlns:bal=\"http://balance.support.services.estel.com\"" +
                        "    xmlns:his=\"http://transhistory.support.services.estel.com\"" +
                        "    xmlns:wal=\"http://wallettransfer.support.services.estel.com\">" +
                        "  <soapenv:Header/>" +
                        "  <soapenv:Body>" +
                        bodyContent +
                        "  </soapenv:Body>" +
                        "</soapenv:Envelope>";

        log.debug("[TELECEL] Sending SOAP request to {} operation={}", endpoint, operation);

        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Content-Type", "text/xml;charset=UTF-8");
        conn.setRequestProperty("SOAPAction", "\"\"");
        conn.setRequestProperty("Accept", "text/xml");

        byte[] requestBytes = soapEnvelope.getBytes("UTF-8");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestBytes);
        }

        int httpCode = conn.getResponseCode();
        InputStream is = (httpCode >= 400) ? conn.getErrorStream() : conn.getInputStream();

        if (is == null) {
            throw new IOException("No response stream from Telecel (HTTP " + httpCode + ")");
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }

        return sb.toString();
    }

    // ─── Response Parsers ─────────────────────────────────────────────────────

    private TelecelBalanceResult parseBalanceResponse(String xml) {
        String resultCode = extractXmlValue(xml, "resultcode");
        String resultDesc = extractXmlValue(xml, "resultdescription");
        String balanceRaw = extractXmlValue(xml, "amount");

        boolean success = "0".equals(resultCode);
        
        logBalanceResponse(resultCode, resultDesc, balanceRaw);

        return TelecelBalanceResult.builder()
                .success(success)
                .resultCode(resultCode)
                .resultDescription(resultDesc)
                .balanceRaw(balanceRaw)
                .build();
    }

    private TelecelTopupResult parseTopupResponse(String xml) {
        String resultCode      = extractXmlValue(xml, "resultcode");
        String resultDesc      = extractXmlValue(xml, "resultdescription");
        String agentTransId    = extractXmlValue(xml, "agenttransid");
        String providerTransId = extractXmlValue(xml, "transid");
        String requestCts      = extractXmlValue(xml, "requestcts");

        boolean success = "100".equals(resultCode);
        String resolvedProviderRef = (providerTransId != null && !providerTransId.isEmpty())
                ? providerTransId : agentTransId;
        
        logTopupResponse(resultCode, resultDesc, resolvedProviderRef);

        return TelecelTopupResult.builder()
                .success(success)
                .resultCode(resultCode)
                .resultDescription(resultDesc)
                .agentTransId(agentTransId)
                .providerTransId(resolvedProviderRef)
                .requestTimestamp(requestCts)
                .rawResponse(xml)
                .build();
    }

    private TelecelWalletTransferResult parseWalletTransferResponse(String xml) {
        String resultCode      = extractXmlValue(xml, "resultCode");
        String resultDesc      = extractXmlValue(xml, "resultDescription");
        String transId         = extractXmlValue(xml, "transId");
        String walletBalance   = extractXmlValue(xml, "walletBalance");
        String preWalletBalance = extractXmlValue(xml, "prewalletBalance");

        boolean success = "0".equals(resultCode);
        
        logTransferResponse(resultCode, resultDesc, transId, walletBalance);

        return TelecelWalletTransferResult.builder()
                .success(success)
                .resultCode(resultCode)
                .resultDescription(resultDesc)
                .transId(transId)
                .walletBalance(walletBalance)
                .preWalletBalance(preWalletBalance)
                .rawResponse(xml)
                .build();
    }

    /**
     * Extracts the text content of an XML element regardless of namespace prefix.
     */
    private String extractXmlValue(String xml, String localName) {
        if (xml == null || xml.isEmpty()) return null;

        Pattern withNs = Pattern.compile(
                "<[a-zA-Z0-9_]*:" + localName + "[^>]*>([^<]*)</[a-zA-Z0-9_]*:" + localName + ">",
                Pattern.CASE_INSENSITIVE
        );
        Matcher m1 = withNs.matcher(xml);
        if (m1.find()) return m1.group(1).trim();

        Pattern withoutNs = Pattern.compile(
                "<" + localName + "[^>]*>([^<]*)</" + localName + ">",
                Pattern.CASE_INSENSITIVE
        );
        Matcher m2 = withoutNs.matcher(xml);
        if (m2.find()) return m2.group(1).trim();

        return null;
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) return "****";
        return phone.substring(0, 4) + "****" + phone.substring(phone.length() - 3);
    }

    // ─── Result DTOs ──────────────────────────────────────────────────────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TelecelBalanceResult {
        private boolean success;
        private String  resultCode;
        private String  resultDescription;
        private String  balanceRaw;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TelecelTopupResult {
        private boolean success;
        private String  resultCode;
        private String  resultDescription;
        private String  agentTransId;
        private String  providerTransId;
        private String  requestTimestamp;
        private String  rawResponse;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TelecelWalletTransferResult {
        private boolean success;
        private String  resultCode;
        private String  resultDescription;
        private String  transId;
        private String  walletBalance;
        private String  preWalletBalance;
        private String  rawResponse;
    }
}
