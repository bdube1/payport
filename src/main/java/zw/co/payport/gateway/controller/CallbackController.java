package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.psp.payment.CallbackInboxService;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * Callback Controllers
 *
 * Handles incoming callbacks from payment providers.
 * These are called directly by EcoCash/OneMoney servers after customer approves payment.
 *
 * SECURITY MODEL (PSP):
 * 1. IP allow-list at Cloudflare / firewall (defence in depth, not relied on alone)
 * 2. Provider signature validated where the provider signs; where it does not
 *    (EcoCash, OneMoney), SUCCESS is only applied after a status-API query agrees
 * 3. Callback persisted to psp_callback_inbox before processing; failures are retried
 * 4. Idempotency: duplicate deliveries are counted, never re-applied
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

    private final CallbackInboxService         callbackInbox;

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

    /**
     * PSP: every callback is now persisted to the callback inbox BEFORE it is
     * processed (see CallbackInboxService). Previously a processing exception
     * was swallowed with a 200 "ACK" and the callback was lost for good, and
     * EcoCash/OneMoney SUCCESS callbacks were applied without any check that
     * they really came from the provider. Now: stored first, de-duplicated,
     * SUCCESS confirmed with the provider's status API, retried on failure,
     * and escalated to a reconciliation break if it still cannot be applied.
     */
    private ResponseEntity<String> handleCallback(String provider, String rawBody, HttpServletRequest request) {
        log.info("[CALLBACK] Received {} callback from IP: {}", provider, request.getRemoteAddr());
        String ackBody = "ONEMONEY".equals(provider) ? "SUCCESS" : "OK";
        CallbackInboxService.Ack ack = callbackInbox.receive(provider, rawBody, extractHeaders(request), clientIp(request), ackBody);
        return ResponseEntity.status(ack.httpStatus()).body(ack.body());
    }

    private String clientIp(HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        return fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : request.getRemoteAddr();
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