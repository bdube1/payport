package zw.co.payport.gateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.service.VendorBalanceService;

import java.util.Map;

/** Staff-only -- exposes vendor float/balance data. */
@Slf4j
@RestController
@RequestMapping("/api/v1/vendors")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OPS','ADMIN','FINANCE')")
public class VendorBalanceController {

    private final VendorBalanceService vendorBalanceService;

    /**
     * Get vendor balance for a specific currency
     * @param currency Currency code (USD or ZWG) - optional, returns all if not specified
     */
    @GetMapping("/balance")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getBalance(
            @RequestParam(required = false) String currency) {

        log.info("[BALANCE] Balance enquiry request for currency: {}", currency);

        try {
            Map<String, Object> balance = vendorBalanceService.getBalance(currency);
            return ResponseEntity.ok(ApiResponse.success(balance, null));
        } catch (IllegalStateException e) {
            log.warn("[BALANCE] {}", e.getMessage());
            return ResponseEntity.status(502).body(ApiResponse.error("BALANCE_UNAVAILABLE", e.getMessage(), null));
        } catch (Exception e) {
            log.error("[BALANCE] Failed to get balance: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("BALANCE_ERROR", e.getMessage(), null));
        }
    }
}