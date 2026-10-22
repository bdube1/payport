package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.config.ApiKeyAuthenticationFilter;
import zw.co.payport.gateway.entity.ApiKey;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.service.ApiKeyService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lists the merchant's own API keys -- public keys and metadata only.
 * Secrets are never returned here, only once, at creation/rotation time
 * (see AdminMerchantController.approve()) -- matches the same principle
 * password hashes are never returned anywhere either.
 */
@RestController
@RequestMapping("/api/v1/merchant/api-keys")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MERCHANT_OWNER','MERCHANT_DEVELOPER')")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final zw.co.payport.gateway.merchant.MerchantPlatformService platform;

    public record CreateKeyBody(String environment, String label, List<String> scopes, java.time.LocalDateTime expiresAt) {}

    /**
     * Creates a key; the secret is returned once. TEST keys are available as soon as the merchant
     * is approved; LIVE keys only when the merchant is live-ready (enabled currency + verified
     * settlement destination). Only the owner may create LIVE keys.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody CreateKeyBody body, HttpServletRequest req) {
        Merchant merchant = currentMerchant();
        String env = body.environment() == null ? "TEST" : body.environment().trim().toUpperCase();
        try {
            if (!env.equals("TEST") && !env.equals("LIVE")) throw new IllegalArgumentException("environment must be TEST or LIVE");
            if (env.equals("LIVE")) {
                var user = zw.co.payport.gateway.config.MerchantAuthResolver.currentMerchantUserOrNull();
                if (user != null && !"OWNER".equalsIgnoreCase(user.getRole())) {
                    return ResponseEntity.status(403).body(ApiResponse.error("OWNER_ONLY", "Only the account owner can create live keys", requestId(req)));
                }
                List<String> blockers = platform.liveBlockers(merchant.getId());
                if (!blockers.isEmpty()) {
                    return ResponseEntity.status(409).body(ApiResponse.error("NOT_LIVE_READY", "Live keys are not available yet: " + String.join("; ", blockers), requestId(req)));
                }
            }
            if (body.expiresAt() != null && body.expiresAt().isBefore(java.time.LocalDateTime.now())) {
                throw new IllegalArgumentException("expiresAt must be in the future");
            }
            String scopes = zw.co.payport.gateway.merchant.ApiKeyScopes.normalise(body.scopes());
            ApiKeyService.GeneratedKeyPair pair = apiKeyService.create(merchant.getId(), env, body.label(), scopes, body.expiresAt());
            return ResponseEntity.status(201).body(ApiResponse.success(secretOnce(pair, env, scopes, body.expiresAt()), requestId(req)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_KEY_REQUEST", e.getMessage(), requestId(req)));
        }
    }

    /** Rotates a key: a new key with the same environment and scopes; the old one keeps working for the overlap (max 168h). */
    @PostMapping("/{id}/rotate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rotate(@PathVariable Long id, @RequestParam(defaultValue = "24") int overlapHours,
                                                                   HttpServletRequest req) {
        try {
            ApiKeyService.GeneratedKeyPair pair = apiKeyService.rotate(currentMerchant().getId(), id, overlapHours);
            Map<String, Object> m = secretOnce(pair, null, null, null);
            m.put("oldKeyExpiresInHours", Math.max(0, Math.min(overlapHours, 168)));
            return ResponseEntity.ok(ApiResponse.success(m, requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("ROTATE_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @PostMapping("/{id}/revoke")
    public ResponseEntity<ApiResponse<Map<String, Object>>> revoke(@PathVariable Long id, HttpServletRequest req) {
        try {
            apiKeyService.revokeOwn(currentMerchant().getId(), id);
            return ResponseEntity.ok(ApiResponse.success(Map.of("id", id, "revoked", true), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("REVOKE_FAILED", e.getMessage(), requestId(req)));
        }
    }

    private static Map<String, Object> secretOnce(ApiKeyService.GeneratedKeyPair pair, String env, String scopes, java.time.LocalDateTime expiresAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("publicKey", pair.publicKey());
        m.put("secretKey", pair.secretKey());
        if (env != null) m.put("environment", env);
        if (scopes != null) m.put("scopes", scopes);
        if (expiresAt != null) m.put("expiresAt", expiresAt);
        m.put("warning", "This secret key is shown once. Copy it now: it cannot be retrieved again, only rotated.");
        return m;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest req) {
        Merchant merchant = currentMerchant();
        List<ApiKey> keys = apiKeyService.listForMerchant(merchant.getId());
        List<Map<String, Object>> body = keys.stream().map(k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", k.getId());
            m.put("publicKey", k.getPublicKey());
            m.put("environment", k.getEnvironment());
            m.put("label", k.getLabel());
            m.put("createdAt", k.getCreatedAt());
            m.put("lastUsedAt", k.getLastUsedAt());
            m.put("revoked", k.getRevokedAt() != null);
            m.put("scopes", k.getScopes());
            m.put("expiresAt", k.getExpiresAt());
            m.put("rotatedFromId", k.getRotatedFromId());
            return m;
        }).toList();
        return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
    }

    private Merchant currentMerchant() {
        return zw.co.payport.gateway.config.MerchantAuthResolver.currentMerchant();
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
