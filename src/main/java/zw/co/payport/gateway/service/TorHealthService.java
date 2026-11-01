package zw.co.payport.gateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Keeps EcoCash's egress route working. Every minute it checks the route itself, not just the proxy port: it opens a
 * connection to EcoCash's host through the proxy. When the proxy port is closed it restarts Tor; when the port is open
 * but EcoCash cannot be reached through it (Tor's route went bad) it asks Tor for new routes (NEWNYM on the control
 * port), and restarts Tor if that does not help. With a non-Tor proxy (ECOCASH_EGRESS_TOR=false) it only reports.
 */
@Slf4j
@Service
public class TorHealthService {

    private static final int CONNECT_TIMEOUT_MS = 8000;

    private final boolean enabled, tor;
    private final String proxyHost, controlPassword, target;
    private final int proxyPort, controlPort;
    private final Proxy.Type type;
    private volatile boolean lastCheckWasHealthy = true;
    private int routeFailures;

    public TorHealthService(@Value("${payport.ecocash.egress.enabled:true}") boolean enabled,
                            @Value("${payport.ecocash.egress.tor:true}") boolean tor,
                            @Value("${payport.ecocash.egress.type:SOCKS}") String type,
                            @Value("${payport.ecocash.egress.host:127.0.0.1}") String proxyHost,
                            @Value("${payport.ecocash.egress.port:9050}") int proxyPort,
                            @Value("${payport.ecocash.egress.tor-control-port:9051}") int controlPort,
                            @Value("${payport.ecocash.egress.tor-control-password:}") String controlPassword,
                            @Value("${payport.ecocash.egress.check-host:payonline.econet.co.zw}") String target) {
        this.enabled = enabled;
        this.tor = tor;
        this.type = "HTTP".equalsIgnoreCase(type.trim()) ? Proxy.Type.HTTP : Proxy.Type.SOCKS;
        this.proxyHost = proxyHost.trim();
        this.proxyPort = proxyPort;
        this.controlPort = controlPort;
        this.controlPassword = controlPassword == null ? "" : controlPassword;
        this.target = target.trim();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkOnStartup() {
        if (!enabled) return;
        log.info("[EGRESS] Checking EcoCash's route through {} {}:{}", type, proxyHost, proxyPort);
        check();
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void periodicCheck() {
        if (enabled) check();
    }

    synchronized void check() {
        if (!portOpen()) {
            lastCheckWasHealthy = false;
            log.warn("[EGRESS] Proxy {}:{} is not accepting connections; EcoCash calls fail until it is back.", proxyHost, proxyPort);
            if (tor) restartTor();
            return;
        }
        if (routeWorks()) {
            if (!lastCheckWasHealthy) log.info("[EGRESS] EcoCash reachable again through {}:{}", proxyHost, proxyPort);
            lastCheckWasHealthy = true;
            routeFailures = 0;
            return;
        }
        lastCheckWasHealthy = false;
        routeFailures++;
        log.warn("[EGRESS] Proxy is up but {} cannot be reached through it ({} in a row)", target, routeFailures);
        if (!tor) return;
        if (routeFailures == 1 && newRoutes()) return;
        if (routeFailures >= 2) {
            restartTor();
            routeFailures = 0;
        }
    }

    public boolean healthy() {
        return lastCheckWasHealthy;
    }

    private boolean portOpen() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(proxyHost, proxyPort), 3000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** A TCP connection to EcoCash's HTTPS port through the proxy (nothing is sent). */
    private boolean routeWorks() {
        if (type == Proxy.Type.HTTP) return portOpen();
        try (Socket s = new Socket(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(proxyHost, proxyPort)))) {
            s.connect(InetSocketAddress.createUnresolved(target, 443), CONNECT_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            log.debug("[EGRESS] route check failed: {}", e.getMessage());
            return false;
        }
    }

    /** Asks Tor for fresh routes through its control port (needs ControlPort in torrc); false when not possible. */
    private boolean newRoutes() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(proxyHost, controlPort), 3000);
            s.setSoTimeout(5000);
            OutputStream out = s.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            out.write(("AUTHENTICATE \"" + controlPassword.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            if (!String.valueOf(in.readLine()).startsWith("250")) {
                log.warn("[EGRESS] Tor control port refused authentication; set ECOCASH_EGRESS_TOR_CONTROL_PASSWORD");
                return false;
            }
            out.write("SIGNAL NEWNYM\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            boolean ok = String.valueOf(in.readLine()).startsWith("250");
            if (ok) log.info("[EGRESS] Asked Tor for new routes");
            return ok;
        } catch (Exception e) {
            log.debug("[EGRESS] Tor control port not available: {}", e.getMessage());
            return false;
        }
    }

    private void restartTor() {
        try {
            Process process = new ProcessBuilder("systemctl", "restart", "tor").redirectErrorStream(true).start();
            if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                log.error("[EGRESS] `systemctl restart tor` did not finish within 20s; check manually.");
                return;
            }
            if (process.exitValue() != 0) {
                log.error("[EGRESS] `systemctl restart tor` exited with code {}: {}", process.exitValue(),
                        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
                return;
            }
            log.info("[EGRESS] Tor restarted");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("[EGRESS] Could not restart Tor: {}", e.getMessage());
        }
    }
}
