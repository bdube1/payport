package zw.co.payport.gateway.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import zw.co.payport.gateway.entity.ProviderHealth;
import zw.co.payport.gateway.repository.ProviderHealthRepository;
import zw.co.payport.gateway.repository.TransactionRepository;
import zw.co.payport.gateway.service.adaptor.telecel.TelecelSoapClient;

import java.math.BigDecimal;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderHealthScheduler {

    private final ProviderHealthRepository healthRepository;
    private final TransactionRepository transactionRepository;
    private final RestTemplate restTemplate;
    private final TelecelSoapClient telecelSoapClient;

    // Track previous status to only log when status changes
    private final Map<String, String> lastStatusMap = new ConcurrentHashMap<>();

    // NetOne
    @Value("${payport.netone.base-url}")
    private String netoneBaseUrl;
    @Value("${payport.netone.access-code}")
    private String netoneAccessCode;
    @Value("${payport.netone.access-password}")
    private String netoneAccessPassword;

    // EcoCash
    @Value("${payport.ecocash.base-url}")
    private String ecocashBaseUrl;
    @Value("${payport.ecocash.username}")
    private String ecocashUsername;
    @Value("${payport.ecocash.password}")
    private String ecocashPassword;

    // Econet
    @Value("${payport.econet.endpoint}")
    private String econetEndpoint;
    @Value("${payport.econet.port}")
    private int econetPort;

    // OneMoney
    @Value("${payport.onemoney.base-url}")
    private String onemoneyBaseUrl;

    // Telecel
    @Value("${payport.telecel.agent-code}")
    private String telecelAgentCode;
    @Value("${payport.telecel.mpin}")
    private String telecelMpin;

    private static final int TIMEOUT_THRESHOLD = 3;
    private static final int CHECK_TIMEOUT_MS  = 5000;

    @Scheduled(fixedDelay = 60000, initialDelay = 10000)
    public void checkAllProviders() {
        if (log.isDebugEnabled()) {
            log.debug("Running provider health checks...");
        }

        checkNetOne();
        checkEcoCash();
        checkEconet();
        checkOneMoney();
       // checkTelecel();  // Temporarily disabled
       // checkTelecash(); // Temporarily disabled

        if (log.isDebugEnabled()) {
            log.debug("Health checks completed.");
        }
    }

    private void logStatusChange(String provider, String status, String detail) {
        String previousStatus = lastStatusMap.get(provider);
        // Only log if status changed or it's a DOWN status (always log DOWN)
        if (!status.equals(previousStatus) || "DOWN".equals(status)) {
            if ("UP".equals(status)) {
                log.info("[HEALTH] {} → UP", provider);
            } else if ("TIMEOUTS".equals(status)) {
                log.warn("[HEALTH] {} → TIMEOUTS - {}", provider, detail);
            } else if ("DOWN".equals(status)) {
                log.warn("[HEALTH] {} → DOWN - {}", provider, detail);
            }
            lastStatusMap.put(provider, status);
        }
    }

    // ─── NETONE ───────────────────────────────────────────────────────────────
    private void checkNetOne() {
        String status;
        String detail;

        if (netoneBaseUrl == null || netoneBaseUrl.isEmpty() ||
                netoneAccessCode == null || netoneAccessCode.isEmpty() ||
                netoneAccessPassword == null || netoneAccessPassword.isEmpty()) {
            status = "DOWN";
            detail = "Not configured";
            persist("NETONE", "FULFILMENT", status, detail);
            logStatusChange("NETONE", status, detail);
            return;
        }

        try {
            String uniqueReference = "HEALTH-" + System.currentTimeMillis() + "-"
                    + UUID.randomUUID().toString().substring(0, 8);

            HttpHeaders headers = new HttpHeaders();
            headers.set("x-access-code", netoneAccessCode);
            headers.set("x-access-password", netoneAccessPassword);
            headers.set("x-agent-reference", uniqueReference);
            headers.set("Accept", "application/json");

            ResponseEntity<Map> response = restTemplate.exchange(
                    netoneBaseUrl + "/wallet-balance",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                int recentFailures = countRecentFailures("NETONE");
                if (recentFailures >= TIMEOUT_THRESHOLD) {
                    status = "TIMEOUTS";
                    detail = recentFailures + " recent failures";
                } else {
                    status = "UP";
                    detail = "OK";
                }
            } else {
                status = "DOWN";
                detail = "HTTP " + response.getStatusCode().value();
            }
        } catch (Exception e) {
            status = "DOWN";
            detail = e.getMessage();
        }
        persist("NETONE", "FULFILMENT", status, detail);
        logStatusChange("NETONE", status, detail);
    }

    // ─── ECOCASH ──────────────────────────────────────────────────────────────
    private void checkEcoCash() {
        String status;
        String detail = "OK";

        if (ecocashBaseUrl == null || ecocashBaseUrl.isEmpty()) {
            persist("ECOCASH", "PAYMENT", "DOWN", "Not configured");
            logStatusChange("ECOCASH", "DOWN", "Not configured");
            return;
        }

        try {
            String checkUrl = ecocashBaseUrl + "/transactions/amount";
            String encoded = java.util.Base64.getEncoder()
                    .encodeToString((ecocashUsername + ":" + ecocashPassword).getBytes());

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Basic " + encoded);
            headers.set("Content-Type", "application/json");

            Map<String, Object> testPayload = new HashMap<>();
            String ref = "HEALTH-" + System.currentTimeMillis();
            testPayload.put("clientCorrelator", ref);
            testPayload.put("notifyUrl", "https://localhost/health");
            testPayload.put("referenceCode", ref);
            testPayload.put("tranType", "MER");
            testPayload.put("endUserId", "771111111");

            restTemplate.exchange(checkUrl, HttpMethod.POST, new HttpEntity<>(testPayload, headers), String.class);
            status = "UP";
        } catch (HttpClientErrorException e) {
            // 4xx means server is reachable
            status = "UP";
            detail = "API reachable";
        } catch (Exception e) {
            status = "DOWN";
            detail = e.getMessage();
        }
        persist("ECOCASH", "PAYMENT", status, detail);
        logStatusChange("ECOCASH", status, detail);
    }

    // ─── ECONET ───────────────────────────────────────────────────────────────
    private void checkEconet() {
        String status;
        String detail = "OK";

        if (econetEndpoint == null || econetEndpoint.isEmpty()) {
            persist("ECONET", "FULFILMENT", "DOWN", "Not configured");
            logStatusChange("ECONET", "DOWN", "Not configured");
            return;
        }

        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(econetEndpoint, econetPort), CHECK_TIMEOUT_MS);
            status = "UP";
        } catch (Exception e) {
            status = "DOWN";
            detail = e.getMessage();
        }
        persist("ECONET", "FULFILMENT", status, detail);
        logStatusChange("ECONET", status, detail);
    }

    // ─── ONEMONEY ─────────────────────────────────────────────────────────────
    private void checkOneMoney() {
        String status;
        String detail = "OK";

        if (onemoneyBaseUrl == null || onemoneyBaseUrl.isEmpty()) {
            persist("ONEMONEY", "PAYMENT", "DOWN", "Not configured");
            logStatusChange("ONEMONEY", "DOWN", "Not configured");
            return;
        }

        String raw = onemoneyBaseUrl.replace("https://", "").replace("http://", "");
        String host = raw.split("/")[0].split(":")[0];
        int port;
        try {
            port = raw.contains(":") ? Integer.parseInt(raw.split(":")[1].split("/")[0])
                    : (onemoneyBaseUrl.startsWith("https") ? 443 : 80);
        } catch (NumberFormatException e) {
            port = 80;
        }

        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), CHECK_TIMEOUT_MS);
            status = "UP";
        } catch (Exception e) {
            status = "DOWN";
            detail = e.getMessage();
        }
        persist("ONEMONEY", "PAYMENT", status, detail);
        logStatusChange("ONEMONEY", status, detail);
    }

    // ─── TELECEL ─────────────────────────────────────────────────────────────
    private void checkTelecel() {
        String status;
        String detail = "OK";

        if (telecelAgentCode == null || telecelAgentCode.isEmpty() ||
                telecelMpin == null || telecelMpin.isEmpty()) {
            persist("TELECEL", "FULFILMENT", "DOWN", "Not configured");
            logStatusChange("TELECEL", "DOWN", "Not configured");
            return;
        }

        try {
            TelecelSoapClient.TelecelBalanceResult result =
                    telecelSoapClient.getBalance(telecelAgentCode, telecelMpin);

            if (result != null && result.isSuccess()) {
                status = "UP";
            } else {
                status = "DOWN";
                detail = result != null ? result.getResultDescription() : "Null response";
            }
        } catch (Exception e) {
            status = "DOWN";
            detail = e.getMessage();
        }
        persist("TELECEL", "FULFILMENT", status, detail);
        logStatusChange("TELECEL", status, detail);
    }

    // ─── TELECASH ────────────────────────────────────────────────────────────
    private void checkTelecash() {
        persist("TELECASH", "PAYMENT", "DOWN", "Pending integration");
        logStatusChange("TELECASH", "DOWN", "Pending integration");
    }

    private int countRecentFailures(String providerCode) {
        try {
            LocalDateTime tenMinutesAgo = LocalDateTime.now().minusMinutes(10);
            long failures = transactionRepository
                    .countRecentFailuresByProvider(providerCode, tenMinutesAgo);
            return (int) failures;
        } catch (Exception e) {
            return 0;
        }
    }

    private void persist(String code, String type, String status, String detail) {
        try {
            ProviderHealth health = healthRepository.findById(code)
                    .orElseGet(() -> ProviderHealth.builder()
                            .providerCode(code)
                            .providerType(type)
                            .manualOverride(false)
                            .status("UNKNOWN")
                            .build());

            if (Boolean.TRUE.equals(health.getManualOverride())) {
                return;
            }

            health.setProviderType(type);
            health.setStatus(status);
            health.setDetail(detail);
            health.setLastCheckedAt(LocalDateTime.now());

            // Calculate success rate if needed
            if ("UP".equals(status)) {
                health.setSuccessRate10m(BigDecimal.valueOf(100.00));
            } else if ("DOWN".equals(status)) {
                health.setSuccessRate10m(BigDecimal.valueOf(0.00));
            }

            healthRepository.save(health);
        } catch (Exception e) {
            log.error("Failed to persist health for {}: {}", code, e.getMessage());
        }
    }
}
