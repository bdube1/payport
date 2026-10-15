package zw.co.payport.gateway.psp.channel;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import zw.co.payport.gateway.psp.ledger.domain.ChainHasher;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authenticates partner API calls (/api/v1/partner/**) and POS terminal calls
 * (/api/v1/psp/channels/pos/**) by HMAC request signature ({@link RequestSigning}),
 * with replay protection (timestamp window + single-use nonce), optional IP
 * allow-list, optional mTLS certificate pinning, and a per-client rate limit.
 *
 * mTLS: terminate TLS at your reverse proxy with client-certificate
 * verification and forward the URL-encoded PEM in X-Client-Cert. That header is
 * only trusted from the proxy addresses in payport.psp.edge.trusted-proxies.
 */
@Component
public class SignedRequestFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SignedRequestFilter.class);
    public static final String PARTNER_PREFIX = "/api/v1/partner/";
    public static final String POS_PREFIX = "/api/v1/psp/channels/pos/";

    public record PartnerPrincipal(String keyId, Long merchantId, Set<String> scopes, String kind) {}

    private final PartnerClientRepository partners;
    private final TerminalRepository terminals;
    private final RequestNonceRepository nonces;
    private final TransactionTemplate newTx;
    private final Map<String, long[]> rate = new ConcurrentHashMap<>(); // key -> [windowStartEpochMinute, count]

    @Value("${payport.psp.edge.trusted-proxies:127.0.0.1}")
    private String trustedProxies = "127.0.0.1";

    public SignedRequestFilter(PartnerClientRepository partners, TerminalRepository terminals, RequestNonceRepository nonces,
                               PlatformTransactionManager txManager) {
        this.partners = partners;
        this.terminals = terminals;
        this.nonces = nonces;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return p == null || !(p.startsWith(PARTNER_PREFIX) || p.startsWith(POS_PREFIX));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CachedBodyRequest req;
        try {
            req = new CachedBodyRequest(request);
        } catch (IOException tooBig) {
            reject(response, 413, "BODY_TOO_LARGE", "Request body too large");
            return;
        }
        String keyId = req.getHeader("X-PayPort-Key-Id");
        String ts = req.getHeader("X-PayPort-Timestamp");
        String nonce = req.getHeader("X-PayPort-Nonce");
        String sig = req.getHeader("X-PayPort-Signature");
        if (keyId == null || ts == null || nonce == null || sig == null) {
            reject(response, 401, "SIGNATURE_REQUIRED", "Signed request headers are required");
            return;
        }
        if (nonce.length() < 16 || nonce.length() > 80) {
            reject(response, 401, "BAD_NONCE", "Nonce must be 16-80 characters");
            return;
        }
        long timestamp;
        try { timestamp = Long.parseLong(ts); } catch (NumberFormatException e) { reject(response, 401, "BAD_TIMESTAMP", "Timestamp must be unix seconds"); return; }
        if (!RequestSigning.timestampFresh(timestamp, System.currentTimeMillis() / 1000)) {
            reject(response, 401, "STALE_REQUEST", "Timestamp outside the 5 minute window");
            return;
        }

        boolean pos = req.getRequestURI().startsWith(POS_PREFIX);
        String secret;
        PartnerPrincipal principal;
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        if (pos) {
            Optional<Terminal> t = terminals.findByTerminalId(keyId);
            if (t.isEmpty() || !"ACTIVE".equals(t.get().getStatus())) { reject(response, 401, "UNKNOWN_TERMINAL", "Terminal not active"); return; }
            secret = t.get().getSecret();
            principal = new PartnerPrincipal(keyId, t.get().getMerchantId(), Set.of("pos"), "TERMINAL");
            authorities.add(new SimpleGrantedAuthority("ROLE_TERMINAL"));
        } else {
            Optional<PartnerClient> p = partners.findByKeyId(keyId);
            if (p.isEmpty() || !"ACTIVE".equals(p.get().getStatus())) { reject(response, 401, "UNKNOWN_CLIENT", "Partner key not active"); return; }
            PartnerClient c = p.get();
            String ip = clientIp(req);
            if (c.getAllowedIps() != null && !c.getAllowedIps().isBlank()
                    && Arrays.stream(c.getAllowedIps().split(",")).map(String::trim).noneMatch(ip::equals)) {
                reject(response, 403, "IP_NOT_ALLOWED", "Source address not allowed for this partner");
                return;
            }
            if (c.getMtlsCertSha256() != null && !c.getMtlsCertSha256().isBlank()) {
                String presented = clientCertSha256(req);
                if (presented == null || !presented.equalsIgnoreCase(c.getMtlsCertSha256())) {
                    reject(response, 401, "MTLS_REQUIRED", "Client certificate missing or not the pinned certificate");
                    return;
                }
            }
            if (!allowRate(keyId, c.getRateLimitPerMinute())) {
                reject(response, 429, "RATE_LIMITED", "Too many requests");
                return;
            }
            secret = c.getSecret();
            Set<String> scopes = new HashSet<>(Arrays.asList(c.getScopes().split(",")));
            principal = new PartnerPrincipal(keyId, c.getMerchantId(), scopes, "PARTNER");
            authorities.add(new SimpleGrantedAuthority("ROLE_PARTNER"));
            for (String s : scopes) authorities.add(new SimpleGrantedAuthority("SCOPE_" + s.trim()));
        }

        String canonical = RequestSigning.canonical(req.getMethod(), req.getRequestURI(), req.getQueryString(), timestamp, nonce, req.body());
        if (!RequestSigning.verify(secret, canonical, sig)) {
            log.warn("[EDGE] Bad signature from {} on {}", keyId, req.getRequestURI());
            reject(response, 401, "BAD_SIGNATURE", "Signature does not match");
            return;
        }
        if (!useNonce(keyId, nonce)) {
            reject(response, 401, "REPLAYED_REQUEST", "Nonce already used");
            return;
        }
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
        chain.doFilter(req, response);
    }

    private boolean useNonce(String keyId, String nonce) {
        try {
            newTx.executeWithoutResult(s -> {
                RequestNonce n = new RequestNonce();
                n.setClientId(keyId);
                n.setNonce(nonce);
                nonces.saveAndFlush(n);
            });
            return true;
        } catch (DataIntegrityViolationException dup) {
            return false;
        }
    }

    private boolean allowRate(String key, int perMinute) {
        long minute = System.currentTimeMillis() / 60_000;
        long[] w = rate.compute(key, (k, v) -> v == null || v[0] != minute ? new long[]{minute, 1} : new long[]{minute, v[1] + 1});
        return w[1] <= Math.max(1, perMinute);
    }

    private String clientIp(HttpServletRequest req) {
        String remote = req.getRemoteAddr();
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && isTrustedProxy(remote)) return fwd.split(",")[0].trim();
        return remote;
    }

    private boolean isTrustedProxy(String ip) {
        return Arrays.stream(trustedProxies.split(",")).map(String::trim).anyMatch(p -> p.equals(ip));
    }

    private String clientCertSha256(HttpServletRequest req) {
        if (!isTrustedProxy(req.getRemoteAddr())) return null;
        String pem = req.getHeader("X-Client-Cert");
        if (pem == null || pem.isBlank()) return null;
        try {
            String decoded = URLDecoder.decode(pem, StandardCharsets.UTF_8);
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(decoded.getBytes(StandardCharsets.US_ASCII)));
            cert.checkValidity();
            return ChainHasher.sha256(cert.getEncoded());
        } catch (Exception e) {
            return null;
        }
    }

    private static void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}");
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void purgeNonces() {
        newTx.executeWithoutResult(s -> nonces.purgeBefore(LocalDateTime.now().minusMinutes(15)));
        long minute = System.currentTimeMillis() / 60_000;
        rate.entrySet().removeIf(e -> e.getValue()[0] < minute - 1);
    }
}
