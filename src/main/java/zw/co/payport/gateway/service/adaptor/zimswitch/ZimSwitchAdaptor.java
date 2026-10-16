package zw.co.payport.gateway.service.adaptor.zimswitch;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import zw.co.payport.gateway.service.adaptor.PaymentProviderAdaptor;

import java.util.Map;

/**
 * ZimSwitch Payment Adaptor (COPYandPAY / OPPWa Widget)
 *
 * Flow:
 *   1. submitPayment()  → POST /v1/checkouts → returns checkoutId as providerReference
 *   2. Frontend         → loads widget JS using checkoutId, customer enters card & submits
 *   3. ZimSwitch        → POSTs result to our notifyUrl (callback) OR redirects shopper
 *   4. validateCallback / parseCallback → processes the webhook notification
 *   5. queryStatus()    → GET /v1/checkouts/{checkoutId}/payment to confirm result
 *
 * Credentials you need from ZimSwitch/OPPWa when you onboard:
 *   payport.zimswitch.entity-id      — your entityId
 *   payport.zimswitch.bearer-token   — your Bearer token
 *   payport.zimswitch.base-url       — test: https://eu-test.oppwa.com  |  prod: given by ZimSwitch
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ZimSwitchAdaptor implements PaymentProviderAdaptor {

    @Value("${payport.zimswitch.base-url}")
    private String baseUrl;

    @Value("${payport.zimswitch.entity-id}")
    private String entityId;

    @Value("${payport.zimswitch.bearer-token}")
    private String bearerToken;

    @Value("${payport.zimswitch.notify-url}")
    private String notifyUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public String getProviderCode() {
        return "ZIMSWITCH";
    }

    /**
     * Step 1: Prepare the OPPWa checkout.
     *
     * POSTs to /v1/checkouts and returns the checkoutId as providerReference.
     * The orchestrator stores the checkoutId on the transaction so:
     *   a) The frontend can load the widget JS to show the card form
     *   b) We can query the result later via queryStatus()
     *
     * NOTE: The widget JS URL is returned in the `message` field so the
     * orchestrator can pass it to the frontend in the transaction response.
     * Frontend loads: <script src="{message}"></script>
     * Then renders:   <form action="{shopperResultUrl}" class="paymentWidgets" data-brands="ZIMSWITCH"></form>
     */
    @Override
    public PaymentSubmitResult submitPayment(PaymentRequest request) throws Exception {
        log.info("[ZIMSWITCH] Preparing checkout | ref={} amountCents={} currency={}",
                request.getPayportReference(), request.getAmountCents(), request.getCurrency());

        // Provisional Phase 5: the integration itself is fully built and
        // wired end-to-end (see the design doc), but real ZimSwitch
        // credentials haven't been issued yet -- entityId/bearerToken are
        // still empty placeholders in application.properties. Rather than
        // let an empty-credentials request hit OPPWA and fail with a
        // confusing provider error, refuse clearly and immediately with
        // an honest explanation. Delete this check the moment real
        // credentials are configured -- everything downstream is ready.
        if (entityId == null || entityId.isBlank() || bearerToken == null || bearerToken.isBlank()) {
            log.warn("[ZIMSWITCH] Card payment attempted but ZimSwitch credentials are not configured yet");
            throw new IllegalStateException(
                    "Card payments aren't available yet -- ZimSwitch credentials haven't been configured. " +
                    "Contact Payport support, or use EcoCash/OneMoney/Omari instead.");
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("Authorization", "Bearer " + bearerToken);

            String amount = String.format("%.2f", request.getAmountCents() / 100.0);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("entityId",              entityId);
            body.add("amount",                amount);
            body.add("currency",              request.getCurrency() != null ? request.getCurrency() : "USD");
            body.add("paymentType",           "DB");        // DB = immediate debit charge
            body.add("merchantTransactionId", request.getPayportReference());
            body.add("notificationUrl",       notifyUrl);

            // Customer phone helps with 3DS routing
            if (request.getCustomerPhone() != null && !request.getCustomerPhone().isEmpty()) {
                body.add("customer.phone", request.getCustomerPhone());
            }

            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl + "/v1/checkouts",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class
            );

            Map<String, Object> result = objectMapper.readValue(response.getBody(), Map.class);
            String checkoutId = (String) result.get("id");
            String resultCode = extractNestedCode(result);

            if (checkoutId == null || checkoutId.isEmpty()) {
                log.error("[ZIMSWITCH] No checkoutId returned for ref={} | code={} | body={}",
                        request.getPayportReference(), resultCode, response.getBody());
                return PaymentSubmitResult.builder()
                        .submitted(false)
                        .message("No checkoutId returned from ZimSwitch — code: " + resultCode)
                        .rawResponse(response.getBody())
                        .build();
            }

            // Widget JS URL — frontend loads this to render the ZimSwitch card form
            String widgetJsUrl = baseUrl + "/v1/paymentWidgets.js?checkoutId=" + checkoutId;

            log.info("[ZIMSWITCH] Checkout prepared | ref={} checkoutId={}", request.getPayportReference(), checkoutId);

            return PaymentSubmitResult.builder()
                    .submitted(true)
                    .providerReference(checkoutId)  // stored as paymentCorrelator on transaction
                    .message(widgetJsUrl)            // frontend loads this JS URL to show widget
                    .rawResponse(response.getBody())
                    .build();

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.error("[ZIMSWITCH] HTTP {} for ref={}: {}",
                    e.getStatusCode(), request.getPayportReference(), e.getResponseBodyAsString());
            return PaymentSubmitResult.builder()
                    .submitted(false)
                    .message("ZimSwitch HTTP " + e.getStatusCode() + ": " + e.getResponseBodyAsString())
                    .rawResponse(e.getResponseBodyAsString())
                    .build();
        } catch (Exception e) {
            log.error("[ZIMSWITCH] submitPayment failed for ref={}: {}", request.getPayportReference(), e.getMessage());
            return PaymentSubmitResult.builder()
                    .submitted(false)
                    .outcomeUnknown(zw.co.payport.gateway.payments.SubmissionErrors.isOutcomeUnknown(e))
                    .message("ZimSwitch error: " + e.getMessage())
                    .rawResponse(e.getMessage())
                    .build();
        }
    }

    /**
     * Validate the incoming webhook notification from OPPWa.
     *
     * OPPWa POSTs to your notifyUrl after payment is processed (JSON body).
     * Basic validation: check the payload has the expected OPPWa structure.
     */
    @Override
    public CallbackValidationResult validateCallback(Map<String, String> headers, String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            return CallbackValidationResult.builder()
                    .valid(false)
                    .failureReason("Empty callback body")
                    .build();
        }
        // OPPWa payloads always contain "id" and "result"
        if (!rawBody.contains("\"id\"") || !rawBody.contains("\"result\"")) {
            return CallbackValidationResult.builder()
                    .valid(false)
                    .failureReason("Missing required OPPWa fields (id, result)")
                    .build();
        }
        return CallbackValidationResult.builder().valid(true).build();
    }

    /**
     * Parse the OPPWa webhook notification body into a normalised CallbackEvent.
     *
     * OPPWa callback JSON fields:
     *   id                    — OPPWa payment ID
     *   merchantTransactionId — our payportReference
     *   amount                — decimal e.g. "1.00"
     *   currency              — "USD"
     *   result.code           — "000.000.000" = success
     *   timestamp             — ISO-8601
     */
    @Override
    public CallbackEvent parseCallback(String rawBody) throws Exception {
        Map<String, Object> payload = objectMapper.readValue(rawBody, Map.class);

        String oppwaId      = (String) payload.get("id");
        String ourReference = (String) payload.get("merchantTransactionId");
        String resultCode   = extractNestedCode(payload);
        String currency     = (String) payload.get("currency");
        String timestamp    = (String) payload.get("timestamp");

        long amountCents = 0;
        Object amountObj = payload.get("amount");
        if (amountObj != null) {
            try {
                amountCents = Math.round(Double.parseDouble(amountObj.toString()) * 100);
            } catch (NumberFormatException ignored) {}
        }

        boolean success = isSuccessCode(resultCode);

        log.info("[ZIMSWITCH] Callback | ref={} oppwaId={} resultCode={} success={}",
                ourReference, oppwaId, resultCode, success);

        return CallbackEvent.builder()
                .payportReference(ourReference)
                .providerReference(oppwaId)
                .status(success ? "SUCCESS" : "FAILED")
                .amountCents(amountCents)
                .currency(currency)
                .timestamp(timestamp)
                .rawPayload(rawBody)
                .build();
    }

    /**
     * Step 5: Query final payment status using the checkoutId.
     *
     * GET /v1/checkouts/{checkoutId}/payment?entityId=...
     * Called by the timeout watchdog and reconciliation scheduler.
     */
    @Override
    public PaymentStatusResult queryStatus(String providerReference) throws Exception {
        log.info("[ZIMSWITCH] Querying payment status | checkoutId={}", providerReference);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + bearerToken);

            String url = baseUrl + "/v1/checkouts/" + providerReference
                    + "/payment?entityId=" + entityId;

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            Map<String, Object> result = objectMapper.readValue(response.getBody(), Map.class);
            String resultCode = extractNestedCode(result);
            String oppwaId    = (String) result.get("id");
            boolean success   = isSuccessCode(resultCode);

            long amountCents = 0;
            Object amountObj = result.get("amount");
            if (amountObj != null) {
                try {
                    amountCents = Math.round(Double.parseDouble(amountObj.toString()) * 100);
                } catch (NumberFormatException ignored) {}
            }

            log.info("[ZIMSWITCH] Status | checkoutId={} oppwaId={} code={} success={}",
                    providerReference, oppwaId, resultCode, success);

            String status = classifyResultCode(resultCode);

            return PaymentStatusResult.builder()
                    .status(status)
                    .amountCents(amountCents)
                    .providerReference(oppwaId != null ? oppwaId : providerReference)
                    // The customer sees OPPWA's own description (owner decision); the code is in the logs.
                    .message(extractResultDescription(result))
                    .build();

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.error("[ZIMSWITCH] HTTP {} querying status for checkoutId={}: {}",
                    e.getStatusCode(), providerReference, e.getResponseBodyAsString());
            return PaymentStatusResult.builder()
                    .status("UNKNOWN")
                    .providerReference(providerReference)
                    .message("HTTP " + e.getStatusCode())
                    .build();
        } catch (Exception e) {
            log.error("[ZIMSWITCH] queryStatus failed for checkoutId={}: {}", providerReference, e.getMessage());
            return PaymentStatusResult.builder()
                    .status("UNKNOWN")
                    .providerReference(providerReference)
                    .message(e.getMessage())
                    .build();
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String extractNestedCode(Map<String, Object> map) {
        if (map == null) return "UNKNOWN";
        Object resultObj = map.get("result");
        if (resultObj instanceof Map) {
            Object code = ((Map<String, Object>) resultObj).get("code");
            return code != null ? code.toString() : "UNKNOWN";
        }
        return "UNKNOWN";
    }

    @SuppressWarnings("unchecked")
    private String extractResultDescription(Map<String, Object> map) {
        if (map == null) return "";
        Object resultObj = map.get("result");
        if (resultObj instanceof Map) {
            Object desc = ((Map<String, Object>) resultObj).get("description");
            return desc != null ? desc.toString() : "";
        }
        return "";
    }

    /**
     * Result code groups from https://zimswitch.docs.oppwa.com/reference/resultCodes:
     * SUCCESS (1.1 and 1.2 "successful, review"), PENDING (1.3, 1.4, and 200.300.404 for a checkout
     * not paid yet), UNKNOWN for "uncertain" timeouts (900.100.300/400/500, 100.396.104, 999.*), and
     * FAILED only for the documented rejection groups. Anything else is UNKNOWN, never FAILED.
     */
    static String classifyResultCode(String code) {
        if (code == null || "UNKNOWN".equals(code)) return "UNKNOWN";
        if (code.matches("^(000\\.000\\.|000\\.100\\.1|000\\.[36]|000\\.400\\.[1][12]0|000\\.500\\.).*")) return "SUCCESS";
        if (code.matches("^(000\\.400\\.0[^3]|000\\.400\\.100).*")) return "SUCCESS";
        if (code.matches("^(000\\.200|800\\.400\\.5|100\\.400\\.500).*") || code.equals("200.300.404")) return "PENDING";
        if (code.matches("^(900\\.100\\.300|900\\.100\\.400|900\\.100\\.500|100\\.396\\.104|999\\.).*")) return "UNKNOWN";
        String rejected = "^(000\\.400\\.[1][0-9][1-9]|000\\.400\\.2|800\\.[17]00|800\\.800\\.[123]|900\\.[1234]00|000\\.400\\.030"
                + "|800\\.[56]|600\\.1|800\\.800\\.[84]|100\\.39[765]|300\\.100\\.100|100\\.400\\.[0-3]|100\\.380\\.100|100\\.380\\.11"
                + "|100\\.380\\.4|100\\.380\\.5|800\\.400\\.1|800\\.400\\.2|100\\.390|800\\.[32]|800\\.1[123456]0|600\\.[23]|500\\.[12]"
                + "|800\\.121|100\\.[13]50|100\\.250|100\\.360|700\\.[1345][05]0|700\\.600|700\\.601|200\\.[123]|100\\.[53][07]|800\\.900"
                + "|100\\.[69]00\\.500|100\\.310\\.40[12]).*";
        return code.matches(rejected) ? "FAILED" : "UNKNOWN";
    }

    /**
     * OPPWa success result codes.
     * Reference: https://zimswitch.docs.oppwa.com/reference/resultCodes
     */
    private boolean isSuccessCode(String code) {
        if (code == null) return false;
        return code.matches("^000\\.000\\.0.*")
                || code.matches("^000\\.100\\.1.*")
                || code.matches("^000\\.300\\..*")
                || code.matches("^000\\.600\\..*");
    }

    /**
     * OPPWa pending codes — payment is still in progress (e.g. waiting for 3DS).
     */
    private boolean isPendingCode(String code) {
        if (code == null) return false;
        return code.matches("^000\\.200\\..*")
                || code.matches("^800\\.400\\..*");
    }
}
