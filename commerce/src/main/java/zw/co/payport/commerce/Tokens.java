package zw.co.payport.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Merchant sessions in Commerce: HMAC-SHA256 signed, 12 hours. Issued after the PSP confirms the merchant portal
 * login once, so a merchant who is signed in keeps working in Commerce while the PSP is down.
 */
@Component
public class Tokens {

    public record Merchant(long merchantId, long userId, String role, String name) {
        public boolean manages() { return "OWNER".equals(role) || "DEVELOPER".equals(role) || "ADMIN".equals(role) || "MANAGER".equals(role); }
    }

    static final long HOURS = 12;
    private final byte[] secret;
    private final ObjectMapper json = new ObjectMapper();

    public Tokens(Settings settings) {
        this.secret = settings.tokenSecret.getBytes(StandardCharsets.UTF_8);
    }

    public String issue(Merchant m) {
        try {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("m", m.merchantId());
            p.put("u", m.userId());
            p.put("r", m.role());
            p.put("n", m.name());
            p.put("exp", Instant.now().plusSeconds(HOURS * 3600).getEpochSecond());
            String body = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(p));
            return body + "." + sign(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The merchant behind a token, or null when it is missing, forged or expired. */
    public Merchant read(String token) {
        if (token == null) return null;
        int dot = token.indexOf('.');
        if (dot < 1) return null;
        String body = token.substring(0, dot), sig = token.substring(dot + 1);
        if (!MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.US_ASCII), sig.getBytes(StandardCharsets.US_ASCII))) return null;
        try {
            Map<?, ?> p = json.readValue(Base64.getUrlDecoder().decode(body), Map.class);
            if (((Number) p.get("exp")).longValue() < Instant.now().getEpochSecond()) return null;
            return new Merchant(((Number) p.get("m")).longValue(), ((Number) p.get("u")).longValue(), String.valueOf(p.get("r")), String.valueOf(p.get("n")));
        } catch (Exception e) {
            return null;
        }
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
