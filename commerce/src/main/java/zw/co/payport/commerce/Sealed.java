package zw.co.payport.commerce;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encryption for chat messages, names and phone numbers kept by the store (AES-256-GCM, a new random nonce for every
 * value). The key is COMMERCE_DATA_KEY (base64 of 32 bytes); without it, a key is derived from COMMERCE_TOKEN_SECRET
 * so the store still never keeps these in plain text. Phone numbers are also kept as a keyed hash, to find a
 * conversation again without the number itself.
 */
@Component
public class Sealed {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;
    private final SecretKeySpec hashKey;

    public Sealed(@Value("${commerce.data-key:}") String dataKey, Settings settings) {
        byte[] k;
        if (dataKey != null && !dataKey.isBlank()) {
            k = Base64.getDecoder().decode(dataKey.trim());
            if (k.length != 32) throw new IllegalStateException("COMMERCE_DATA_KEY must be base64 of 32 bytes");
        } else {
            k = hmac(settings.tokenSecret.getBytes(StandardCharsets.UTF_8), "payport-commerce-data-key");
        }
        this.key = new SecretKeySpec(k, "AES");
        this.hashKey = new SecretKeySpec(hmac(k, "payport-commerce-lookup"), "HmacSHA256");
    }

    static byte[] hmac(byte[] key, String text) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String seal(String plain) {
        if (plain == null) return null;
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return "v1:" + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Could not protect the message");
        }
    }

    public String open(String sealed) {
        if (sealed == null) return null;
        if (!sealed.startsWith("v1:")) return null;
        try {
            byte[] in = Base64.getDecoder().decode(sealed.substring(3));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, in, 0, 12));
            return new String(c.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** A keyed hash (not reversible without the key) for finding a record by phone number. */
    public String lookup(String value) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(hashKey);
            byte[] h = m.doFinal((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : h) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
