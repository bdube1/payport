package zw.co.payport.gateway.psp.platform.crypto;

import javax.crypto.SecretKey;
import java.util.Set;

/**
 * Source of data-encryption keys. The default {@link EnvKeyProvider} reads
 * keys from the environment (populated from your secrets manager / Vault).
 * For production card scope, implement this against your HSM (PKCS#11 via
 * SunPKCS11, or the HSM vendor's JCE provider) so keys never leave it:
 * only {@link #key(String)} and {@link #activeKeyId()} need implementing.
 */
public interface KeyProvider {

    /** Key used to encrypt new values. */
    String activeKeyId();

    /** Any key, current or retired, so old ciphertext stays readable during rotation. */
    SecretKey key(String keyId);

    Set<String> keyIds();

    /** Separate key for blind indexes (searchable hashes of encrypted values). */
    SecretKey indexKey();
}
