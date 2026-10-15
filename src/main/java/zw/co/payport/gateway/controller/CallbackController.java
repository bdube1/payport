package zw.co.payport.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.service.adaptor.PaymentProviderAdaptor;
import zw.co.payport.gateway.entity.Transaction;
import zw.co.payport.gateway.repository.TransactionRepository;
import zw.co.payport.gateway.service.AdaptorRegistry;
import zw.co.payport.gateway.service.PaymentStatusVerifier;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * Callback Controllers
 *
 * Handles incoming callbacks from payment providers.
 * These are called directly by EcoCash/OneMoney servers after customer approves payment.
 *
 * SECURITY MODEL:
 * 1. IP whitelist enforced by Cloudflare/firewall (not in app code for performance)
 * 2. Each provider's signature/auth is validated before any processing
 * 3. All processing is async - we return 200 OK immediately to prevent provider timeouts
 * 4. Idempotency: duplicate callbacks are safely ignored
 *
 * URLs (register these with each provider):
 * EcoCash:  POST https://api.payport.africa/api/v1/callbacks/ecocash
 * OneMoney: POST https://api.payport.africa/api/v1/callbacks/onemoney
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/callbacks")
@RequiredArgsConstructor
public class CallbackController {

    private final AdaptorRegistry              adaptorRegistry;
    private final TransactionOrchestratorService orchestratorService;
    private final ObjectMapper                 objectMapper;
    private final PaymentStatusVerifier        verifier;
    private final TransactionRepository        transactionRepository;

    /**
     * EcoCash Callback
     * Called by EcoCash when customer approves or declines payment.
     */
    @PostMapping("/ecocash")
    public ResponseEntity<String> ecoCashCallback(
            @RequestBody String rawBody,
            HttpServletRequest request) {

        return handleCallback("ECOCASH", rawBody, request);
    }

    /**
     * OneMoney Callback
     * Called by OneMoney when customer approves or declines payment.
     */
    @PostMapping("/onemoney")
    public ResponseEntity<String> oneMoneyCallback(
            @RequestBody String rawBody,
            HttpServletRequest request) {

        return handleCallback("ONEMONEY", rawBody, request);
    }

    /**
     * Telecash Callback
     */
    @PostMapping("/telecash")
    public ResponseEntity<String> telecashCallback(
            @RequestBody String rawBody,
            HttpServletRequest request) {

        return handleCallback("TELECASH", rawBody, request);
    }

    // ─── Core callback handler ────────────────────────────────────────────────

    private ResponseEntity<String> handleCallback(String provider, String rawBody, HttpServletRequest request) {
        log.info("[CALLBACK] Received {} callback from IP: {}", provider, request.getRemoteAddr());
        log.debug("[CALLBACK] {} body: {}", provider, rawBody);

        try {
            // 1. Get headers
            Map<String, String> headers = extractHeaders(request);

            // 2. Validate callback (signature, structure)
            if (!adaptorRegistry.hasPaymentAdaptor(provider)) {
                log.warn("[CALLBACK] No adaptor for provider: {}", provider);
                return ResponseEntity.ok("ACK");
            }

            PaymentProviderAdaptor adaptor = adaptorRegistry.getPaymentAdaptor(provider);
            PaymentProviderAdaptor.CallbackValidationResult validation =
                    adaptor.validateCallback(headers, rawBody);

            if (!validation.isValid()) {
                log.error("[CALLBACK] {} callback validation FAILED: {}", provider, validation.getFailureReason());
                // Return 403 to tell provider this callback was rejected
                return ResponseEntity.status(403).body("INVALID_SIGNATURE");
            }

            // 3. Parse callback
            PaymentProviderAdaptor.CallbackEvent event = adaptor.parseCallback(rawBody);
            log.info("[CALLBACK] {} parsed - reference: {} status: {} providerRef: {}",
                    provider, event.getPayportReference(), event.getStatus(), event.getProviderReference());

            // 4. Process asynchronously (return 200 immediately to provider)
            // Processing happens in orchestratorService which updates transaction state
            if (event.getPayportReference() != null && !event.getPayportReference().isBlank()) {
                if (verifier.requiresVerification(provider)) {
                    applyVerified(provider, event);
                } else {
                    orchestratorService.handlePaymentCallback(event);
                }
            } else {
                log.warn("[CALLBACK] {} callback missing payportReference - cannot process", provider);
            }

            // 5. Return 200 immediately - OneMoney specifically requires "SUCCESS"
            String ackBody = "ONEMONEY".equals(provider) ? "SUCCESS" : "OK";
            return ResponseEntity.ok(ackBody);

        } catch (Exception e) {
            log.error("[CALLBACK] {} callback processing error: {}", provider, e.getMessage(), e);
            // Always return 200 to provider - never let them know our internal errors
            return ResponseEntity.ok("ACK");
        }
    }

    /**
     * R4 containment: the callback content is not trusted. Ask the provider what really
     * happened and apply only a definite, amount-matched answer. If the provider has no
     * definite answer yet, nothing changes now; PaymentReconciliationScheduler keeps
     * asking. Timeouts never turn into failures by themselves.
     */
    private void applyVerified(String provider, PaymentProviderAdaptor.CallbackEvent claimed) {
        Transaction tx = transactionRepository.findByReference(claimed.getPayportReference()).orElse(null);
        if (tx == null) {
            log.warn("[CALLBACK] {} callback for unknown reference {} -- ignored", provider, claimed.getPayportReference());
            return;
        }
        PaymentStatusVerifier.Verified verified = verifier.verify(provider, tx);
        boolean claimedDefinite = "SUCCESS".equals(claimed.getStatus()) || "FAILED".equals(claimed.getStatus());
        if (claimedDefinite && verified.isDefinite() && !claimed.getStatus().equals(verified.status())) {
            log.error("[CALLBACK] CONTRADICTION for {}: callback says {}, {} status query says {} -- applying the provider query result; review this payment",
                    tx.getReference(), claimed.getStatus(), provider, verified.status());
        }
        PaymentProviderAdaptor.CallbackEvent confirmed = verifier.toEvent(tx, verified, provider + " callback");
        if (confirmed == null) {
            log.info("[CALLBACK] {} callback for {} claimed {}, provider query says {} ({}) -- left for the reconciler",
                    provider, tx.getReference(), claimed.getStatus(), verified.status(), verified.detail());
            return;
        }
        orchestratorService.handlePaymentCallback(confirmed);
    }

    private Map<String, String> extractHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                headers.put(name.toLowerCase(), request.getHeader(name));
            }
        }
        return headers;
    }
}