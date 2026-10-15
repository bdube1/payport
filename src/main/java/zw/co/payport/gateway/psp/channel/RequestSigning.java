package zw.co.payport.gateway.psp.channel;

import zw.co.payport.gateway.psp.ledger.domain.ChainHasher;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * HMAC-SHA256 request signing for partners and terminals. Pure and unit-tested.
 *
 * Canonical string (lines joined by '\n'):
 * <pre>
 *   METHOD
 *   /path
 *   query string (or empty)
 *   unix timestamp (seconds)
 *   nonce (unique per request, 16-80 chars)
 *   lowercase hex SHA-256 of the raw body (of the empty string for GET)
 * </pre>
 * Headers: X-PayPort-Key-Id, X-PayPort-Timestamp, X-PayPort-Nonce,
 * X-PayPort-Signature = base64(HMAC-SHA256(secret, canonical)).
 */
public final class RequestSigning {

    public static final long MAX_SKEW_SECONDS = 300;

    private RequestSigning() {}

    public static String canonical(String method, String path, String query, long timestamp, String nonce, byte[] body) {
        return method.toUpperCase() + "\n" + path + "\n" + (query == null ? "" : query) + "\n" + timestamp + "\n" + nonce + "\n"
                + ChainHasher.sha256(body == null ? new byte[0] : body);
    }

    public static String sign(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    /** Constant-time comparison. */
    public static boolean verify(String secret, String canonical, String presented) {
        if (presented == null) return false;
        byte[] expected = sign(secret, canonical).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, presented.trim().getBytes(StandardCharsets.US_ASCII));
    }

    public static boolean timestampFresh(long timestamp, long nowSeconds) {
        return Math.abs(nowSeconds - timestamp) <= MAX_SKEW_SECONDS;
    }

    /** Verifies an HMAC over a raw body with a hex digest, as used by Meta webhooks (X-Hub-Signature-256: sha256=...). */
    public static boolean verifyHexBodySignature(String secret, byte[] body, String header) {
        if (header == null || !header.startsWith("sha256=")) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String hex = java.util.HexFormat.of().formatHex(mac.doFinal(body));
            return MessageDigest.isEqual(hex.getBytes(StandardCharsets.US_ASCII), header.substring(7).trim().toLowerCase().getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            return false;
        }
    }
}
