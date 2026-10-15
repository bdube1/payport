package zw.co.payport.gateway.psp.platform.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Keys from environment variables:
 * <pre>
 * PAYPORT_DATA_KEYS=k2026a:BASE64_32_BYTES,k2025b:BASE64_32_BYTES
 * PAYPORT_DATA_KEY_ACTIVE=k2026a
 * PAYPORT_INDEX_KEY=BASE64_32_BYTES
 * </pre>
 * Generate a key with {@code openssl rand -base64 32}. Rotation = add a new
 * key, switch ACTIVE, run POST /api/v1/psp/admin/crypto/re-encrypt, then
 * remove the old key once nothing references it.
 * To use an HSM instead, register your own KeyProvider bean annotated @Primary.
 */
@Component
public class EnvKeyProvider implements KeyProvider {

    private static final Logger log = LoggerFactory.getLogger(EnvKeyProvider.class);

    private final Map<String, SecretKey> keys = new LinkedHashMap<>();
    private final String active;
    private final SecretKey indexKey;

    public EnvKeyProvider(@Value("${PAYPORT_DATA_KEYS:}") String keySpec,
                          @Value("${PAYPORT_DATA_KEY_ACTIVE:}") String active,
                          @Value("${PAYPORT_INDEX_KEY:}") String indexKey) {
        if (keySpec != null && !keySpec.isBlank()) {
            for (String part : keySpec.split(",")) {
                String[] kv = part.trim().split(":", 2);
                if (kv.length != 2) throw new IllegalStateException("PAYPORT_DATA_KEYS entry must be id:base64");
                keys.put(kv[0].trim(), aes(kv[1].trim(), "PAYPORT_DATA_KEYS[" + kv[0] + "]"));
            }
        }
        this.active = (active == null || active.isBlank()) ? keys.keySet().stream().findFirst().orElse(null) : active.trim();
        if (this.active != null && !keys.containsKey(this.active)) {
            throw new IllegalStateException("PAYPORT_DATA_KEY_ACTIVE=" + this.active + " is not in PAYPORT_DATA_KEYS");
        }
        this.indexKey = (indexKey == null || indexKey.isBlank()) ? null : aesLike(indexKey.trim());
        if (keys.isEmpty()) {
            log.error("[CRYPTO] PAYPORT_DATA_KEYS is not set: encrypted fields (payout accounts, national IDs) cannot be written");
        }
    }

    @Override
    public String activeKeyId() {
        if (active == null) throw new IllegalStateException("No data encryption key configured (PAYPORT_DATA_KEYS)");
        return active;
    }

    @Override
    public SecretKey key(String keyId) {
        SecretKey k = keys.get(keyId);
        if (k == null) throw new IllegalStateException("Unknown data key id " + keyId + " -- was it removed before re-encryption finished?");
        return k;
    }

    @Override
    public Set<String> keyIds() {
        return keys.keySet();
    }

    @Override
    public SecretKey indexKey() {
        if (indexKey == null) throw new IllegalStateException("PAYPORT_INDEX_KEY is not configured");
        return indexKey;
    }

    private static SecretKey aes(String b64, String name) {
        byte[] raw = Base64.getDecoder().decode(b64);
        if (raw.length != 32) throw new IllegalStateException(name + " must be 32 bytes (AES-256), got " + raw.length);
        return new SecretKeySpec(raw, "AES");
    }

    private static SecretKey aesLike(String b64) {
        byte[] raw = Base64.getDecoder().decode(b64);
        if (raw.length < 32) throw new IllegalStateException("PAYPORT_INDEX_KEY must be at least 32 bytes");
        return new SecretKeySpec(raw, "HmacSHA256");
    }
}
