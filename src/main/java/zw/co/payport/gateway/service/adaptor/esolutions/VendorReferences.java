package zw.co.payport.gateway.service.adaptor.esolutions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * eSolutions vendor references (max 20 characters, letters and digits).
 *
 *  - Delivery references are derived from the PayPort reference and the delivery attempt, and are
 *    recorded on the payment BEFORE the request is sent. A resend/lookup after an unknown outcome
 *    therefore always names the reference eSolutions actually received (Phase 5 fix: the lookup
 *    used to send the PayPort reference, which eSolutions never saw, so an issued token could be
 *    recorded as not delivered).
 *  - Lookup/resend references only need to be unique: prefix + time + random digits. (They were
 *    prefix + time to the second, so two requests in the same second collided.)
 */
public final class VendorReferences {

    public static final int MAX = 20;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    private VendorReferences() {}

    /** PP-API-20261016-000042, attempt 1 -> API20261016000042D1 (unique per payment and attempt). */
    public static String forDelivery(String payportReference, int attempt) {
        String compact = payportReference == null ? "" : payportReference.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (compact.startsWith("PP")) compact = compact.substring(2);
        String suffix = "D" + Math.max(1, attempt);
        if (!compact.isEmpty() && compact.length() + suffix.length() <= MAX) return compact + suffix;
        // Non-standard reference: a stable hash keeps it unique and within the limit.
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(String.valueOf(payportReference).getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(h).toUpperCase();
            return "H" + hex.substring(0, MAX - 1 - suffix.length()) + suffix;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Unique reference for a lookup, resend or customer-information request. */
    public static String lookup(String prefix) {
        String p = prefix == null ? "" : prefix.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (p.length() > 4) p = p.substring(0, 4);
        String digits = String.format("%04d", RANDOM.nextInt(10_000));
        String ref = p + LocalDateTime.now().format(TS) + digits;
        return ref.length() <= MAX ? ref : ref.substring(ref.length() - MAX);
    }
}
