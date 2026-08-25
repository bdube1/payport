package zw.co.payport.gateway.service.adaptor.esolutions.nyaradzo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.service.adaptor.FulfilmentProviderAdaptor;
import zw.co.payport.gateway.service.adaptor.esolutions.ESolutionsClient;
import zw.co.payport.gateway.service.adaptor.esolutions.ESolutionsResponse;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nyaradzo Life Assurance Payment Adaptor
 *
 * ── e-Solutions API: merchant=NYARADZO, product=NYARADZO ────────────────────
 *
 * SPECIAL RULES (from spec section 5.13):
 *   • Customer must pay EITHER:
 *       (a) balance only, OR
 *       (b) balance + (months * monthly premium)
 *   • Formula: transactionAmount = balance + (noOfMonths * monthlyPremium)
 *   • customerData field in payment request = "noOfMonths|monthlyPremium"
 *     e.g. "1|3407.73" = paying 1 month + balance
 *     e.g. "0|3407.73" = paying balance only
 *   • Transaction will FAIL if the amount does not satisfy the equation
 *   • IMPORTANT: Amount must be sent in CENTS (not dollars) to eSolutions
 *
 * Customer Info response - customerData format varies:
 *   eSolutions may send: "1|334.95|ZWG|LOCAL" or similar
 *   Holder name and policy status are in the narrative field
 *
 * Narrative format: "HOLDER_NAME (POLICY_NUMBER - STATUS). Monthly premium is CURRENCY AMOUNT"
 *   Examples: "EMILIA MARURIKE (SCPK301027 - Active). Monthly premium is ZWG 150.15"
 *             "TALENT KAPITO (SCPK537774 - Lapsed). Monthly premium is ZWG 334.95"
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NyaradzoAdaptor implements FulfilmentProviderAdaptor {

    private final ESolutionsClient client;
    private final ObjectMapper     mapper = new ObjectMapper();

    private static final String MERCHANT = "NYARADZO";
    private static final String PRODUCT  = "NYARADZO";

    @Override
    public String getProviderCode() { return "NYARADZO"; }

    public NyaradzoCustomerInfo getCustomerInfo(String policyNumber, String sourcePhone) throws Exception {
        String vendorRef = buildVendorRef("NCI");
        log.info("[NYARADZO] Customer info for policy {}", maskPolicy(policyNumber));

        ESolutionsResponse resp = client.customerInfo(
                MERCHANT, PRODUCT,
                policyNumber.trim(),
                "USD",
                0,
                vendorRef
        );

        if (!resp.isSuccess()) {
            log.error("[NYARADZO] Customer info failed: {}", resp.getFailureMessage());
            throw new RuntimeException(resp.getFailureMessage());
        }

        // Get customerData and narrative
        String customerData = resp.getCustomerData();
        String narrative = resp.getNarrative();
        String customerBalance = extractCustomerBalanceFromRawResponse(resp.getRawJson());

        log.debug("[NYARADZO] Customer data: {}", customerData);
        log.debug("[NYARADZO] Narrative: {}", narrative);
        log.debug("[NYARADZO] Customer balance: {}", customerBalance);

        // Parse customerData: format - "BALANCE|MONTHLY_PREMIUM|CURRENCY|ADDITIONAL"
        String[] parts = customerData != null ? customerData.split("\\|", -1) : new String[0];

        // Extract holder name and policy status from narrative
        String holderName = extractHolderNameFromNarrative(narrative);
        String policyStatus = extractPolicyStatusFromNarrative(narrative);

        // Parse balance
        long balanceCents = 0;
        if (customerBalance != null && !customerBalance.isBlank()) {
            try {
                double balance = Double.parseDouble(customerBalance);
                balanceCents = Math.round(balance * 100);
            } catch (NumberFormatException e) {
                log.warn("[NYARADZO] Failed to parse customerBalance: {}", customerBalance);
            }
        } else if (parts.length > 0 && !parts[0].isBlank()) {
            try {
                double balance = Double.parseDouble(parts[0]);
                if (balance < 10 && !parts[0].contains(".")) {
                    balanceCents = Math.round(balance);
                } else {
                    balanceCents = Math.round(balance * 100);
                }
            } catch (NumberFormatException e) {
                log.warn("[NYARADZO] Failed to parse balance: {}", parts[0]);
            }
        }

        // Parse monthly premium
        long monthlyPremiumCents = 0;
        String currency = "ZWG";
        if (parts.length > 1 && !parts[1].isBlank()) {
            try {
                double monthlyPremium = Double.parseDouble(parts[1]);
                monthlyPremiumCents = Math.round(monthlyPremium * 100);
            } catch (NumberFormatException e) {
                log.warn("[NYARADZO] Failed to parse monthly premium: {}", parts[1]);
            }
        }

        // Get currency from parts[2] if available
        if (parts.length > 2 && !parts[2].isBlank()) {
            currency = parts[2].trim();
        }

        // Additional info from parts[3]
        String additionalInfo = parts.length > 3 ? parts[3].trim() : "";

        // Fallback to extract monthly premium from narrative if not found
        if (monthlyPremiumCents == 0 && narrative != null) {
            monthlyPremiumCents = extractMonthlyPremiumFromNarrative(narrative);
            String narrativeCurrency = extractCurrencyFromNarrative(narrative);
            if (!narrativeCurrency.isBlank()) {
                currency = narrativeCurrency;
            }
        }

        log.info("[NYARADZO] Customer info retrieved - Holder: {}, Status: {}, Balance: {} cents, Monthly: {} cents, Currency: {}",
                holderName, policyStatus, balanceCents, monthlyPremiumCents, currency);

        return new NyaradzoCustomerInfo(
                holderName,
                policyNumber.trim(),
                balanceCents,
                monthlyPremiumCents,
                additionalInfo,
                currency,
                policyStatus
        );
    }

    private String extractCustomerBalanceFromRawResponse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(rawJson);
            if (root.has("customerBalance")) {
                JsonNode balanceNode = root.get("customerBalance");
                if (balanceNode != null && !balanceNode.isNull()) {
                    return balanceNode.asText();
                }
            }
        } catch (Exception e) {
            log.debug("[NYARADZO] Failed to extract customerBalance: {}", e.getMessage());
        }
        return null;
    }

    private String extractHolderNameFromNarrative(String narrative) {
        if (narrative == null || narrative.isBlank()) {
            return "";
        }
        try {
            Pattern pattern = Pattern.compile("^([^(]+)\\(");
            Matcher matcher = pattern.matcher(narrative);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        } catch (Exception e) {
            log.debug("[NYARADZO] Failed to extract holder name: {}", e.getMessage());
        }
        return "";
    }

    private String extractPolicyStatusFromNarrative(String narrative) {
        if (narrative == null || narrative.isBlank()) {
            return "";
        }
        try {
            Pattern pattern = Pattern.compile("\\([^)]+\\s+-\\s+([^)]+)\\)");
            Matcher matcher = pattern.matcher(narrative);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        } catch (Exception e) {
            log.debug("[NYARADZO] Failed to extract policy status: {}", e.getMessage());
        }
        return "";
    }

    private long extractMonthlyPremiumFromNarrative(String narrative) {
        if (narrative == null || narrative.isBlank()) {
            return 0;
        }
        try {
            Pattern pattern = Pattern.compile("premium is (?:ZWG|USD)\\s*([\\d.]+)");
            Matcher matcher = pattern.matcher(narrative);
            if (matcher.find()) {
                double premium = Double.parseDouble(matcher.group(1));
                return Math.round(premium * 100);
            }
        } catch (Exception e) {
            log.debug("[NYARADZO] Failed to extract monthly premium from narrative: {}", e.getMessage());
        }
        return 0;
    }

    private String extractCurrencyFromNarrative(String narrative) {
        if (narrative == null || narrative.isBlank()) {
            return "";
        }
        try {
            Pattern pattern = Pattern.compile("premium is (ZWG|USD)\\s*[\\d.]+");
            Matcher matcher = pattern.matcher(narrative);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception e) {
            log.debug("[NYARADZO] Failed to extract currency from narrative: {}", e.getMessage());
        }
        return "";
    }

    @Override
    public FulfilmentResult deliver(FulfilmentRequest request) throws Exception {
        String policyNumber = request.getRecipientIdentifier();
        String vendorRef    = buildVendorRef(request.getPayportReference());

        log.info("[NYARADZO] deliver called - amountCents={}, currency={}, productConfig={}",
                request.getAmountCents(), request.getCurrency(), request.getProductConfig());

        NyaradzoPaymentConfig cfg = parsePaymentConfig(request.getProductConfig());

        log.info("[NYARADZO] Parsed config - noOfMonths={}, monthlyPremiumCents={}, balanceCents={}, holderName={}, sourcePhone={}, currency={}",
                cfg.noOfMonths(), cfg.monthlyPremiumCents(), cfg.balanceCents(), cfg.holderName(), cfg.sourcePhone(), cfg.currency());

        // Validate amount per spec: transactionAmount = balance + (noOfMonths * monthlyPremium)
        long expectedCents = cfg.balanceCents() + (cfg.noOfMonths() * cfg.monthlyPremiumCents());
        if (expectedCents != request.getAmountCents()) {
            String msg = String.format(
                    "Payment amount mismatch. Expected %s %.2f (balance %s %.2f + %d month(s) × %s %.2f), got %s %.2f",
                    cfg.currency(), expectedCents / 100.0,
                    cfg.currency(), cfg.balanceCents() / 100.0,
                    cfg.noOfMonths(), cfg.currency(), cfg.monthlyPremiumCents() / 100.0,
                    request.getCurrency(), request.getAmountCents() / 100.0);
            log.warn("[NYARADZO] Amount validation failed: {}", msg);
            return FulfilmentResult.builder().success(false).message(msg).build();
        }

        // customerData for payment: "noOfMonths|monthlyPremium"
        // e.g. "1|3407.73" — amounts as doubles per spec
        String customerData = cfg.noOfMonths() + "|" + String.format("%.2f", cfg.monthlyPremiumCents() / 100.0);

        // IMPORTANT: Send amount in CENTS as per eSolutions specification
        // The spec example shows: "transactionAmount": 393146 (cents)
        long amountInCents = request.getAmountCents();

        // Remove the + sign from sourceMobile as per eSolutions spec (shows "263772731006" without +)
        String cleanSourcePhone = cfg.sourcePhone() != null ? cfg.sourcePhone().replace("+", "") : null;

        log.info("[NYARADZO] Payment for policy {} ref={} amount={} cents ({} {}) months={}, customerData={}, sourcePhone={}",
                maskPolicy(policyNumber), vendorRef, amountInCents, amountInCents / 100.0, request.getCurrency(), cfg.noOfMonths(), customerData, cleanSourcePhone);

        ESolutionsResponse resp;
        try {
            resp = client.purchase(
                    MERCHANT, PRODUCT,
                    policyNumber.trim(),
                    cleanSourcePhone,  // Without + sign as per spec
                    amountInCents,      // Send amount in CENTS (e.g., 15015 for $150.15)
                    request.getCurrency(),
                    vendorRef,
                    customerData
            );
        } catch (java.net.http.HttpTimeoutException | java.net.ConnectException e) {
            log.warn("[NYARADZO] Timeout on purchase for ref={}, resolving via resend", vendorRef);
            return resolveViaResend(vendorRef, policyNumber, cleanSourcePhone, amountInCents, request.getCurrency(), customerData, cfg);
        }

        FulfilmentResult result = buildResult(resp, vendorRef, policyNumber, cfg);

        // Was returning "TIMEOUT_REQUIRES_RESEND:" straight to the
        // orchestrator as a plain failure -- this signal was produced
        // here but never consumed anywhere in the codebase. eSolutions'
        // own resend() documentation is explicit for this whole family
        // of merchants: never conclude failure without it, since a
        // payment may already have gone through even though this
        // specific HTTP round-trip never confirmed it.
        if (result.getMessage() != null && result.getMessage().startsWith("TIMEOUT_REQUIRES_RESEND:")) {
            return resolveViaResend(vendorRef, policyNumber, cleanSourcePhone, amountInCents, request.getCurrency(), customerData, cfg);
        }

        return result;
    }

    private FulfilmentResult buildResult(ESolutionsResponse resp, String vendorRef, String policyNumber, NyaradzoPaymentConfig cfg) throws Exception {
        if (resp.isInProgress()) {
            log.warn("[NYARADZO] Purchase in progress for ref={}", vendorRef);
            return FulfilmentResult.builder()
                    .success(false)
                    .fulfilmentReference(vendorRef)
                    .message("TIMEOUT_REQUIRES_RESEND:" + vendorRef)
                    .rawResponse(resp.getRawJson())
                    .build();
        }

        if (!resp.isSuccess()) {
            log.warn("[NYARADZO] Payment failed for ref={}: {}", vendorRef, resp.getFailureMessage());
            return FulfilmentResult.builder()
                    .success(false)
                    .message(resp.getFailureMessage())
                    .rawResponse(resp.getRawJson())
                    .build();
        }

        log.info("[NYARADZO] Payment SUCCESS for ref={}, transactionRef={}", vendorRef, resp.getTransactionRef());

        Map<String, Object> data = new HashMap<>();
        data.put("type",           "NYARADZO_PAYMENT");
        data.put("transactionRef", resp.getTransactionRef());
        data.put("policyNumber",   policyNumber.trim());
        data.put("holderName",     cfg.holderName());
        data.put("noOfMonths",     cfg.noOfMonths());
        data.put("narrative",      resp.getNarrative());
        data.put("currencyCode",   resp.getCurrencyCode());
        data.put("responseCode",   resp.getResponseCode());

        return FulfilmentResult.builder()
                .success(true)
                .fulfilmentReference(resp.getTransactionRef() != null
                        ? resp.getTransactionRef() : vendorRef)
                .fulfilmentData(mapper.writeValueAsString(data))
                .message("Nyaradzo payment successful")
                .rawResponse(resp.getRawJson())
                .build();
    }

    /**
     * eSolutions' documented warning applies here too: never conclude
     * failure without a resend -- a payment may already have gone
     * through even though this specific round-trip never confirmed it.
     * Reuses buildResult() against the resend's response, since it's the
     * same ESolutionsResponse shape.
     */
    private FulfilmentResult resolveViaResend(String originalVendorRef, String policyNumber, String sourcePhone,
                                                long amountInCents, String currency, String customerData,
                                                NyaradzoPaymentConfig cfg) throws Exception {
        String resendVendorRef = buildVendorRef("RS");
        log.info("[NYARADZO] Resolving ambiguous outcome for {} via resend (new ref {})", originalVendorRef, resendVendorRef);

        ESolutionsResponse resendResp;
        try {
            resendResp = client.resend(
                    MERCHANT, PRODUCT,
                    policyNumber.trim(),
                    amountInCents,
                    currency,
                    originalVendorRef,
                    resendVendorRef
            );
        } catch (Exception e) {
            log.error("[NYARADZO] Resend itself failed for {}: {}", originalVendorRef, e.getMessage(), e);
            return FulfilmentResult.builder()
                    .success(false)
                    .fulfilmentReference(originalVendorRef)
                    .message("Could not confirm whether this payment already went through -- needs manual verification before retrying.")
                    .rawResponse("Resend attempt failed: " + e.getMessage())
                    .build();
        }

        FulfilmentResult resendResult = buildResult(resendResp, originalVendorRef, policyNumber, cfg);

        if (resendResult.getMessage() != null && resendResult.getMessage().startsWith("TIMEOUT_REQUIRES_RESEND:")) {
            return FulfilmentResult.builder()
                    .success(false)
                    .fulfilmentReference(originalVendorRef)
                    .message("Still awaiting confirmation from eSolutions -- needs a manual check before retrying, to avoid a duplicate payment.")
                    .rawResponse(resendResult.getRawResponse())
                    .build();
        }

        return resendResult;
    }

    @Override
    public FulfilmentStatusResult queryDelivery(String originalVendorRef) throws Exception {
        log.info("[NYARADZO] Resend lookup for ref={}", originalVendorRef);
        String newVendorRef = buildVendorRef("NRS");
        ESolutionsResponse resp = client.resend(
                MERCHANT, PRODUCT, null, 0, "USD", originalVendorRef, newVendorRef);

        if (resp.isInProgress()) {
            return FulfilmentStatusResult.builder()
                    .status("PENDING")
                    .fulfilmentReference(originalVendorRef)
                    .build();
        }
        if (resp.isSuccess()) {
            return FulfilmentStatusResult.builder()
                    .status("DELIVERED")
                    .fulfilmentReference(resp.getTransactionRef())
                    .build();
        }
        return FulfilmentStatusResult.builder()
                .status("FAILED")
                .fulfilmentReference(originalVendorRef)
                .message(resp.getFailureMessage())
                .build();
    }

    @SuppressWarnings("unchecked")
    private NyaradzoPaymentConfig parsePaymentConfig(String json) {
        log.info("[NYARADZO] parsePaymentConfig called with json: {}", json);

        if (json == null || json.isBlank()) {
            log.warn("[NYARADZO] parsePaymentConfig: json is null or blank, returning default config");
            return new NyaradzoPaymentConfig(0, 0, 0, "", null, "ZWG");
        }
        try {
            Map<String, Object> cfg = mapper.readValue(json, Map.class);
            log.info("[NYARADZO] Parsed config - noOfMonths: {}, monthlyPremiumCents: {}, balanceCents: {}, holderName: {}, sourcePhone: {}, currency: {}",
                    cfg.get("noOfMonths"), cfg.get("monthlyPremiumCents"), cfg.get("balanceCents"),
                    cfg.get("holderName"), cfg.get("sourcePhone"), cfg.get("currency"));

            return new NyaradzoPaymentConfig(
                    toLong(cfg.get("noOfMonths")),
                    toLong(cfg.get("monthlyPremiumCents")),
                    toLong(cfg.get("balanceCents")),
                    cfg.getOrDefault("holderName", "").toString(),
                    (String) cfg.get("sourcePhone"),
                    cfg.getOrDefault("currency", "ZWG").toString()
            );
        } catch (Exception e) {
            log.error("[NYARADZO] Failed to parse productConfig: {}", e.getMessage(), e);
            return new NyaradzoPaymentConfig(0, 0, 0, "", null, "ZWG");
        }
    }

    private String buildVendorRef(String prefix) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String p  = prefix.replaceAll("[^A-Za-z0-9]", "").substring(0, Math.min(prefix.length(), 4));
        return (p + ts).substring(0, Math.min(p.length() + ts.length(), 20));
    }

    private String maskPolicy(String p) {
        if (p == null || p.length() < 4) return "****";
        return p.substring(0, 3) + "****";
    }

    private double safeDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(o.toString());
        } catch (Exception e) {
            return 0L;
        }
    }

    public record NyaradzoCustomerInfo(
            String holderName,
            String policyNumber,
            long balanceCents,
            long monthlyPremiumCents,
            String additionalInfo,
            String currency,
            String policyStatus
    ) {
        public String getBalanceFormatted() {
            return currency + " " + String.format("%.2f", balanceCents / 100.0);
        }

        public String getMonthlyPremiumFormatted() {
            return currency + " " + String.format("%.2f", monthlyPremiumCents / 100.0);
        }

        public boolean isPolicyActive() {
            return "Active".equalsIgnoreCase(policyStatus);
        }
    }

    private record NyaradzoPaymentConfig(
            long noOfMonths,
            long monthlyPremiumCents,
            long balanceCents,
            String holderName,
            String sourcePhone,
            String currency
    ) {}
}