package zw.co.payport.gateway.psp.common;

import java.security.SecureRandom;

/**
 * Collision-resistant, time-sortable public references, e.g. {@code stl_01J9Z6Q4X8M2KQH7V3T5R0N1C}.
 * 48 bits of millisecond time + 80 random bits, Crockford base32 (ULID layout).
 * Replaces the legacy 6-random-digits-per-day scheme, which starts colliding
 * after a few hundred payments a day.
 */
public final class Refs {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Refs() {}

    public static String newRef(String prefix) {
        return prefix + "_" + ulid();
    }

    public static String ulid() {
        long time = System.currentTimeMillis();
        byte[] rnd = new byte[10];
        RANDOM.nextBytes(rnd);
        char[] out = new char[26];
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (time & 31)];
            time >>>= 5;
        }
        // 80 random bits -> 16 base32 chars
        long hi = 0;
        for (int i = 0; i < 5; i++) hi = (hi << 8) | (rnd[i] & 0xFF);
        long lo = 0;
        for (int i = 5; i < 10; i++) lo = (lo << 8) | (rnd[i] & 0xFF);
        for (int i = 17; i >= 10; i--) { out[i] = ALPHABET[(int) (hi & 31)]; hi >>>= 5; }
        for (int i = 25; i >= 18; i--) { out[i] = ALPHABET[(int) (lo & 31)]; lo >>>= 5; }
        return new String(out);
    }

    /** Random digits for human-typed codes (USSD, cash-out vouchers). Not for anything that must be unguessable at scale. */
    public static String digits(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(RANDOM.nextInt(10));
        return sb.toString();
    }
}
