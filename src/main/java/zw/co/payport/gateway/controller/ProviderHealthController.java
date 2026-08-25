package zw.co.payport.gateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.entity.ProviderHealth;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.repository.ProviderHealthRepository;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/admin/health-status")
@RequiredArgsConstructor
public class ProviderHealthController {

    private final ProviderHealthRepository healthRepository;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ProviderHealth>>> getProviderHealth(HttpServletRequest req) {
        String requestId = req.getHeader("X-Request-ID");
        if (requestId == null || requestId.isEmpty()) {
            requestId = UUID.randomUUID().toString();
        }

        log.info("[HEALTH-API] Fetching provider health data");

        try {
            List<ProviderHealth> healthData = healthRepository.findAll();
            log.info("[HEALTH-API] Found {} provider health records", healthData.size());

            // Log each record for debugging
            healthData.forEach(h ->
                    log.info("[HEALTH-API] {} → {} | {}",
                            h.getProviderCode(), h.getStatus(), h.getDetail())
            );

            return ResponseEntity.ok(ApiResponse.success(healthData, requestId));

        } catch (Exception e) {
            log.error("[HEALTH-API] Error fetching health data: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("INTERNAL_ERROR", e.getMessage(), requestId));
        }
    }

    @GetMapping("/{providerCode}")
    public ResponseEntity<ApiResponse<ProviderHealth>> getProviderHealthByCode(
            @PathVariable String providerCode,
            HttpServletRequest req) {

        String requestId = req.getHeader("X-Request-ID");
        if (requestId == null || requestId.isEmpty()) {
            requestId = UUID.randomUUID().toString();
        }

        log.info("[HEALTH-API] Fetching health for provider: {}", providerCode);

        try {
            ProviderHealth health = healthRepository.findById(providerCode.toUpperCase())
                    .orElse(null);

            if (health == null) {
                return ResponseEntity.status(404)
                        .body(ApiResponse.error("NOT_FOUND", "Provider not found", requestId));
            }

            return ResponseEntity.ok(ApiResponse.success(health, requestId));

        } catch (Exception e) {
            log.error("[HEALTH-API] Error fetching health for {}: {}", providerCode, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("INTERNAL_ERROR", e.getMessage(), requestId));
        }
    }
}