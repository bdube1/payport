package zw.co.payport.gateway.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class HomeController {

    @GetMapping("/")
    public ResponseEntity<Map<String, Object>> home() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "PayPort Gateway");
        response.put("version", "1.0.0");
        response.put("timestamp", java.time.LocalDateTime.now().toString());
        response.put("endpoints", Map.of(
            "health", "/actuator/health",
            "transactions", "/api/v1/transactions",
            "products", "/api/v1/products",
            "bundles", "/api/v1/bundles"
        ));
        return ResponseEntity.ok(response);
    }
}
