package zw.co.payport.gateway.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Standard API response envelope used for ALL PayPort Gateway responses.
 *
 * Success: { "success": true, "data": {...}, "meta": {...} }
 * Error:   { "success": false, "error": {...}, "meta": {...} }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yy HH:mm:ss");

    private boolean success;
    private T data;
    private ErrorDetail error;
    private Meta meta;

    public static <T> ApiResponse<T> success(T data, String requestId) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .meta(Meta.builder()
                        .requestId(requestId)
                        .timestamp(LocalDateTime.now().format(FORMATTER))
                        .build())
                .build();
    }

    public static <T> ApiResponse<T> error(String code, String message, String requestId) {
        return ApiResponse.<T>builder()
                .success(false)
                .error(ErrorDetail.builder()
                        .code(code)
                        .message(message)
                        .build())
                .meta(Meta.builder()
                        .requestId(requestId)
                        .timestamp(LocalDateTime.now().format(FORMATTER))
                        .build())
                .build();
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorDetail {
        private String code;
        private String message;
        private Object details;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Meta {
        private String requestId;
        private String timestamp; // Now stored as formatted string
    }
}