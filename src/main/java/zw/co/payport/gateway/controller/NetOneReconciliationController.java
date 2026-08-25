package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.service.adaptor.netone.NetOneAirtimeAdaptor;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Ops-facing NetOne diagnostics -- direct, read-only lookups against
 * NetOne's own API, testable through Postman against this gateway
 * (which holds the real configured credentials) rather than hitting
 * NetOne directly. Deliberately limited to read-only spec methods
 * (balances, pin stock, transaction query) -- money-moving actions
 * (recharge, bulk EVD purchase) are exercised through the real
 * transaction/bundle flows instead, not a "just for testing" shortcut
 * that could actually spend real balance.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/netone")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OPS','ADMIN')")
public class NetOneReconciliationController {

    private final NetOneAirtimeAdaptor netOneAdaptor;
    private final SecureRandom secureRandom = new SecureRandom();

    @GetMapping("/wallet-balance")
    public ResponseEntity<ApiResponse<Map<String, Object>>> walletBalance(HttpServletRequest req) {
        try {
            NetOneAirtimeAdaptor.WalletBalanceResult result = netOneAdaptor.getWalletBalance(agentRef("BAL"));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("replyCode", result.getReplyCode());
            body.put("replyMsg", result.getReplyMsg());
            body.put("walletBalance", result.getWalletBalance());
            body.put("currency", "ZWG");
            return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("QUERY_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @GetMapping("/wallet-balance-usd")
    public ResponseEntity<ApiResponse<Map<String, Object>>> walletBalanceUsd(HttpServletRequest req) {
        try {
            NetOneAirtimeAdaptor.WalletBalanceResult result = netOneAdaptor.getWalletBalanceUsd(agentRef("BALUSD"));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("replyCode", result.getReplyCode());
            body.put("replyMsg", result.getReplyMsg());
            body.put("walletBalance", result.getWalletBalance());
            body.put("currency", "USD");
            return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("QUERY_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @GetMapping("/end-user-balance")
    public ResponseEntity<ApiResponse<Map<String, Object>>> endUserBalance(
            @RequestParam String mobile, HttpServletRequest req) {
        try {
            NetOneAirtimeAdaptor.EndUserBalanceResult result = netOneAdaptor.getEndUserBalance(mobile, agentRef("ENDUSER"));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("replyCode", result.getReplyCode());
            body.put("replyMsg", result.getReplyMsg());
            body.put("mobileBalance", result.getMobileBalance());
            body.put("windowPeriod", result.getWindowPeriod());
            return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("QUERY_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @GetMapping("/pin-stock")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pinStock(HttpServletRequest req) {
        try {
            NetOneAirtimeAdaptor.PinStockResult result = netOneAdaptor.queryPinStock(agentRef("PINSTOCK"));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("replyCode", result.getReplyCode());
            body.put("replyMsg", result.getReplyMsg());
            body.put("inStock", result.getInStock());
            return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("QUERY_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @GetMapping("/query-transaction")
    public ResponseEntity<ApiResponse<Map<String, Object>>> queryTransaction(
            @RequestParam String reference, HttpServletRequest req) {
        try {
            NetOneAirtimeAdaptor.QueryTransactionResult result =
                    netOneAdaptor.queryTransaction(agentRef("QUERY"), reference);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("replyCode", result.getReplyCode());
            body.put("replyMsg", result.getReplyMsg());
            body.put("originalAgentReference", result.getOriginalAgentReference());
            body.put("status", result.isSuccess() ? "SUCCESS" : result.isPending() ? "PENDING" : result.isBusy() ? "BUSY" : "FAILED_OR_NOT_FOUND");
            body.put("rawReply", result.getRawReply());
            return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
        } catch (Exception e) {
            log.error("[NETONE_RECON] Query failed for reference {}: {}", reference, e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.error("QUERY_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @GetMapping("/data-bundles")
    public ResponseEntity<ApiResponse<Map<String, Object>>> dataBundles(
            @RequestParam(defaultValue = "USD") String currency, HttpServletRequest req) {
        try {
            NetOneAirtimeAdaptor.AvailableBundlesResult result = netOneAdaptor.getAvailableDataBundles(agentRef("BUNDLES"), currency);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("replyCode", result.getReplyCode());
            body.put("replyMsg", result.getReplyMsg());
            body.put("bundles", result.getBundles());
            return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("QUERY_FAILED", e.getMessage(), requestId(req)));
        }
    }

    private String agentRef(String label) {
        return "OPS-" + label + "-" + randomToken();
    }

    private String randomToken() {
        byte[] bytes = new byte[6];
        secureRandom.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
