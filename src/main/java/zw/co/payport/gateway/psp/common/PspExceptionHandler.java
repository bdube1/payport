package zw.co.payport.gateway.psp.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.psp.ledger.domain.InsufficientFundsException;
import zw.co.payport.gateway.psp.ledger.domain.LedgerException;

import java.util.UUID;

/** Maps PSP errors onto the platform's existing {@link ApiResponse} envelope. Scoped to PSP controllers only. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "zw.co.payport.gateway.psp")
public class PspExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PspExceptionHandler.class);

    @ExceptionHandler(PspException.class)
    public ResponseEntity<ApiResponse<Void>> onPsp(PspException e, HttpServletRequest req) {
        return ResponseEntity.status(e.httpStatus()).body(ApiResponse.error(e.code(), e.getMessage(), requestId(req)));
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ApiResponse<Void>> onFunds(InsufficientFundsException e, HttpServletRequest req) {
        return ResponseEntity.status(422).body(ApiResponse.error("INSUFFICIENT_FUNDS", e.getMessage(), requestId(req)));
    }

    @ExceptionHandler(LedgerException.class)
    public ResponseEntity<ApiResponse<Void>> onLedger(LedgerException e, HttpServletRequest req) {
        log.error("[PSP] Ledger invariant violated: {}", e.getMessage());
        return ResponseEntity.status(422).body(ApiResponse.error("LEDGER_REJECTED", e.getMessage(), requestId(req)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> onBadArg(IllegalArgumentException e, HttpServletRequest req) {
        return ResponseEntity.status(400).body(ApiResponse.error("BAD_REQUEST", e.getMessage(), requestId(req)));
    }

    static String requestId(HttpServletRequest req) {
        String id = req != null ? req.getHeader("X-Request-ID") : null;
        return id != null ? id : UUID.randomUUID().toString();
    }
}
