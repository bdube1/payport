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
