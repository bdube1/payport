package zw.co.payport.gateway.psp.common;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Wallet and agent PINs (USSD / app). PBKDF2-HMAC-SHA256, 210k iterations,
 * per-PIN salt. Weak PINs (1234, 0000, repeated or sequential digits) are refused.
 * Format: pbkdf2$iterations$salt$hash
 */
public final class Pins {

    private static final int ITERATIONS = 210_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Pins() {}

    public static String hash(String pin) {
        validate(pin);
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derive(pin, salt, ITERATIONS));
    }

    /** Same hashing without the weak-PIN rules: for one-time codes, which are random and may look like 1234. */
    public static String hashUnchecked(String secret) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derive(secret, salt, ITERATIONS));
    }

    public static boolean matches(String pin, String stored) {
        if (pin == null || stored == null) return false;
        String[] p = stored.split("\\$");
        if (p.length != 4 || !"pbkdf2".equals(p[0])) return false;
        byte[] expected = Base64.getDecoder().decode(p[3]);
        byte[] actual = derive(pin, Base64.getDecoder().decode(p[2]), Integer.parseInt(p[1]));
        return MessageDigest.isEqual(expected, actual);
    }

    public static void validate(String pin) {
        if (pin == null || !pin.matches("\\d{4,6}")) throw PspException.badRequest("INVALID_PIN", "PIN must be 4 to 6 digits");
        if (pin.chars().distinct().count() == 1) throw PspException.badRequest("WEAK_PIN", "PIN cannot be one repeated digit");
        boolean asc = true, desc = true;
        for (int i = 1; i < pin.length(); i++) {
            if (pin.charAt(i) - pin.charAt(i - 1) != 1) asc = false;
            if (pin.charAt(i - 1) - pin.charAt(i) != 1) desc = false;
        }
        if (asc || desc) throw PspException.badRequest("WEAK_PIN", "PIN cannot be a sequence like 1234");
    }

    private static byte[] derive(String pin, byte[] salt, int iterations) {
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(new PBEKeySpec(pin.toCharArray(), salt, iterations, 256)).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PIN hashing unavailable", e);
        }
    }
}
