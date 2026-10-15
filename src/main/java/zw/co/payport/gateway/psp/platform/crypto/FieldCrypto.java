package zw.co.payport.gateway.psp.platform.crypto;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * AES-256-GCM field encryption with key ids embedded in the ciphertext:
 * {@code enc:v1:<keyId>:<base64 iv>:<base64 ciphertext+tag>}.
 *
 * Values without the {@code enc:} prefix are treated as legacy plaintext and
 * returned unchanged; they are encrypted the next time the row is written
 * (or by the re-encryption job). This lets existing plaintext columns such as
 * merchants.payout_account_details be migrated without downtime.
 */
public final class FieldCrypto {

    public static final String PREFIX = "enc:v1:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final KeyProvider keys;

    public FieldCrypto(KeyProvider keys) {
        this.keys = keys;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        if (plaintext.startsWith(PREFIX)) return plaintext;
        try {
            String keyId = keys.activeKeyId();
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, keys.key(keyId), new GCMParameterSpec(128, iv));
            c.updateAAD(keyId.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + keyId + ":" + Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(ct);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Field encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return stored;
        String[] parts = stored.substring(PREFIX.length()).split(":", 3);
        if (parts.length != 3) throw new IllegalStateException("Malformed encrypted value");
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, keys.key(parts[0]), new GCMParameterSpec(128, Base64.getDecoder().decode(parts[1])));
            c.updateAAD(parts[0].getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Field decryption failed (wrong key or tampered value)", e);
        }
    }

    /** Key id a stored value was encrypted with, or null for plaintext. Used by rotation to find stale rows. */
    public static String keyIdOf(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return null;
        return stored.substring(PREFIX.length()).split(":", 2)[0];
    }

    /**
     * Deterministic keyed hash for equality search on encrypted data
     * (e.g. "is this national ID already registered?") without decrypting.
     */
    public String blindIndex(String value) {
        if (value == null) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(keys.indexKey());
            String normalised = value.trim().toUpperCase().replaceAll("[\\s-]", "");
            return HexFormat.of().formatHex(mac.doFinal(normalised.getBytes(StandardCharsets.UTF_8)));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Blind index failed", e);
        }
    }

    /** Masks for display: keeps the last {@code visible} characters. */
    public static String mask(String value, int visible) {
        if (value == null) return null;
        if (value.length() <= visible) return "*".repeat(value.length());
        return "*".repeat(value.length() - visible) + value.substring(value.length() - visible);
    }
}
