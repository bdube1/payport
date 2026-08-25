package zw.co.payport.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.entity.ApiKey;
import zw.co.payport.gateway.repository.ApiKeyRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * API keys are high-entropy random tokens, not human-chosen passwords --
 * that distinction matters for how they're hashed. bcrypt is deliberately
 * slow and can only be checked against one specific candidate hash at a
 * time (there's no way to "look up" a bcrypt hash by the raw value), which
 * is exactly right for passwords but wrong here: a client authenticates
 * with just the secret key (Authorization: Bearer sk_...), so the lookup
 * has to go the other way -- hash what arrived, find the matching row.
 * SHA-256 is what makes that direct lookup possible, and is the same
 * approach Stripe and GitHub use for API keys for this exact reason.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public record GeneratedKeyPair(String publicKey, String secretKey) {}

    /**
     * Creates a new key pair. The raw secret is returned here and ONLY
     * here -- callers must surface it to the merchant immediately (e.g. in
     * the API response to the approval action) since it can never be
     * retrieved again once this method returns; only its hash is stored.
     */
    public GeneratedKeyPair generateKeyPair(Long merchantId, String environment, String label) {
        String envPrefix = "LIVE".equals(environment) ? "live" : "test";
        String publicKey = "pk_" + envPrefix + "_" + randomToken();
        String secretKeyRaw = "sk_" + envPrefix + "_" + randomToken();

        ApiKey key = ApiKey.builder()
                .merchantId(merchantId)
                .environment(environment)
                .publicKey(publicKey)
                .secretKeyHash(sha256(secretKeyRaw))
                .label(label)
                .build();
        apiKeyRepository.save(key);

        log.info("[APIKEY] Generated {} key pair for merchant {}: {}", environment, merchantId, publicKey);
        return new GeneratedKeyPair(publicKey, secretKeyRaw);
    }

    public List<ApiKey> listForMerchant(Long merchantId) {
        return apiKeyRepository.findByMerchantId(merchantId);
    }

    public void revoke(Long keyId) {
        ApiKey key = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> new RuntimeException("API key not found: " + keyId));
        key.setRevokedAt(LocalDateTime.now());
        apiKeyRepository.save(key);
        log.info("[APIKEY] Revoked key {} for merchant {}", key.getPublicKey(), key.getMerchantId());
    }

    /**
     * Authenticates by the raw secret key alone -- matching the standard
     * `Authorization: Bearer sk_...` convention, no separate public key
     * needed in the request. Hashes the incoming value and looks up the
     * matching row directly; returns empty if not found or revoked.
     */
    public Optional<ApiKey> authenticateBySecret(String rawSecretKey) {
        if (rawSecretKey == null || rawSecretKey.isBlank()) return Optional.empty();
        return apiKeyRepository.findBySecretKeyHash(sha256(rawSecretKey))
                .filter(k -> k.getRevokedAt() == null);
    }

    private String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Could not hash API key", e);
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

