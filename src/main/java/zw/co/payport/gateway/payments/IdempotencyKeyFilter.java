package zw.co.payport.gateway.payments;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Idempotency keys, as in Stripe's API: a POST that carries an {@code Idempotency-Key}
 * header is performed once; repeating it with the same key returns the stored original
 * response (with header {@code Idempotent-Replayed: true}) instead of acting again.
 *
 *  - The key is scoped to the caller (Authorization / X-API-Key), so two clients cannot
 *    collide or read each other's responses.
 *  - The same key with a different request body is refused (422): a key names one request.
 *  - While the first request is still running, a repeat gets 409 (retry later).
 *  - Server errors (5xx) are not stored, so the client may retry them; the payment flow
 *    itself is still protected by the payment's own state (an attempt in flight cannot be
 *    started twice).
 *  - Keys are kept for 24 hours.
 *
 * Idempotency is not reconciliation: it stops Payport acting twice on a retried request,
 * not a provider acting twice. Provider-side duplicates are handled by attempt references
 * and statement reconciliation.
 */
@Slf4j
@Component
public class IdempotencyKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    private static final int MAX_BODY = 1_000_000;

    private final JdbcTemplate jdbc;

    public IdempotencyKeyFilter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String key = request.getHeader(HEADER);
        return key == null || key.isBlank() || !"POST".equalsIgnoreCase(request.getMethod())
                || !request.getRequestURI().startsWith("/api/v1/") || request.getRequestURI().startsWith("/api/v1/callbacks/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER).trim();
        if (key.length() > 255) {
            response.sendError(400, "Idempotency-Key longer than 255 characters");
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            response.sendError(413, "Request too large for an idempotent request");
            return;
        }
        String scope = sha256((nz(request.getHeader("Authorization")) + "|" + nz(request.getHeader("X-API-Key"))).getBytes(StandardCharsets.UTF_8));
        String requestHash = sha256((request.getMethod() + " " + request.getRequestURI() + "?" + nz(request.getQueryString()) + "\n")
                .getBytes(StandardCharsets.UTF_8), body);
        LocalDateTime now = LocalDateTime.now();

        jdbc.update("DELETE FROM idempotency_keys WHERE expires_at < ? LIMIT 100", now);
        try {
            jdbc.update("INSERT INTO idempotency_keys (scope_hash, idem_key, method, path, request_sha256, status, created_at, expires_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS', ?, ?)",
                    scope, key, request.getMethod(), truncate(request.getRequestURI(), 500), requestHash, now, now.plusHours(24));
        } catch (DuplicateKeyException e) {
            replay(scope, key, requestHash, response);
            return;
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        boolean stored = false;
        try {
            chain.doFilter(new CachedBodyRequest(request, body), wrapped);
            int status = wrapped.getStatus();
            if (status < 500) {
                jdbc.update("UPDATE idempotency_keys SET status = 'COMPLETED', response_status = ?, response_type = ?, response_body = ?, "
                                + "completed_at = ? WHERE scope_hash = ? AND idem_key = ?",
                        status, wrapped.getContentType(), new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8),
                        LocalDateTime.now(), scope, key);
                stored = true;
            }
        } finally {
            if (!stored) {
                jdbc.update("DELETE FROM idempotency_keys WHERE scope_hash = ? AND idem_key = ? AND status = 'IN_PROGRESS'", scope, key);
            }
            wrapped.copyBodyToResponse();
        }
    }

    private void replay(String scope, String key, String requestHash, HttpServletResponse response) throws IOException {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, request_sha256, response_status, response_type, response_body FROM idempotency_keys "
                        + "WHERE scope_hash = ? AND idem_key = ?", scope, key);
        if (rows.isEmpty()) { // expired or released between our insert attempt and now
            writeError(response, 409, "IDEMPOTENCY_RETRY", "Please retry this request.");
            return;
        }
        Map<String, Object> row = rows.get(0);
        if (!requestHash.equals(row.get("request_sha256"))) {
            writeError(response, 422, "IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was used for a different request.");
            return;
        }
        if (!"COMPLETED".equals(row.get("status"))) {
            writeError(response, 409, "IDEMPOTENCY_IN_PROGRESS", "The original request with this Idempotency-Key is still being processed.");
            return;
        }
        response.setStatus(((Number) row.get("response_status")).intValue());
        if (row.get("response_type") != null) response.setContentType((String) row.get("response_type"));
        zw.co.payport.gateway.observability.SecurityEvents.duplicateRequest();
        response.setHeader("Idempotent-Replayed", "true");
        String bodyText = (String) row.get("response_body");
        if (bodyText != null) response.getOutputStream().write(bodyText.getBytes(StandardCharsets.UTF_8));
        log.info("[IDEMPOTENCY] Replayed stored response for key {}", key);
    }

    private static void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getOutputStream().write(("{\"success\":false,\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) md.update(p);
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /** Lets the controller read a body the filter has already consumed. */
    static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                @Override public int read() { return in.read(); }
                @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
