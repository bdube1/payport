package zw.co.payport.gateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.service.adaptor.econet.EconetAirtimeAdaptor;

import java.util.HashMap;
import java.util.Map;

/** Staff-only -- exposes PayPort's own Econet wallet balance and float status. */
@Slf4j
@RestController
@RequestMapping("/api/v1/econet/admin")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OPS','ADMIN','FINANCE')")
public class EconetAdminController {

    private final EconetAirtimeAdaptor econetAdaptor;

    /**
     * GET /api/v1/econet/admin/balance?msisdn=263772291841&currency=USD
     * Check company balance (for ops to monitor float)
     */
    @GetMapping("/balance")
    public ResponseEntity<Map<String, Object>> getBalance(
            @RequestParam String msisdn,
            @RequestParam(defaultValue = "USD") String currency) {
        
        log.info("[ADMIN] Balance enquiry - MSISDN: {}, Currency: {}", msisdn, currency);
        
        try {
            EconetAirtimeAdaptor.BalanceResult result = econetAdaptor.getAccountBalance(msisdn, currency);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("msisdn", msisdn);
            response.put("currency", currency);
            
            if (result.getCompanyAccountAmount() != null) {
                response.put("companyBalanceCents", result.getCompanyAccountAmount());
                response.put("companyBalance", result.getCompanyAccountAmount() / 100.0);
            }
            if (result.getAvailableAmount() != null) {
                response.put("availableBalanceCents", result.getAvailableAmount());
                response.put("availableBalance", result.getAvailableAmount() / 100.0);
            }
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[ADMIN] Balance enquiry failed: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * GET /api/v1/econet/admin/validate?msisdn=263772291841&currency=USD
     * Validate if MSISDN is valid on Econet network
     */
    @GetMapping("/validate")
    public ResponseEntity<Map<String, Object>> validateMsisdn(
            @RequestParam String msisdn,
            @RequestParam(defaultValue = "USD") String currency) {
        
        log.info("[ADMIN] Validate MSISDN - Number: {}, Currency: {}", msisdn, currency);
        
        try {
            EconetAirtimeAdaptor.ValidationResult result = econetAdaptor.validateMsisdn(msisdn, currency);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", result.isValid());
            response.put("msisdn", msisdn);
            response.put("currency", currency);
            response.put("valid", result.isValid());
            response.put("statusCode", result.getStatusCode());
            response.put("description", result.getDescription());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[ADMIN] Validation failed: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * GET /api/v1/econet/admin/transaction-status?reference=PP-WEB-20260521-000018&currency=USD
     * Check transaction status directly with Econet
     */
    @GetMapping("/transaction-status")
    public ResponseEntity<Map<String, Object>> getTransactionStatus(
            @RequestParam String reference,
            @RequestParam(defaultValue = "USD") String currency) {
        
        log.info("[ADMIN] Transaction status enquiry - Reference: {}, Currency: {}", reference, currency);
        
        try {
            EconetAirtimeAdaptor.TransactionStatusResult result = econetAdaptor.getTransactionStatus(reference, currency);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", result.isSuccess());
            response.put("reference", reference);
            response.put("currency", currency);
            response.put("serial", result.getSerial());
            response.put("statusCode", result.getStatusCode());
            response.put("description", result.getDescription());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[ADMIN] Transaction status failed: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
}
