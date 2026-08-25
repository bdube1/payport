package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.model.omari.OmariQueryResponse;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.service.adaptor.PaymentProviderAdaptor;
import zw.co.payport.gateway.service.adaptor.ecocash.EcoCashAdaptor;
import zw.co.payport.gateway.service.adaptor.omari.OmariPaymentAdaptor;
import zw.co.payport.gateway.service.adaptor.onemoney.OneMoneyAdaptor;
import zw.co.payport.gateway.service.adaptor.zimswitch.ZimSwitchAdaptor;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manual reconciliation lookups for the four providers that didn't
 * already have one -- EcoCash's queryTransaction() existed but was only
 * ever called by the automatic scheduler, never by a human on demand;
 * Omari, ZimSwitch, and OneMoney had no admin-facing query endpoint at
 * all, despite each adaptor's query method being fully built and correct
 * against its own spec. NetOne and Econet already had this coverage
 * (NetOneReconciliationController, EconetAdminController) -- this closes
 * the same gap for the remaining four.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/provider-query")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OPS','ADMIN')")
public class ProviderQueryController {

    private final EcoCashAdaptor ecoCashAdaptor;
    private final OmariPaymentAdaptor omariPaymentAdaptor;
    private final ZimSwitchAdaptor zimSwitchAdaptor;
    private final OneMoneyAdaptor oneMoneyAdaptor;
    private final SecureRandom secureRandom = new SecureRandom();

    @GetMapping("/ecocash")
    public ResponseEntity<ApiResponse<Map<String, Object>>> queryEcoCash(
            @RequestParam String reference, @RequestParam String mobile, HttpServletRequest req) {
        try {
            EcoCashAdaptor.QueryResult result = ecoCashAdaptor.queryTransaction(reference, mobile);
            String mapped = ecoCashAdaptor.mapEcoCashStatus(result.getTransactionStatus());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", mapped);
            body.put("providerStatus", result.getTransactionStatus());
            body.put("ecocashReference", result.getEcocashReference());
            body.put("amount", result.getAmount());
            body.put("currency", result.getCurrency());
            return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("QUERY_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @GetMapping("/omari")
    public ResponseEntity<ApiResponse<Map<String, Object>>> queryOmari(
            @RequestParam String reference, HttpServletRequest req) {
        try {
            OmariQueryResponse result = omariPaymentAdaptor.callQuery(reference);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", result.isError());
            body.put("message", result.getMessage());
            body.put("status", result.getStatus());
            body.put("responseCode", result.getResponseCode());
            body.put("amount", result.getAmount());
            body.put("currency", result.getCurrency());
            body.put("paymentReference", result.getPaymentReference());
            body.put("debitReference", result.getDebitReference());
            return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("QUERY_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @GetMapping("/zimswitch")
    public ResponseEntity<ApiResponse<Map<String, Object>>> queryZimSwitch(
            @RequestParam String checkoutId, HttpServletRequest req) {
        try {
            PaymentProviderAdaptor.PaymentStatusResult result = zimSwitchAdaptor.queryStatus(checkoutId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", result.getStatus());
            body.put("providerReference", result.getProviderReference());
            body.put("amountCents", result.getAmountCents());
            body.put("message", result.getMessage());
            return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("QUERY_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @GetMapping("/onemoney")
    public ResponseEntity<ApiResponse<Map<String, Object>>> queryOneMoney(
            @RequestParam String transOrderNo, HttpServletRequest req) {
        try {
            OneMoneyAdaptor.C2BQueryResult result = oneMoneyAdaptor.queryC2BTransaction(transOrderNo);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("orderNo", result.getOrderNo());
            body.put("orderStatus", result.getOrderStatus());
            body.put("orderAmt", result.getOrderAmt());
            body.put("actAmt", result.getActAmt());
            body.put("currency", result.getCurrency());
            return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("QUERY_FAILED", e.getMessage(), requestId(req)));
        }
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
