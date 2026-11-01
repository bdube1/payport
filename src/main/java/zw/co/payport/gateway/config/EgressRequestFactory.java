package zw.co.payport.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Proxy;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Outbound HTTP for every provider. Hosts in the egress list (EcoCash, which only accepts Zimbabwean addresses) go
 * through the configured proxy: the local Tor SOCKS port by default, or any SOCKS or HTTP proxy (for example an SSH
 * tunnel to a Zimbabwean server) set with ECOCASH_EGRESS_*. Everything else goes direct.
 *
 * A proxied call that fails before anything reached the provider (the proxy refused, the route through it could not
 * be built, the TLS handshake was cut) is tried again on a fresh connection, up to {@link #ATTEMPTS} times. A call that
 * may have reached the provider (a read timeout, a reset after sending) is never repeated, so a payment is never sent
 * twice.
 */
@Slf4j
public class EgressRequestFactory implements ClientHttpRequestFactory {

    static final int ATTEMPTS = 3;

    private final SimpleClientHttpRequestFactory direct;
    private final SimpleClientHttpRequestFactory proxied;
    private final List<String> hosts;
    private final boolean enabled;
    private final long pauseMs;

    public EgressRequestFactory(boolean enabled, Proxy.Type type, String proxyHost, int proxyPort, List<String> hosts,
                                int connectTimeoutMs, int readTimeoutMs, long pauseMs) {
        this.enabled = enabled;
        this.hosts = hosts;
        this.pauseMs = pauseMs;
        this.direct = new SimpleClientHttpRequestFactory();
        this.direct.setConnectTimeout(connectTimeoutMs);
        this.direct.setReadTimeout(readTimeoutMs);
        this.direct.setProxy(Proxy.NO_PROXY);
        this.proxied = new SimpleClientHttpRequestFactory();
        this.proxied.setConnectTimeout(connectTimeoutMs);
        this.proxied.setReadTimeout(readTimeoutMs);
        this.proxied.setProxy(new Proxy(type, new InetSocketAddress(proxyHost, proxyPort)));
    }

    public boolean usesProxy(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return enabled && hosts.stream().anyMatch(h -> host.equals(h) || host.endsWith("." + h));
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
        if (!usesProxy(uri)) return direct.createRequest(uri, httpMethod);
        return new Retrying(uri, httpMethod);
    }

    /**
     * True only when the request certainly never reached the provider: the connection, the proxy's route or the TLS
     * handshake failed before any of the request was written.
     */
    public static boolean neverSent(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof UnknownHostException || t instanceof NoRouteToHostException) return true;
            if (t instanceof javax.net.ssl.SSLHandshakeException) return true;
            String m = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
            if (t instanceof SocketTimeoutException) return m.contains("connect");
            if (t instanceof SocketException && (m.startsWith("socks") || m.contains("socks server") || m.contains("can't connect to socks")
                    || m.contains("proxy") || m.contains("remote host terminated the handshake"))) return true;
        }
        return false;
    }

    /** Keeps the headers and body so the request can be sent again on a new connection. */
    private final class Retrying implements ClientHttpRequest {
        private final URI uri;
        private final HttpMethod method;
        private final HttpHeaders headers = new HttpHeaders();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final Map<String, Object> attributes = new LinkedHashMap<>();

        Retrying(URI uri, HttpMethod method) {
            this.uri = uri;
            this.method = method;
        }

        public HttpMethod getMethod() { return method; }

        public URI getURI() { return uri; }

        public HttpHeaders getHeaders() { return headers; }

        public Map<String, Object> getAttributes() { return attributes; }

        public OutputStream getBody() { return body; }

        public ClientHttpResponse execute() throws IOException {
            byte[] bytes = body.toByteArray();
            IOException last = null;
            for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                ClientHttpRequest r = proxied.createRequest(uri, method);
                headers.forEach((k, v) -> r.getHeaders().put(k, v));
                if (bytes.length > 0) r.getBody().write(bytes);
                try {
                    ClientHttpResponse res = r.execute();
                    if (attempt > 1) log.info("[EGRESS] {} reached on attempt {}", uri.getHost(), attempt);
                    return res;
                } catch (IOException e) {
                    last = e;
                    if (!neverSent(e) || attempt == ATTEMPTS) throw e;
                    log.warn("[EGRESS] {} not reached through the proxy (attempt {}): {}; trying a new connection", uri.getHost(), attempt, e.getMessage());
                    try {
                        Thread.sleep(pauseMs * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
            throw last;
        }
    }
}
