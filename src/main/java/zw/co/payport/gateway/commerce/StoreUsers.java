package zw.co.payport.gateway.commerce;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Payport Store users are the store's own accounts (owner, 31 Oct 2026), separate from Payport app customers. */
final class StoreUsers {

    private StoreUsers() {}

    static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
