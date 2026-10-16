package zw.co.payport.gateway.merchant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;

/**
 * Webhook URLs are chosen by merchants, so PayPort must never be tricked into calling its own
 * network (R13, SSRF): HTTPS only, no credentials or fragments in the URL, and every address the
 * host resolves to must be public -- not loopback, private (10/8, 172.16/12, 192.168/16),
 * carrier-grade NAT (100.64/10), link-local (169.254/16, incl. cloud metadata 169.254.169.254),
 * multicast, unspecified, IPv6 unique-local (fc00::/7) or link-local (fe80::/10), or an
 * IPv4-mapped form of those. Checked at registration and again before every delivery (DNS can
 * change); redirects are never followed.
 *
 * payport.webhooks.allow-private-targets=true (and allow-http) exist only for local testing.
 */
@Component
public class WebhookTargetGuard {

    private final boolean allowPrivate;
    private final boolean allowHttp;

    public WebhookTargetGuard(@Value("${payport.webhooks.allow-private-targets:false}") boolean allowPrivate,
                              @Value("${payport.webhooks.allow-http:false}") boolean allowHttp) {
        this.allowPrivate = allowPrivate;
        this.allowHttp = allowHttp;
    }

    /** Throws IllegalArgumentException with a reason the merchant can act on. */
    public URI check(String url) {
        URI u;
        try {
            u = URI.create(url == null ? "" : url.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("not a valid URL");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        if (!scheme.equals("https") && !(allowHttp && scheme.equals("http"))) throw new IllegalArgumentException("webhook URLs must use https");
        if (u.getHost() == null || u.getHost().isBlank()) throw new IllegalArgumentException("the URL has no host");
        if (u.getUserInfo() != null) throw new IllegalArgumentException("credentials in the URL are not allowed");
        if (u.getFragment() != null) throw new IllegalArgumentException("fragments are not allowed");
        int port = u.getPort();
        if (port != -1 && port != 443 && port != 8443 && port < 1024 && !(allowHttp && port == 80)) {
            throw new IllegalArgumentException("port " + port + " is not allowed");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(u.getHost());
        } catch (Exception e) {
            throw new IllegalArgumentException("the host does not resolve");
        }
        for (InetAddress a : addresses) {
            // Link-local (169.254.x.x, fe80::, cloud metadata services) is refused even in local-testing mode.
            if (a.isLinkLocalAddress() || a.isAnyLocalAddress() || a.isMulticastAddress()) {
                throw new IllegalArgumentException("the host resolves to a non-public address");
            }
            if (!allowPrivate && !isPublic(a)) throw new IllegalArgumentException("the host resolves to a non-public address");
        }
        return u;
    }

    static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) return false;                       // fc00::/7 unique local
            boolean mapped = true;                                          // ::ffff:a.b.c.d
            for (int i = 0; i < 10; i++) if (b[i] != 0) { mapped = false; break; }
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return isPublic(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (Exception e) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Inet4Address) {
            int o0 = b[0] & 0xff, o1 = b[1] & 0xff;
            if (o0 == 0) return false;                                      // 0.0.0.0/8
            if (o0 == 100 && o1 >= 64 && o1 <= 127) return false;           // 100.64/10 CGNAT
            if (o0 == 192 && o1 == 0 && (b[2] & 0xff) == 0) return false;   // 192.0.0/24
            if (o0 == 198 && (o1 == 18 || o1 == 19)) return false;          // benchmarking
            if (o0 >= 240) return false;                                    // reserved, broadcast
        }
        return true;
    }
}
