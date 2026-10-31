package zw.co.payport.gateway.service.adaptor.econet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Risk R76 mitigation for the end-of-life Apache XML-RPC 3.1.3 client (no fixed release exists):
 *
 *  - CVE-2019-17570 (CVSS 9.8): the client's response parser deserialises a server-supplied
 *    {@code faultCause} with {@code ObjectInputStream} (remote code execution from a malicious or
 *    impersonated server). Any response mentioning {@code faultCause}, an {@code ex:serializable}
 *    value or a Java serialisation stream (base64 "rO0AB") is refused BEFORE the library parses it.
 *  - CVE-2016-5004 (GHSA-r2pg-w96p-pcpj, moderate): resource exhaustion through compressed content.
 *    Payport never asks for compression and reads at most {@link #MAX_BYTES} (after any decompression)
 *    before refusing.
 *  - XML entity tricks: DOCTYPE / ENTITY declarations are refused.
 *
 * Econet's genuine responses (methodResponse with params, or a fault with faultCode/faultString) pass.
 */
public final class XmlRpcResponseGuard {

    public static final int MAX_BYTES = 256 * 1024;

    private XmlRpcResponseGuard() {}

    /** Thrown when a response is refused; the delivery outcome is then UNKNOWN and resolved by query, never retried blindly. */
    public static class RefusedResponse extends Exception {
        public RefusedResponse(String message) { super(message); }
    }

    /** Reads at most MAX_BYTES + 1 bytes and checks them; returns the bytes to hand to the parser. */
    public static byte[] read(InputStream in) throws RefusedResponse, IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BYTES) throw new RefusedResponse("XML-RPC response larger than " + MAX_BYTES + " bytes refused");
        }
        byte[] body = out.toByteArray();
        check(body);
        return body;
    }

    public static void check(byte[] body) throws RefusedResponse {
        if (body.length > MAX_BYTES) throw new RefusedResponse("XML-RPC response larger than " + MAX_BYTES + " bytes refused");
        String raw = new String(body, StandardCharsets.ISO_8859_1);
        String text = raw.toLowerCase(Locale.ROOT);
        if (text.contains("faultcause")) throw new RefusedResponse("XML-RPC response carries a faultCause (CVE-2019-17570 trigger) and was refused");
        if (text.contains("serializable")) throw new RefusedResponse("XML-RPC response carries a serialised Java object and was refused");
        if (raw.contains("rO0AB") || raw.contains("¬í\u0000\u0005")) throw new RefusedResponse("XML-RPC response carries a Java serialisation stream and was refused");
        if (text.contains("<!doctype") || text.contains("<!entity")) throw new RefusedResponse("XML-RPC response declares a DOCTYPE or ENTITY and was refused");
    }
}
