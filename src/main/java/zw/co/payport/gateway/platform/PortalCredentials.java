package zw.co.payport.gateway.platform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Temporary passwords, reset tokens and the password rule for the merchant and agent portals. */
public final class PortalCredentials {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnpqrstuvwxyz";
    private static final String DIGIT = "23456789";
    private static final String SPECIAL = "!@#$%*?";

    private PortalCredentials() {}

    /** 12 characters with an upper, a lower, a digit and a special character. */
    public static String temporaryPassword() {
        String all = UPPER + LOWER + DIGIT + SPECIAL;
        char[] c = new char[12];
        c[0] = UPPER.charAt(RANDOM.nextInt(UPPER.length()));
        c[1] = LOWER.charAt(RANDOM.nextInt(LOWER.length()));
        c[2] = DIGIT.charAt(RANDOM.nextInt(DIGIT.length()));
        c[3] = SPECIAL.charAt(RANDOM.nextInt(SPECIAL.length()));
        for (int i = 4; i < c.length; i++) c[i] = all.charAt(RANDOM.nextInt(all.length()));
        for (int i = c.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char t = c[i];
            c[i] = c[j];
            c[j] = t;
        }
        return new String(c);
    }

    /** A reset token for an emailed link; only its SHA-256 is stored. */
    public static String resetToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static void requireStrong(String password) {
        if (password == null || password.length() < 8) throw new IllegalArgumentException("Password must be at least 8 characters long");
        if (!password.matches(".*[A-Z].*")) throw new IllegalArgumentException("Password must contain at least one uppercase letter");
        if (!password.matches(".*[a-z].*")) throw new IllegalArgumentException("Password must contain at least one lowercase letter");
        if (!password.matches(".*\\d.*")) throw new IllegalArgumentException("Password must contain at least one digit");
        if (!password.matches(".*[^A-Za-z0-9].*")) throw new IllegalArgumentException("Password must contain at least one special character");
    }
}
