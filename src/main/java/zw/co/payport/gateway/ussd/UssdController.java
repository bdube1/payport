package zw.co.payport.gateway.ussd;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * USSD aggregator callback. The aggregator signs every hop:
 *   X-PayPort-Ussd-Signature: hex HMAC-SHA256(secret, sessionId + "\n" + phoneNumber + "\n" + text)
 * Without a configured secret (payport.ussd.secret) every request is refused: fail closed.
 */
@RestController
public class UssdController {

    private final UssdService ussd;
    private final byte[] secret;

    public UssdController(UssdService ussd, @Value("${payport.ussd.secret:}") String secret) {
        this.ussd = ussd;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping(value = "/api/v1/ussd", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> hop(@RequestParam String sessionId, @RequestParam String phoneNumber,
                                      @RequestParam(required = false, defaultValue = "") String text,
                                      @RequestHeader(value = "X-PayPort-Ussd-Signature", required = false) String signature) {
        if (secret.length == 0 || signature == null || !MessageDigest.isEqual(sign(sessionId + "\n" + phoneNumber + "\n" + text).getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).contentType(MediaType.TEXT_PLAIN).body("END Not authorised.");
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(ussd.handle(sessionId, phoneNumber, text));
    }

    String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
