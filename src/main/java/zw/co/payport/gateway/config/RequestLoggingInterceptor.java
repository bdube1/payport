package zw.co.payport.gateway.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Collections;
import java.util.UUID;

/**
 * Request Logging Interceptor
 *
 * Logs every incoming request and outgoing response.
 * Masks sensitive headers: Authorization, PINs, passwords, secrets, keys, tokens, cookies and signatures.
 *
 * Also injects X-Request-ID on every request so we can trace requests
 * through the system.
 */
@Slf4j
@Component
public class RequestLoggingInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Inject or propagate X-Request-ID
        String requestId = request.getHeader("X-Request-ID");
        if (requestId == null || requestId.isBlank()) {
            requestId = "PP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        }
        response.setHeader("X-Request-ID", requestId);

        String headers = Collections.list(request.getHeaderNames()).stream()
                .map(h -> h + "=" + maskHeader(h, request.getHeader(h)))
                .reduce("", (a, b) -> a + " " + b);

        log.info("[REQUEST] {} {} | ID={} | IP={} | Headers:{}",
                request.getMethod(), request.getRequestURI(),
                requestId, request.getRemoteAddr(), headers);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        String requestId = response.getHeader("X-Request-ID");
        log.info("[RESPONSE] {} {} | ID={} | Status={}",
                request.getMethod(), request.getRequestURI(), requestId, response.getStatus());

        if (ex != null) {
            log.error("[RESPONSE] Exception on {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        }
    }

    private String maskHeader(String headerName, String value) {
        if (value == null) return "null";
        String lower = headerName.toLowerCase();
        if (lower.equals("authorization"))         return "****";
        if (lower.equals("x-merchant-pin"))        return "****";
        if (lower.equals("x-access-password"))     return "****";
        if (lower.equals("merchantpin"))           return "****";
        if (lower.contains("password"))            return "****";
        if (lower.contains("secret"))              return "****";
        if (lower.contains("apikey") || lower.contains("api-key")) return "****";
        // Service keys (X-PayPort-Commerce-Key and the like), tokens, cookies, signatures and PINs never reach the log.
        if (lower.contains("key") && !lower.equals("idempotency-key")) return "****";
        if (lower.contains("token") || lower.contains("cookie") || lower.contains("signature") || lower.contains("pin")) return "****";
        return value;
    }
}
