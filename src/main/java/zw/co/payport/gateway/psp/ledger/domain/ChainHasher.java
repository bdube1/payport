package zw.co.payport.gateway.psp.ledger.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Tamper-evidence for append-only tables (ledger entries, audit events).
 *
 * 1. Each row gets a content hash when written ({@link #contentHash}).
 * 2. A sealing job periodically chains consecutive rows into a seal:
 *    sealHash = SHA-256(previousSealHash | fromId | toId | rowHash1 | rowHash2 ...).
 *
 * Editing any sealed row changes its content hash; deleting one breaks the
 * id range; rewriting a seal breaks every later seal. The latest seal hash
 * should also be exported off-box daily (e.g. emailed to the compliance
 * officer, written to WORM storage) so that rewriting the whole chain is
 * detectable too.
 */
public final class ChainHasher {

    public static final String GENESIS = "0".repeat(64);
    private static final char SEP = '\u001F'; // unit separator: cannot appear in normal field values

    private ChainHasher() {}

    /** Hash of a row's business content, fields in a fixed order. Nulls are encoded distinctly from empty strings. */
    public static String contentHash(Object... fields) {
        StringBuilder sb = new StringBuilder();
        for (Object f : fields) {
            sb.append(f == null ? "␀" : f.toString()).append(SEP);
        }
        return sha256(sb.toString());
    }

    public static String sealHash(String previousSealHash, long fromId, long toId, List<String> rowHashes) {
        StringBuilder sb = new StringBuilder()
                .append(previousSealHash).append(SEP)
                .append(fromId).append(SEP)
                .append(toId).append(SEP);
        for (String h : rowHashes) sb.append(h).append(SEP);
        return sha256(sb.toString());
    }

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String sha256(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
