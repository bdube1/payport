package zw.co.payport.gateway.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import zw.co.payport.gateway.model.response.ApiResponse;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : "N/A";
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<ApiResponse<?>> handleNotFound(TransactionNotFoundException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("TRANSACTION_NOT_FOUND", ex.getMessage(), requestId(req)));
    }

    @ExceptionHandler(DuplicateReferenceException.class)
    public ResponseEntity<ApiResponse<?>> handleDuplicate(DuplicateReferenceException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("DUPLICATE_REFERENCE", ex.getMessage(), requestId(req)));
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<ApiResponse<?>> handleInvalidTransition(InvalidStateTransitionException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("INVALID_STATE_TRANSITION", ex.getMessage(), requestId(req)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<?>> handleBadArgument(IllegalArgumentException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.error("BUSINESS_RULE_VIOLATION", ex.getMessage(), requestId(req)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<?>> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String field = ((FieldError) error).getField();
            errors.put(field, error.getDefaultMessage());
        });
        String first = errors.values().stream().filter(java.util.Objects::nonNull).findFirst().orElse("Request validation failed");
        ApiResponse<?> response = ApiResponse.builder()
                .success(false)
                .message(first)
                .error(ApiResponse.ErrorDetail.builder()
                        .code("VALIDATION_ERROR")
                        .message("Request validation failed")
                        .details(errors)
                        .build())
                .meta(ApiResponse.Meta.builder().requestId(requestId(req)).build())
                .build();
        return ResponseEntity.badRequest().body(response);
    }

    /**
     * Fixes a real, systemic bug: almost every intentional, developer-
     * written business exception in this codebase (wrong PIN, insufficient
     * funds, provider rejection, ZimSwitch not configured, etc.) is a
     * plain RuntimeException with a genuinely useful message -- but with
     * only the specific handlers above and the generic Exception.class
     * catch-all below, any RuntimeException not from that short specific
     * list fell into the catch-all, which always overwrites the message
     * with a generic "unexpected error" regardless of what actually
     * happened. This sits between them: it catches the same broad net,
     * but preserves the real message, since that's where the actually
     * useful information lives. Genuinely unexpected failures (NPEs, SQL
     * errors, I/O failures) are checked exceptions or Errors, not
     * RuntimeExceptions with a clear message -- they still fall through
     * to the generic handler below untouched.
     */
    /** A provider is suspended or not responding: nothing was sent. 503 with a code channels can act on (offer another method). */
    @ExceptionHandler(zw.co.payport.gateway.integration.ProviderUnavailableException.class)
    public ResponseEntity<ApiResponse<?>> handleProviderUnavailable(zw.co.payport.gateway.integration.ProviderUnavailableException ex, HttpServletRequest req) {
        log.warn("[GATEWAY] {} unavailable on {} {}", ex.getProviderCode(), req.getMethod(), req.getRequestURI());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "60")
                .body(ApiResponse.error("PROVIDER_UNAVAILABLE", ex.getMessage(), requestId(req)));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<?>> handleRuntimeException(RuntimeException ex, HttpServletRequest req) {
        log.warn("[GATEWAY] {} on {} {}: {}",
                ex.getClass().getSimpleName(), req.getMethod(), req.getRequestURI(), ex.getMessage());

        String message = (ex.getMessage() != null && !ex.getMessage().isBlank())
                ? ex.getMessage()
                : "That request couldn't be completed. Please try again, or contact support if the problem continues.";

        return ResponseEntity.badRequest().body(ApiResponse.error("REQUEST_FAILED", message, requestId(req)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleGeneral(Exception ex, HttpServletRequest req) {
        log.error("[GATEWAY] Unhandled exception on {} {}: {}",
                req.getMethod(), req.getRequestURI(), ex.getMessage(), ex);

        // The reference is recorded as a request attribute by each controller
        // as soon as it's parsed from the request body.
        Object ref = req.getAttribute("txReference");
        String message = (ref != null)
                ? "An unexpected error occurred and your payment could not be completed. Please try again, or contact support quoting reference " + ref + " if the problem continues."
                : "An unexpected error occurred and your payment could not be completed. Please try again, or contact support if the problem continues.";

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("INTERNAL_ERROR", message, requestId(req)));
    }
}
