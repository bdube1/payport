package zw.co.payport.gateway.support;

import com.sun.net.httpserver.HttpServer;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Serves a UN-format consolidated list for tests, the way the UN does: the list URL answers with a
 * redirect to a file host. Tests point payport.screening.un.url at it (http allowed for tests only).
 */
public final class UnListStub {

    public static final HttpServer SERVER;
    public static final AtomicReference<byte[]> BODY = new AtomicReference<>();
    public static final AtomicInteger STATUS = new AtomicInteger(200);

    static {
        try (InputStream in = UnListStub.class.getResourceAsStream("/screening/un-consolidated-fixture.xml")) {
            BODY.set(in.readAllBytes());
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SERVER.createContext("/resources/xml/en/consolidated.xml", ex -> {
                ex.getResponseHeaders().add("Location", "/blob/consolidatedLegacyByPRN.xml?sig=test");
                ex.sendResponseHeaders(302, -1);
                ex.close();
            });
            SERVER.createContext("/blob/consolidatedLegacyByPRN.xml", ex -> {
                int s = STATUS.get();
                byte[] b = BODY.get();
                ex.getResponseHeaders().add("Content-Type", "application/xml");
                ex.sendResponseHeaders(s, s == 200 ? b.length : -1);
                if (s == 200) ex.getResponseBody().write(b);
                ex.close();
            });
            SERVER.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private UnListStub() {}

    public static String url() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/resources/xml/en/consolidated.xml";
    }

    public static void register(DynamicPropertyRegistry r) {
        r.add("payport.screening.un.url", UnListStub::url);
        r.add("payport.screening.un.allowed-hosts", () -> "127.0.0.1");
        r.add("payport.screening.un.allow-http-for-tests", () -> "true");
        r.add("payport.screening.un.enabled", () -> "false");   // refreshed explicitly by the test
    }
}
