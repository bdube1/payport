package zw.co.payport.gateway.service.adaptor.esolutions;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;

/**
 * Shared HTTP client for all e-Solutions Bill Payments API calls.
 *
 * Authentication : HTTP Basic Auth (username + password)
 * Base URL       : configured in application.properties
 * API Version    : "02" (mandatory for V2.x messages)
 * Timeout        : 60s (as recommended by spec for resend scenarios)
 *
 * CRITICAL: The spec requires a resend/lookup pattern for timed-out transactions.
 * All adaptors using this client must implement resend logic (mti 0201 → 0211).
 */
@Slf4j
@Component
public class ESolutionsClient {

    @Value("${payport.esolutions.base-url}")
    private String baseUrl;

    @Value("${payport.esolutions.username}")
    private String username;

    @Value("${payport.esolutions.password}")
    private String password;

    @Value("${payport.esolutions.vendor-number}")
    private String vendorNumber;

    @Value("${payport.esolutions.account-number:}")
    private String accountNumber;

    @Value("${payport.esolutions.terminal-id:POS001}")
    private String terminalId;

    @Value("${payport.esolutions.timeout-seconds:60}")
    private int timeoutSeconds;

    private static final String API_VERSION = "02";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMddyyHHmmss");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    // ─── Public API ───────────────────────────────────────────────────────────

    /** Customer Information lookup (processingCode 310000) */
    public ESolutionsResponse customerInfo(String merchantName, String productName,
                                           String utilityAccount, String currencyCode,
                                           long amountCents, String vendorRef) throws Exception {
        Map<String, Object> body = buildBase(vendorRef, "310000", amountCents, currencyCode);
        body.put("merchantName",   merchantName);
        body.put("productName",    productName);
        body.put("utilityAccount", utilityAccount);
        return post("/billpayments/vend", body);
    }

    /** Token/Voucher purchase (processingCode U50000) - amount in cents */
    public ESolutionsResponse purchase(String merchantName, String productName,
                                       String utilityAccount, String sourceMobile,
                                       long amountCents, String currencyCode,
                                       String vendorRef, String customerData) throws Exception {
        Map<String, Object> body = buildBase(vendorRef, "U50000", amountCents, currencyCode);
        body.put("merchantName",   merchantName);
        body.put("productName",    productName);
        body.put("utilityAccount", utilityAccount);
        body.put("terminalID",     terminalId);
        if (sourceMobile != null) body.put("sourceMobile", sourceMobile);
        if (customerData != null) body.put("customerData", customerData);
        return post("/billpayments/vend", body);
    }

    /**
     * Token/Voucher purchase (processingCode U50000) - amount in DOLLARS (for Nyaradzo)
     * eSolutions expects amount in dollars for Nyaradzo, not cents
     */
    public ESolutionsResponse purchaseWithDollars(String merchantName, String productName,
                                                  String utilityAccount, String sourceMobile,
                                                  double amountDollars, String currencyCode,
                                                  String vendorRef, String customerData) throws Exception {
        // For Nyaradzo, we need to send amount in dollars (as double)
        Map<String, Object> body = buildBaseWithDollars(vendorRef, "U50000", amountDollars, currencyCode);
        body.put("merchantName",   merchantName);
        body.put("productName",    productName);
        body.put("utilityAccount", utilityAccount);
        body.put("terminalID",     terminalId);
        if (sourceMobile != null) body.put("sourceMobile", sourceMobile);
        if (customerData != null) body.put("customerData", customerData);
        return post("/billpayments/vend", body);
    }

    /** Generic payment (processingCode 520000) — DStv, ZOL, Bulawayo etc */
    public ESolutionsResponse payment(String merchantName, String productName,
                                      String utilityAccount, String sourceMobile,
                                      long amountCents, String currencyCode,
                                      String vendorRef, String customerData) throws Exception {
        Map<String, Object> body = buildBase(vendorRef, "520000", amountCents, currencyCode);
        body.put("merchantName",   merchantName);
        body.put("productName",    productName);
        body.put("utilityAccount", utilityAccount);
        if (sourceMobile != null) body.put("sourceMobile", sourceMobile);
        if (customerData != null) body.put("customerData", customerData);
        return post("/billpayments/vend", body);
    }

    /**
     * Resend / Lookup (mti 0201) — CRITICAL for ZESA and all token merchants.
     * Must be called when original request times out. Never conclude failure
     * without a resend for ZESA — every generated token draws vendor balance.
     */
    public ESolutionsResponse resend(String merchantName, String productName,
                                     String utilityAccount, long amountCents,
                                     String currencyCode, String originalVendorRef,
                                     String newVendorRef) throws Exception {
        Map<String, Object> body = buildBase(newVendorRef, "U50000", amountCents, currencyCode);
        body.put("mti",               "0201");
        body.put("originalReference", originalVendorRef);
        body.put("merchantName",      merchantName);
        body.put("productName",       productName);
        body.put("utilityAccount",    utilityAccount);
        return post("/billpayments/vend", body);
    }

    /**
     * Vendor balance enquiry (processingCode 300000) - spec section 4.2.1
     *
     * Per spec, the request MUST include:
     * - mti: "0200"
     * - processingCode: "300000"
     * - vendorNumber: Your assigned vendor number
     * - accountNumber: Your account number with eSolutions
     * - currencyCode: USD or ZWG (optional, defaults to account currency)
     * - vendorReference: Unique reference for this request
     * - transmissionDate: Current date/time in format MMDDHHmmss
     * - apiVersion: "02" for V2.x messages
     *
     * Sample request from spec:
     * {
     *   "mti": "0200",
     *   "vendorReference": "198909855445443",
     *   "processingCode": "300000",
     *   "transactionAmount": 0,
     *   "transmissionDate": "91916182800",
     *   "vendorNumber": "VE19217695801",
     *   "accountNumber": "0119217695806",
     *   "currencyCode": "USD"
     * }
     *
     * Sample response:
     * {
     *   "mti": "0210",
     *   "responseCode": "00",
     *   "vendorBalance": "Muk Plc|0119217695806|USD|361260",
     *   "narrative": "Transaction Successful"
     * }
     *
     * @param vendorNumber The vendor number (e.g., VE19257147501)
     * @param currencyCode Currency code (USD or ZWG) - optional per spec
     * @param vendorRef Unique vendor reference for this request
     * @return ESolutionsResponse containing vendorBalance
     */
    public ESolutionsResponse balanceEnquiry(String vendorNumber, String currencyCode, String vendorRef) throws Exception {
        // Build base request with processingCode "300000" for balance enquiry
        Map<String, Object> body = buildBase(vendorRef, "300000", 0, currencyCode);

        // REQUIRED: vendorNumber - your assigned vendor number
        body.put("vendorNumber", vendorNumber);

        // REQUIRED: accountNumber - your account number with eSolutions
        // Must use the specific account number provided by eSolutions, not the vendor number
        String accNumber = (accountNumber != null && !accountNumber.isBlank()) ? accountNumber : vendorNumber;
        body.put("accountNumber", accNumber);

        // Log the request for debugging and audit (without sensitive data)
        log.info("[eSolutions] Balance enquiry - currency: {}, vendorRef: {}, vendorNumber: {}, accountNumber: {}",
                currencyCode != null ? currencyCode : "default", vendorRef, vendorNumber, accNumber);
        log.debug("[eSolutions] Balance request body: {}", body);

        ESolutionsResponse response = post("/billpayments/vend", body);

        // Log response summary for audit
        if (response.isSuccess()) {
            log.info("[eSolutions] Balance enquiry successful - responseCode: {}", response.getResponseCode());
        } else {
            log.warn("[eSolutions] Balance enquiry failed - responseCode: {}, narrative: {}",
                    response.getResponseCode(), response.getNarrative());
        }

        return response;
    }

    /**
     * Vendor balance enquiry with default vendor number from config
     */
    public ESolutionsResponse balanceEnquiry(String currencyCode) throws Exception {
        String vendorRef = buildVendorRef("BAL");
        return balanceEnquiry(this.vendorNumber, currencyCode, vendorRef);
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    /**
     * Build base request for eSolutions API
     *
     * @param vendorRef Unique vendor reference for this request
     * @param processingCode Transaction type (300000=balance, 310000=customer info, U50000=purchase)
     * @param amountCents Amount in cents (0 for balance enquiry)
     * @param currencyCode Currency code (USD or ZWG)
     * @return Map containing the base request body
     */
    private Map<String, Object> buildBase(String vendorRef, String processingCode,
                                          long amountCents, String currencyCode) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("mti",              "0200");
        m.put("vendorReference",  vendorRef);
        m.put("processingCode",   processingCode);
        m.put("transactionAmount", amountCents);
        m.put("transmissionDate", LocalDateTime.now().format(DATE_FMT));
        m.put("vendorNumber",     this.vendorNumber);
        //m.put("apiVersion",       API_VERSION);

        // currencyCode is optional per spec - only add if provided
        if (currencyCode != null && !currencyCode.isBlank()) {
            m.put("currencyCode", currencyCode);
        }

        return m;
    }

    /**
     * Build base request with amount in DOLLARS (for Nyaradzo)
     */
    private Map<String, Object> buildBaseWithDollars(String vendorRef, String processingCode,
                                                     double amountDollars, String currencyCode) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("mti",              "0200");
        m.put("vendorReference",  vendorRef);
        m.put("processingCode",   processingCode);
        m.put("transactionAmount", amountDollars);
        m.put("transmissionDate", LocalDateTime.now().format(DATE_FMT));
        m.put("vendorNumber",     this.vendorNumber);
        m.put("apiVersion",       API_VERSION);

        if (currencyCode != null && !currencyCode.isBlank()) {
            m.put("currencyCode", currencyCode);
        }

        return m;
    }

    private String buildVendorRef(String prefix) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String p = prefix.replaceAll("[^A-Za-z0-9]", "").substring(0, Math.min(prefix.length(), 4));
        return (p + ts).substring(0, Math.min(p.length() + ts.length(), 20));
    }

    public ESolutionsResponse post(String path, Map<String, Object> body) throws Exception {
        String json = mapper.writeValueAsString(body);
        log.debug("[eSolutions] → {} : {}", path, json);

        String auth = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type",  "application/json")
                .header("Authorization", "Basic " + auth)
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        String responseBody = response.body();
        log.debug("[eSolutions] ← {} {} : {}", path, response.statusCode(), responseBody);

        if (response.statusCode() != 200) {
            // The raw response can be a full HTML error page (nginx/gateway
            // outages return one) -- that's already logged above in full for
            // debugging, but it must never be the message shown to a user.
            // Anyone catching this exception and surfacing e.getMessage()
            // directly (as VendorBalanceController does) would otherwise
            // dump raw HTML markup onto the ops screen.
            String detail = looksLikeHtml(responseBody)
                    ? "(non-JSON response, see server logs)"
                    : truncate(responseBody, 200);
            throw new RuntimeException("eSolutions is currently unavailable (HTTP " + response.statusCode() + "). " + detail);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> responseMap = mapper.readValue(responseBody, Map.class);
        return new ESolutionsResponse(responseMap, responseBody);
    }

    public String getVendorNumber() { return vendorNumber; }

    private boolean looksLikeHtml(String body) {
        if (body == null) return false;
        String trimmed = body.stripLeading();
        return trimmed.regionMatches(true, 0, "<!DOCTYPE", 0, 9) || trimmed.startsWith("<html");
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }
}