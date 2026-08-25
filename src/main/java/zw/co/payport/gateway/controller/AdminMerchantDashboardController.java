package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.service.AdminMerchantDashboardService;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/merchants/dashboard")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OPS','ADMIN')")
public class AdminMerchantDashboardController {

    private final AdminMerchantDashboardService dashboardService;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> overview(
            @RequestParam(defaultValue = "30") int days, HttpServletRequest req) {
        Map<String, Object> overview = dashboardService.getOverview(days);
        return ResponseEntity.ok(ApiResponse.success(overview, requestId(req)));
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
