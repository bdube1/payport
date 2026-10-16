package zw.co.payport.gateway.platform;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts secrets PayPort must be able to read back (webhook signing secrets) with AES-256-GCM.
 * Stored form: "enc:v1:" + base64(iv || ciphertext+tag). Values without the prefix are legacy
 * plaintext and are returned as they are (R14: they are re-encrypted when next written).
 *
 * Key: PAYPORT_DATA_KEY (base64 of 32 random bytes). Until it is configured the key is derived
 * from the JWT secret and a warning is logged -- set a separate key in production.
 */
@Slf4j
@Component
public class SecretBox {

    private static final String PREFIX = "enc:v1:";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] key;

    public SecretBox(@Value("${payport.security.data-key:}") String dataKey,
                     @Value("${payport.security.jwt-secret:}") String jwtSecret) {
        try {
            if (dataKey != null && !dataKey.isBlank()) {
                this.key = Base64.getDecoder().decode(dataKey.trim());
                if (key.length != 32) throw new IllegalArgumentException("PAYPORT_DATA_KEY must be 32 bytes (base64)");
            } else {
                log.warn("[SECRETS] PAYPORT_DATA_KEY not set: deriving the data key from the JWT secret. Set a separate key in production.");
                this.key = MessageDigest.getInstance("SHA-256").digest(("payport-data-key|" + jwtSecret).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String seal(String plaintext) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("could not encrypt secret", e);
        }
    }

    public String open(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return stored; // legacy plaintext
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, in, 0, 12));
            return new String(c.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("could not decrypt secret (wrong PAYPORT_DATA_KEY?)", e);
        }
    }

    public static boolean isSealed(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }
}
