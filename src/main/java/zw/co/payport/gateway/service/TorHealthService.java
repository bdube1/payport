package zw.co.payport.gateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.Socket;

@Slf4j
@Service
public class TorHealthService {

    private static final String TOR_HOST = "localhost";
    private static final int TOR_PORT = 9050;
    private static final int CONNECT_TIMEOUT_MS = 3000;

    private volatile boolean lastCheckWasHealthy = true;

    @EventListener(ApplicationReadyEvent.class)
    public void checkOnStartup() {
        log.info("[TOR] Checking Tor SOCKS proxy on startup...");
        ensureTorIsRunning();
    }

    @Scheduled(fixedDelay = 120_000, initialDelay = 120_000)
    public void periodicCheck() {
        ensureTorIsRunning();
    }

    private void ensureTorIsRunning() {
        if (isTorReachable()) {
            if (!lastCheckWasHealthy) {
                log.info("[TOR] Proxy is back up at {}:{}", TOR_HOST, TOR_PORT);
            }
            lastCheckWasHealthy = true;
            return;
        }

        lastCheckWasHealthy = false;
        log.warn("[TOR] Proxy at {}:{} is not accepting connections -- EcoCash calls will fail until this is fixed. Attempting to restart the Tor service.", TOR_HOST, TOR_PORT);
        restartTorService();
    }

    private boolean isTorReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(TOR_HOST, TOR_PORT), CONNECT_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void restartTorService() {
        try {
            Process process = new ProcessBuilder("systemctl", "restart", "tor")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);

            if (!finished) {
                log.error("[TOR] `systemctl restart tor` did not finish within 15s -- check manually.");
                return;
            }

            if (process.exitValue() == 0) {
                log.info("[TOR] `systemctl restart tor` completed. Re-checking the proxy...");
                Thread.sleep(5000);
                if (isTorReachable()) {
                    log.info("[TOR] Restart successful -- proxy is now accepting connections.");
                    lastCheckWasHealthy = true;
                } else {
                    log.error("[TOR] Restart command succeeded but the proxy still isn't reachable 5s later -- this needs manual attention.");
                }
            } else {
                String output = new String(process.getInputStream().readAllBytes());
                log.error("[TOR] `systemctl restart tor` exited with code {}: {}", process.exitValue(), output);
            }
        } catch (Exception e) {
            log.error("[TOR] Could not restart the Tor service (is the gateway process missing permission to run systemctl?): {}", e.getMessage(), e);
        }
    }
}
