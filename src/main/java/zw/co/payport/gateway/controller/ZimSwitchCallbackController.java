package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.payments.CallbackInbox;
import zw.co.payport.gateway.repository.CheckoutSessionRepository;
import zw.co.payport.gateway.repository.TransactionRepository;
import zw.co.payport.gateway.service.adaptor.PaymentProviderAdaptor;
import zw.co.payport.gateway.service.adaptor.zimswitch.ZimSwitchAdaptor;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * ZimSwitch Callback Controller
 *
 * Receives two types of incoming calls from OPPWa/ZimSwitch:
 *
 * 1. POST /api/v1/callbacks/zimswitch
 *    Webhook notification — OPPWa POSTs payment result JSON to this URL (notifyUrl).
 *    This is the primary confirmation path.
 *
 * 2. GET /api/v1/callbacks/zimswitch/result
 *    Shopper redirect — OPPWa redirects the customer's browser here after card entry.
 *    Query param: resourcePath=/v1/checkouts/{checkoutId}/payment
 *    We query OPPWa to confirm the result and update the transaction.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/callbacks/zimswitch")
@RequiredArgsConstructor
public class ZimSwitchCallbackController {

    private final ZimSwitchAdaptor zimSwitchAdaptor;
    private final CallbackInbox inbox;
    private final TransactionOrchestratorService orchestrator;
    private final TransactionRepository transactionRepository;
    private final CheckoutSessionRepository checkoutSessionRepository;

    @Value("${payport.consumer.base-url}")
    private String consumerBaseUrl;

    /**
     * Webhook notification from OPPWa (server-to-server POST).
     * OPPWa sends this to the notifyUrl after the payment is finalised.
     * Body is JSON with the full payment result including merchantTransactionId (our reference).
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> handleWebhook(
            @RequestBody String rawBody,
            @RequestHeader Map<String, String> headers,
            HttpServletRequest req) {

        log.info("[ZIMSWITCH] Webhook received | length={}", rawBody != null ? rawBody.length() : 0);
        log.debug("[ZIMSWITCH] Webhook body: {}", rawBody);

        // Stored in the callback inbox, validated (signature/structure), then applied idempotently.
        Map<String, String> lower = new HashMap<>();
        headers.forEach((k, v) -> lower.put(k.toLowerCase(), v));
        String body = rawBody;
        if (lower.containsKey("x-initialization-vector") && lower.containsKey("x-authentication-tag")) {
            // OPPWA webhooks are AES-256-GCM encrypted (zimswitch.docs.oppwa.com/tutorials/webhooks).
            try {
                body = decryptWebhook(rawBody, lower.get("x-initialization-vector"), lower.get("x-authentication-tag"));
            } catch (Exception e) {
                log.warn("[ZIMSWITCH] Webhook could not be decrypted ({}) -- rejected", e.getMessage());
                return ResponseEntity.badRequest().body(Map.of("status", "rejected", "reason", "decryption failed"));
            }
        }
        CallbackInbox.Result r = inbox.receive("ZIMSWITCH", body, lower, req.getRemoteAddr());
        if (r.status() == CallbackInbox.Status.INVALID && r.detail() != null && r.detail().startsWith("validation failed")) {
            log.warn("[ZIMSWITCH] Webhook validation failed: {}", r.detail());
            return ResponseEntity.badRequest().body(Map.of("status", "rejected", "reason", r.detail()));
        }
        // Always 200 otherwise -- OPPWa retries on non-2xx; the inbox absorbs duplicates anyway.
        return ResponseEntity.ok(Map.of("status", r.status() == CallbackInbox.Status.ERROR ? "error" : "received"));
    }

    @Value("${payport.zimswitch.webhook-secret:}")
    private String webhookSecretHex;

    /**
     * Decrypts an OPPWA webhook: body is hex (or {"encryptedBody":"hex"}), IV and GCM tag come in
     * headers, key is the 64-hex-character secret configured for the webhook. Returns the
     * notification's payload object as JSON (the payment, with merchantTransactionId).
     */
    String decryptWebhook(String rawBody, String ivHex, String tagHex) throws Exception {
        if (webhookSecretHex == null || webhookSecretHex.isBlank()) throw new IllegalStateException("ZIMSWITCH_WEBHOOK_SECRET not configured");
        String hex = rawBody.trim();
        if (hex.startsWith("{")) {
            hex = (String) new com.fasterxml.jackson.databind.ObjectMapper().readValue(hex, Map.class).get("encryptedBody");
        }
        java.util.HexFormat h = java.util.HexFormat.of();
        byte[] cipher = h.parseHex(hex.trim());
        byte[] tag = h.parseHex(tagHex.trim());
        byte[] input = new byte[cipher.length + tag.length];
        System.arraycopy(cipher, 0, input, 0, cipher.length);
        System.arraycopy(tag, 0, input, cipher.length, tag.length);
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        c.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(h.parseHex(webhookSecretHex.trim()), "AES"),
                new javax.crypto.spec.GCMParameterSpec(tag.length * 8, h.parseHex(ivHex.trim())));
        String plain = new String(c.doFinal(input), java.nio.charset.StandardCharsets.UTF_8);
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<?, ?> m = om.readValue(plain, Map.class);
        Object payload = m.get("payload");
        return payload != null ? om.writeValueAsString(payload) : plain;
    }

    /**
     * Shopper redirect — customer's browser lands here after completing payment on widget.
     *
     * OPPWa appends: ?resourcePath=/v1/checkouts/{checkoutId}/payment
     * We:
     *   1. Extract the checkoutId from resourcePath
     *   2. Query OPPWa directly for the final payment status
     *   3. Look up our transaction by paymentCorrelator (= checkoutId stored at submitPayment)
     *   4. Build a CallbackEvent and pass to the orchestrator
     *
     * Note: The webhook (POST above) usually arrives before the browser redirect.
     * The orchestrator is idempotent so a duplicate callback is safely ignored.
     */
    @GetMapping("/result")
    public ResponseEntity<?> handleShopperRedirect(
            @RequestParam(value = "resourcePath", required = false) String resourcePath,
            @RequestParam(value = "id", required = false) String directCheckoutId,
            HttpServletRequest req) {

        log.info("[ZIMSWITCH] Shopper redirect | resourcePath={} id={}", resourcePath, directCheckoutId);

        Map<String, Object> response = new HashMap<>();

        try {
            // Step 1: Resolve checkoutId
            // resourcePath format: /v1/checkouts/{checkoutId}/payment
            String checkoutId = directCheckoutId;
            if (checkoutId == null && resourcePath != null) {
                String[] parts = resourcePath.split("/");
                for (int i = 0; i < parts.length; i++) {
                    if ("checkouts".equals(parts[i]) && i + 1 < parts.length) {
                        checkoutId = parts[i + 1];
                        break;
                    }
                }
            }

            if (checkoutId == null || checkoutId.isEmpty()) {
                log.warn("[ZIMSWITCH] No checkoutId in shopper redirect: resourcePath={}", resourcePath);
                response.put("status", "error");
                response.put("message", "No checkoutId found in redirect parameters");
                return ResponseEntity.badRequest().body(response);
            }

            final String resolvedCheckoutId = checkoutId;

            // Step 2: Look up our transaction by the checkoutId (stored as paymentCorrelator)
            var matched = transactionRepository.findByPaymentCorrelator(resolvedCheckoutId);
            String payportReference = matched.map(tx -> tx.getReference()).orElse(null);
            // The event must name the attempt the checkout belongs to (reference-N after a retried payment).
            String attemptReference = matched.map(zw.co.payport.gateway.service.PaymentStatusVerifier::attemptReference).orElse(null);

            if (payportReference == null) {
                log.warn("[ZIMSWITCH] No transaction found for checkoutId={}", resolvedCheckoutId);
                response.put("status", "error");
                response.put("message", "Transaction not found for checkoutId: " + resolvedCheckoutId);
                return ResponseEntity.ok(response);
            }

            // Step 3: Query OPPWa for the final payment status
            PaymentProviderAdaptor.PaymentStatusResult statusResult =
                    zimSwitchAdaptor.queryStatus(resolvedCheckoutId);

            log.info("[ZIMSWITCH] Shopper redirect status | ref={} checkoutId={} status={} message={}",
                    payportReference, resolvedCheckoutId, statusResult.getStatus(), statusResult.getMessage());

            // Step 4: Build a CallbackEvent and pass to orchestrator (idempotent)
            PaymentProviderAdaptor.CallbackEvent event = PaymentProviderAdaptor.CallbackEvent.builder()
                    .payportReference(attemptReference)
                    .providerReference(statusResult.getProviderReference())
                    .status(statusResult.getStatus())   // SUCCESS | FAILED | PENDING | UNKNOWN
                    .amountCents(statusResult.getAmountCents())
                    .rawPayload(statusResult.getMessage())
                    .build();

            // Only process terminal states — PENDING means payment still in progress
            if ("SUCCESS".equals(event.getStatus()) || "FAILED".equals(event.getStatus())) {
                orchestrator.handlePaymentCallback(event);
            }

            response.put("status", statusResult.getStatus());
            response.put("reference", payportReference);
            response.put("checkoutId", resolvedCheckoutId);
            response.put("providerReference", statusResult.getProviderReference());
            response.put("message", statusResult.getMessage());

            // Merchants Module addition: if this transaction belongs to a
            // checkout session (i.e. this card payment came from a
            // merchant's hosted checkout, not the regular Payport Pay
            // flow), redirect the browser back to that checkout page
            // instead of leaving the customer looking at raw JSON. Every
            // other transaction keeps the exact original behaviour below,
            // completely unchanged -- this only adds a new branch, it
            // doesn't alter the existing one.
            var linkedSession = checkoutSessionRepository.findByTransactionReference(payportReference);
            if (linkedSession.isPresent()) {
                String redirectUrl = consumerBaseUrl + "/checkout/" + linkedSession.get().getId();
                log.info("[ZIMSWITCH] Redirecting merchant checkout customer to {}", redirectUrl);
                return ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create(redirectUrl))
                        .build();
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[ZIMSWITCH] Shopper redirect error: {}", e.getMessage(), e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.ok(response);
        }
    }
}