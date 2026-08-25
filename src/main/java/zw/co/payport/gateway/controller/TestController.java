package zw.co.payport.gateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.entity.Transaction;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.repository.TransactionRepository;
import zw.co.payport.gateway.service.FulfilmentQueueService;
import zw.co.payport.gateway.service.adaptor.FulfilmentProviderAdaptor;
import zw.co.payport.gateway.service.adaptor.esolutions.zesa.ZesaTokenAdaptor;

import java.util.HashMap;
import java.util.Map;

/**
 * TEST ONLY -- both endpoints bypass real payment (free product issuance,
 * or marking a transaction as paid directly). Restricted to logged-in
 * staff.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/test")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OPS','ADMIN','FINANCE')")
public class TestController {

    private final ZesaTokenAdaptor zesaAdaptor;
    private final TransactionRepository transactionRepository;
    private final FulfilmentQueueService fulfilmentQueueService;

    /**
     * TEST ONLY: Direct token purchase - skips EcoCash payment
     * This will buy a ZESA token directly using the meter and amount
     */
    @PostMapping("/zesa-token")
    public ResponseEntity<?> getZesaTokenDirect(
            @RequestParam String meterNumber,
            @RequestParam long amountCents,
            @RequestParam(defaultValue = "USD") String currency,
            @RequestParam(defaultValue = "TEST-REF-001") String reference) {

        try {
            log.info("[TEST] Direct ZESA token purchase - meter: {} amount: {} {} ref: {}",
                    meterNumber, amountCents, currency, reference);

            // Build fulfilment request
            FulfilmentProviderAdaptor.FulfilmentRequest request =
                    FulfilmentProviderAdaptor.FulfilmentRequest.builder()
                            .payportReference(reference)
                            .recipientIdentifier(meterNumber)
                            .amountCents(amountCents)
                            .currency(currency)
                            .productTypeCode("ZESA_TOKEN")
                            .build();

            // Call ZesaTokenAdaptor directly
            FulfilmentProviderAdaptor.FulfilmentResult result = zesaAdaptor.deliver(request);

            Map<String, Object> response = new HashMap<>();
            response.put("success", result.isSuccess());
            response.put("message", result.getMessage());
            response.put("providerReference", result.getFulfilmentReference());

            if (result.isSuccess() && result.getFulfilmentData() != null) {
                response.put("tokenData", result.getFulfilmentData());
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[TEST] ZESA token purchase failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * TEST ONLY: Skip payment for an existing transaction
     * This will take an initiated transaction and directly trigger fulfilment
     */
    @PostMapping("/skip-payment/{reference}")
    public ResponseEntity<?> skipPaymentAndFulfil(@PathVariable String reference) {
        try {
            log.info("[TEST] Skipping payment for transaction: {}", reference);

            // Find transaction
            Transaction tx = transactionRepository.findByReference(reference)
                    .orElseThrow(() -> new RuntimeException("Transaction not found"));

            // Update to PAYMENT_CONFIRMED (skipping actual payment)
            tx.setStatus(TransactionStatus.PAYMENT_CONFIRMED);
            tx.setPaymentConfirmedAt(java.time.LocalDateTime.now());
            transactionRepository.save(tx);

            // Trigger fulfilment
            fulfilmentQueueService.enqueue(reference);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Payment skipped, fulfilment queued",
                    "reference", reference
            ));

        } catch (Exception e) {
            log.error("[TEST] Skip payment failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }
}