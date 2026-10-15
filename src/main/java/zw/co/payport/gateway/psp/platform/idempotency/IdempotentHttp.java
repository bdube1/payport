package zw.co.payport.gateway.psp.platform.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.model.response.ApiResponse;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Controller-side wrapper: runs an operation under the caller's
 * Idempotency-Key and returns the (possibly replayed) JSON response.
 *
 * <pre>
 * return idempotentHttp.run("merchant:" + id, idemKey, req, bodyJson, 201,
 *         () -> service.createRefund(...), Refund::getRefundRef);
 * </pre>
 */
@Component
public class IdempotentHttp {

    public static final String HEADER = "Idempotency-Key";

    private final IdempotencyService service;
    private final ObjectMapper objectMapper;

    public IdempotentHttp(IdempotencyService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public <T> ResponseEntity<String> run(String scope, String idempotencyKey, HttpServletRequest req, String rawBody,
                                          int successStatus, Supplier<T> operation, Function<T, String> resourceRef) {
        String requestId = req.getHeader("X-Request-ID") != null ? req.getHeader("X-Request-ID") : UUID.randomUUID().toString();
        String hash = IdempotencyService.hashRequest(req.getMethod(), req.getRequestURI(), rawBody);
        IdempotencyService.StoredResponse resp = service.execute(scope, idempotencyKey, hash, () -> {
            T result = operation.get();
            try {
                String body = objectMapper.writeValueAsString(ApiResponse.success(result, requestId));
                return new IdempotencyService.StoredResponse(successStatus, body, resourceRef != null ? resourceRef.apply(result) : null);
            } catch (Exception e) {
                throw new IllegalStateException("Could not serialise response", e);
            }
        });
        return ResponseEntity.status(resp.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(resp.body());
    }
}
