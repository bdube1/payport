package zw.co.payport.commerce;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Store shopper passwords: PBKDF2-SHA256 with a random salt; only the hash is kept. */
final class Passwords {

    private static final int ITERATIONS = 210_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Passwords() {}

    /** At least 8 characters, with letters and numbers. */
    static void check(String password) {
        if (password == null || password.length() < 8 || password.length() > 100) throw new IllegalArgumentException("Use a password of at least 8 characters.");
        if (!password.matches(".*[A-Za-z].*") || !password.matches(".*[0-9].*")) throw new IllegalArgumentException("Use letters and numbers in your password.");
    }

    static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }

    static boolean matches(String password, String stored) {
        if (password == null || stored == null) return false;
        String[] p = stored.split("\\$");
        if (p.length != 4 || !"pbkdf2".equals(p[0])) return false;
        byte[] want = Base64.getDecoder().decode(p[3]);
        return MessageDigest.isEqual(want, derive(password, Base64.getDecoder().decode(p[2]), Integer.parseInt(p[1])));
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, 256)).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
