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
    /** Where the PSP's checkout may send customers: the setting, and the one that goes with this store's address. */
    public final List<String> checkoutOrigins;

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
        String derived = paysOn(this.publicUrl);
        String set = trim(checkoutOrigin);
        this.checkoutOrigin = set.isEmpty() ? derived : set;
        this.checkoutOrigins = derived.isEmpty() || derived.equals(this.checkoutOrigin) ? List.of(this.checkoutOrigin) : List.of(this.checkoutOrigin, derived);
    }

    /** store.payport.africa pays on app.payport.africa; store-staging.payport.africa on app-staging.payport.africa. */
    static String paysOn(String storeUrl) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(https?://)store(-[a-z0-9]+)?\\.(.+)$").matcher(storeUrl == null ? "" : storeUrl);
        return m.matches() ? m.group(1) + "app" + (m.group(2) == null ? "" : m.group(2)) + "." + m.group(3) : "";
    }

    /** Payport's own payment pages (any https address on payport.africa), or the configured checkout address. */
    public boolean checkoutAllowed(String url) {
        if (url == null) return false;
        if (checkoutOrigins.stream().anyMatch(o -> !o.isEmpty() && url.startsWith(o + "/"))) return true;
        try {
            java.net.URI u = java.net.URI.create(url);
            String h = u.getHost() == null ? "" : u.getHost().toLowerCase(java.util.Locale.ROOT);
            return "https".equals(u.getScheme()) && u.getUserInfo() == null && (h.equals("payport.africa") || h.endsWith(".payport.africa"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static String trim(String url) {
        return url == null ? "" : url.trim().replaceAll("/+$", "");
    }
}
