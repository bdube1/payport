package zw.co.payport.commerce;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/** Commerce's settings, from application.properties and the environment. */
@Component
public class Settings {

    public final String pspUrl;
    public final String pspKey;
    public final String tokenSecret;
    public final String publicUrl;
    public final List<String> portalOrigins;
    public final String checkoutOrigin;

    public Settings(@Value("${commerce.psp-url}") String pspUrl, @Value("${commerce.psp-key:}") String pspKey,
                    @Value("${commerce.token-secret:}") String tokenSecret, @Value("${commerce.public-url}") String publicUrl,
                    @Value("${commerce.portal-origins:}") String portalOrigins, @Value("${commerce.checkout-origin}") String checkoutOrigin) {
        this.pspUrl = trim(pspUrl);
        this.pspKey = pspKey == null ? "" : pspKey.trim();
        this.tokenSecret = tokenSecret == null ? "" : tokenSecret.trim();
        if (this.tokenSecret.length() < 32) throw new IllegalStateException("COMMERCE_TOKEN_SECRET must be at least 32 characters");
        this.publicUrl = trim(publicUrl);
        this.portalOrigins = portalOrigins == null ? List.of()
                : Arrays.stream(portalOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Settings::trim).toList();
        this.checkoutOrigin = trim(checkoutOrigin);
    }

    static String trim(String url) {
        return url == null ? "" : url.trim().replaceAll("/+$", "");
    }
}
