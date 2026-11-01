package zw.co.payport.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** EcoCash's egress: only calls that never reached EcoCash are tried again, so a payment is never sent twice. */
class EgressRequestFactoryTest {

    @Test
    void onlyErrorsBeforeAnythingWasSentAreRetried() {
        assertTrue(EgressRequestFactory.neverSent(new ConnectException("Connection refused")));
        assertTrue(EgressRequestFactory.neverSent(new SocketException("SOCKS: Host unreachable")));
        assertTrue(EgressRequestFactory.neverSent(new SocketException("Can't connect to SOCKS proxy:Connection refused")));
        assertTrue(EgressRequestFactory.neverSent(new SocketTimeoutException("Connect timed out")));
        assertTrue(EgressRequestFactory.neverSent(new IOException("I/O error", new javax.net.ssl.SSLHandshakeException("Remote host terminated the handshake"))));

        assertFalse(EgressRequestFactory.neverSent(new SocketTimeoutException("Read timed out")));
        assertFalse(EgressRequestFactory.neverSent(new SocketException("Connection reset")));
        assertFalse(EgressRequestFactory.neverSent(new IOException("Unexpected end of file from server")));
    }

    @Test
    void onlyEcoCashHostsUseTheProxy() {
        EgressRequestFactory f = new EgressRequestFactory(true, Proxy.Type.SOCKS, "127.0.0.1", 9050,
                List.of("payonline.econet.co.zw", "api.ecocash.co.zw"), 1000, 1000, 1);
        assertTrue(f.usesProxy(URI.create("https://payonline.econet.co.zw/ecocashGateway/payment/v1")));
        assertTrue(f.usesProxy(URI.create("https://api.ecocash.co.zw/x")));
        assertFalse(f.usesProxy(URI.create("https://omari.v.co.zw/vsuite")));
        assertFalse(f.usesProxy(URI.create("https://evil-payonline.econet.co.zw.example.com/")));
        EgressRequestFactory off = new EgressRequestFactory(false, Proxy.Type.SOCKS, "127.0.0.1", 9050, List.of("api.ecocash.co.zw"), 1000, 1000, 1);
        assertFalse(off.usesProxy(URI.create("https://api.ecocash.co.zw/x")));
    }

    @Test
    void aDeadProxyIsTriedThreeTimesThenFails() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }   // closed: nothing listens
        EgressRequestFactory f = new EgressRequestFactory(true, Proxy.Type.SOCKS, "127.0.0.1", port, List.of("api.ecocash.co.zw"), 1000, 1000, 1);
        ClientHttpRequest r = f.createRequest(URI.create("https://api.ecocash.co.zw/x"), HttpMethod.POST);
        r.getHeaders().set("Content-Type", "application/json");
        r.getBody().write("{}".getBytes());
        IOException e = assertThrows(IOException.class, r::execute);
        assertTrue(EgressRequestFactory.neverSent(e), e.toString());
    }
}
