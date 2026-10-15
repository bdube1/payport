package zw.co.payport.gateway.psp.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import zw.co.payport.gateway.model.response.ApiResponse;

/** Small helpers so PSP controllers stay one-liners and use the same envelope as the rest of the platform. */
public final class Api {

    private Api() {}

    public static <T> ResponseEntity<ApiResponse<T>> ok(T data, HttpServletRequest req) {
        return ResponseEntity.ok(ApiResponse.success(data, PspExceptionHandler.requestId(req)));
    }

    public static <T> ResponseEntity<ApiResponse<T>> created(T data, HttpServletRequest req) {
        return ResponseEntity.status(201).body(ApiResponse.success(data, PspExceptionHandler.requestId(req)));
    }
}
