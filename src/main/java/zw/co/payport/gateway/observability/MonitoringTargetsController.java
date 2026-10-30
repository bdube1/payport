package zw.co.payport.gateway.observability;

import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.payport.gateway.controller.ConnectivityController;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prometheus HTTP service discovery for the Blackbox exporter (Operations Control Centre): the provider addresses this
 * gateway is configured with, so the connectivity probes on the TV always follow the real configuration. Protected by
 * the scrape token (MetricsTokenFilter); returns host and port only, never credentials or paths with keys.
 */
@RestController
public class MonitoringTargetsController {

    private final Environment env;

    public MonitoringTargetsController(Environment env) {
        this.env = env;
    }

    @GetMapping("/api/v1/monitoring/targets")
    public List<Map<String, Object>> targets() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] p : ConnectivityController.PROVIDERS) {
            String hostPort = hostPort(env.getProperty(p[1], ""));
            // Econet airtime is configured as a host and a separate port.
            String bare = env.getProperty(p[1], "");
            if (hostPort == null && bare != null && !bare.isBlank() && !bare.contains("/") && env.getProperty("payport.econet.port") != null && p[1].contains("econet"))
                hostPort = bare.trim() + ":" + env.getProperty("payport.econet.port").trim();
            if (hostPort == null) continue;
            out.add(group(hostPort, p[0], "tcp_connect", "provider"));
            out.add(group(hostPort.substring(0, hostPort.lastIndexOf(':')), p[0], "icmp", "provider"));
        }
        for (String[] c : ConnectivityController.CALLBACKS) {
            String url = env.getProperty(c[1], "");
            if (url == null || !url.startsWith("http")) continue;
            out.add(group(url.split("\\?")[0], c[0], "http_any", "callback"));
        }
        return out;
    }

    private static Map<String, Object> group(String target, String name, String module, String kind) {
        Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("name", name);
        labels.put("module", module);
        labels.put("kind", kind);
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("targets", List.of(target));
        g.put("labels", labels);
        return g;
    }

    static String hostPort(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            String u = url.contains("://") ? url : "tcp://" + url;
            URI uri = URI.create(u.trim());
            if (uri.getHost() == null) return null;
            int port = uri.getPort() > 0 ? uri.getPort() : u.startsWith("https") ? 443 : u.startsWith("http") ? 80 : -1;
            return port < 0 ? null : uri.getHost() + ":" + port;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
