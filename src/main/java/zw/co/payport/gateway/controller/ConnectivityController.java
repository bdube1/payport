package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.Web;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Shows where this gateway sends and receives traffic, and checks each address from this server:
 *  - providers: can this server open a connection to the provider (VPN, firewall, DNS)?
 *  - callbacks: does the public callback address reach this gateway (DNS, https, proxy)? A provider posts there.
 *  - addresses: the portal and gateway addresses this environment hands out (USSD, WhatsApp, password resets).
 * Nothing is sent to a provider beyond opening and closing a connection; callbacks are probed with GET only.
 */
@RestController
public class ConnectivityController {

    public static final String[][] PROVIDERS = {
            {"EcoCash", "payport.ecocash.base-url"},
            {"OneMoney", "payport.onemoney.base-url"},
            {"O'mari", "payport.omari.base-url"},
            {"InnBucks", "payport.innbucks.base-url"},
            {"ZimSwitch (cards)", "payport.zimswitch.base-url"},
            {"NetOne", "payport.netone.base-url"},
            {"Econet airtime", "payport.econet.endpoint"},
            {"Telecel", "payport.telecel.endpoint"},
            {"e-Solutions (bills)", "payport.esolutions.base-url"},
            {"SMS (Africa's Talking)", "payport.sms.africas-talking.base-url"},
    };
    public static final String[][] CALLBACKS = {
            {"EcoCash callback", "payport.ecocash.notify-url"},
            {"OneMoney callback", "payport.onemoney.notify-url"},
            {"ZimSwitch callback", "payport.zimswitch.notify-url"},
    };

    private final Environment env;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).followRedirects(HttpClient.Redirect.NEVER).build();

    public ConnectivityController(Environment env) {
        this.env = env;
    }

    @GetMapping("/api/v1/admin/integrations/connectivity")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> check(HttpServletRequest req) {
        return Web.run(this::run, req);
    }

    Map<String, Object> run() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("environment", env.getProperty("payport.environment", "production"));
        Map<String, Object> addresses = new LinkedHashMap<>();
        String gw = env.getProperty("payport.gateway.base-url", "");
        addresses.put("Gateway", gw);
        addresses.put("Callbacks base", env.getProperty("payport.gateway.callback-base-url", ""));
        addresses.put("Consumer portal", env.getProperty("payport.consumer.base-url", ""));
        addresses.put("Merchant portal", env.getProperty("payport.portal.merchant-url", ""));
        addresses.put("Ops password reset", env.getProperty("payport.email.reset-link-base", ""));
        addresses.put("WhatsApp webhook (give to Meta)", gw + "/api/v1/whatsapp/webhook");
        addresses.put("USSD Econet (give to Econet)", gw + "/api/v1/ussd/econet?key=<access key>");
        addresses.put("USSD NetOne (give to NetOne)", gw + "/api/v1/ussd/netone?key=<access key>");
        addresses.put("USSD Telecel (give to Telecel)", gw + "/api/v1/ussd/telecel?key=<access key>");
        out.put("addresses", addresses);

        List<CompletableFuture<Map<String, Object>>> jobs = new ArrayList<>();
        for (String[] p : PROVIDERS) jobs.add(CompletableFuture.supplyAsync(() -> tcp(p[0], env.getProperty(p[1], ""))));
        List<CompletableFuture<Map<String, Object>>> cbs = new ArrayList<>();
        for (String[] c : CALLBACKS) cbs.add(CompletableFuture.supplyAsync(() -> probe(c[0], env.getProperty(c[1], ""))));
        out.put("providers", jobs.stream().map(CompletableFuture::join).toList());
        out.put("callbacks", cbs.stream().map(CompletableFuture::join).toList());
        return out;
    }

    /** Opens (and closes) a TCP connection to the provider's host and port. */
    static Map<String, Object> tcp(String name, String url) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("url", url);
        if (url == null || url.isBlank()) { m.put("status", "NOT_CONFIGURED"); return m; }
        try {
            URI u = URI.create(url.trim());
            String host = u.getHost();
            int port = u.getPort() > 0 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
            m.put("target", host + ":" + port);
            long t0 = System.nanoTime();
            InetAddress ip = InetAddress.getByName(host);
            m.put("ip", ip.getHostAddress());
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(ip, port), 6000);
            }
            m.put("ms", (System.nanoTime() - t0) / 1_000_000);
            m.put("status", "REACHABLE");
        } catch (java.net.UnknownHostException e) {
            m.put("status", "DNS_FAILED");
            m.put("detail", "The name does not resolve from this server");
        } catch (java.net.SocketTimeoutException e) {
            m.put("status", "TIMED_OUT");
            m.put("detail", "No answer within 6 s: usually the VPN or a firewall is not routing this server to the provider");
        } catch (java.net.ConnectException e) {
            m.put("status", "REFUSED");
            m.put("detail", e.getMessage());
        } catch (Exception e) {
            m.put("status", "ERROR");
            m.put("detail", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return m;
    }

    /** GET on the public callback address: any HTTP answer (405 is normal, providers POST) means it reaches the gateway. */
    Map<String, Object> probe(String name, String url) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("url", url);
        if (url == null || url.isBlank()) { m.put("status", "NOT_CONFIGURED"); return m; }
        try {
            long t0 = System.nanoTime();
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(url.trim())).timeout(Duration.ofSeconds(8)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            m.put("ms", (System.nanoTime() - t0) / 1_000_000);
            m.put("httpStatus", r.statusCode());
            boolean ok = r.statusCode() < 500 && r.statusCode() != 404;
            m.put("status", ok ? "REACHABLE" : "ERROR");
            if (!url.startsWith("https://")) m.put("detail", "Providers need an https address");
            else if (!ok) m.put("detail", "The address answers but not with this gateway (HTTP " + r.statusCode() + ")");
        } catch (java.net.http.HttpTimeoutException e) {
            m.put("status", "TIMED_OUT");
        } catch (Exception e) {
            m.put("status", "ERROR");
            m.put("detail", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return m;
    }
}
