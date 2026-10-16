package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.payments.CallbackInbox;
import zw.co.payport.gateway.service.PaymentStatusVerifier;

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
 * 3. Every callback is stored in the inbox (payment_callbacks) before processing
 * 4. Idempotency: duplicates and racing callbacks have exactly one effect
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

    private final CallbackInbox                inbox;
    private final PaymentStatusVerifier        verifier;

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

    /** MTN MoMo (RequestToPay callback; MTN may use PUT). Trigger only: confirmed by status query. */
    @RequestMapping(value = "/mtn-momo", method = {org.springframework.web.bind.annotation.RequestMethod.POST, org.springframework.web.bind.annotation.RequestMethod.PUT})
    public ResponseEntity<String> mtnMomoCallback(@RequestBody String rawBody, HttpServletRequest request) {
        return handleCallback("MTN_MOMO", rawBody, request);
    }

    /** Airtel Money collection callback. Trigger only: confirmed by status query. */
    @PostMapping("/airtel-money")
    public ResponseEntity<String> airtelMoneyCallback(@RequestBody String rawBody, HttpServletRequest request) {
        return handleCallback("AIRTEL_MONEY", rawBody, request);
    }

    // ─── Core callback handler ────────────────────────────────────────────────

    /**
     * Every callback goes through the inbox (stored first, then processed; see CallbackInbox).
     * EcoCash and OneMoney callbacks are never trusted on their content: the provider is
     * asked through our authenticated status query, and only its definite answer applies.
     * The provider always gets a 2xx unless signature validation failed.
     */
    private ResponseEntity<String> handleCallback(String provider, String rawBody, HttpServletRequest request) {
        log.info("[CALLBACK] Received {} callback from IP: {}", provider, request.getRemoteAddr());
        CallbackInbox.Result r = inbox.receive(provider, rawBody, extractHeaders(request), request.getRemoteAddr());
        if (r.status() == CallbackInbox.Status.INVALID && !verifier.requiresVerification(provider)
                && r.detail() != null && r.detail().startsWith("validation failed")) {
            return ResponseEntity.status(403).body("INVALID_SIGNATURE");
        }
        // OneMoney specifically requires "SUCCESS"
        return ResponseEntity.ok("ONEMONEY".equals(provider) ? "SUCCESS" : "OK");
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