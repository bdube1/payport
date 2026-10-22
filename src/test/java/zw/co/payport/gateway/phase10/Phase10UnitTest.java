package zw.co.payport.gateway.phase10;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.client.RestTemplate;
import zw.co.payport.gateway.markets.MarketRegistry;
import zw.co.payport.gateway.platform.DeploymentEnvironment;
import zw.co.payport.gateway.platform.SecretBox;
import zw.co.payport.gateway.service.adaptor.PaymentProviderAdaptor;
import zw.co.payport.gateway.service.adaptor.airtel.AirtelMoneyAdaptor;
import zw.co.payport.gateway.service.adaptor.econet.XmlRpcResponseGuard;
import zw.co.payport.gateway.service.adaptor.mtn.MtnMomoAdaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 10 (verification) unit evidence:
 *  - R76: the XML-RPC response guard refuses the CVE-2019-17570 trigger and bounds CVE-2016-5004;
 *  - MTN MoMo and Airtel Money against contract fixtures written from the providers' published API
 *    descriptions (a local stub, NOT the providers' sandboxes: no credentials are available here);
 *  - production configuration defaults.
 */
class Phase10UnitTest {

    // ─── R76: XML-RPC response guard ─────────────────────────────────────────

    static final String OK_RESPONSE = "<?xml version=\"1.0\"?><methodResponse><params><param><value><struct>"
            + "<member><name>StatusCode</name><value><string>0</string></value></member></struct></value></param></params></methodResponse>";
    static final String FAULT = "<?xml version=\"1.0\"?><methodResponse><fault><value><struct><member><name>faultCode</name><value><int>4</int></value></member>"
            + "<member><name>faultString</name><value><string>Too many parameters</string></value></member></struct></value></fault></methodResponse>";

    @Test
    void xmlRpcGuard_passesGenuineResponsesAndFaults() throws Exception {
        assertArrayEquals(OK_RESPONSE.getBytes(StandardCharsets.UTF_8), XmlRpcResponseGuard.read(stream(OK_RESPONSE)));
        assertArrayEquals(FAULT.getBytes(StandardCharsets.UTF_8), XmlRpcResponseGuard.read(stream(FAULT)));
    }

    @Test
    void xmlRpcGuard_refusesTheDeserialisationTrigger_cve_2019_17570() {
        // The exploit shape: a fault whose faultCause carries a base64 Java serialisation stream.
        String gadget = Base64.getEncoder().encodeToString(new byte[]{(byte) 0xAC, (byte) 0xED, 0x00, 0x05, 0x73, 0x72});
        String exploit = FAULT.replace("</struct></value></fault>", "<member><name>faultCause</name><value><base64>" + gadget + "</base64></value></member></struct></value></fault>");
        assertTrue(gadget.startsWith("rO0AB"));
        assertThrows(XmlRpcResponseGuard.RefusedResponse.class, () -> XmlRpcResponseGuard.read(stream(exploit)));
        assertThrows(XmlRpcResponseGuard.RefusedResponse.class, () -> XmlRpcResponseGuard.read(stream(OK_RESPONSE.replace("<string>0</string>",
                "<ex:serializable xmlns:ex=\"http://ws.apache.org/xmlrpc/namespaces/extensions\">" + gadget + "</ex:serializable>"))));
        assertThrows(XmlRpcResponseGuard.RefusedResponse.class, () -> XmlRpcResponseGuard.read(stream(OK_RESPONSE.replace("<string>0</string>", "<base64>" + gadget + "</base64>"))),
                "a serialisation stream anywhere is refused");
        assertThrows(XmlRpcResponseGuard.RefusedResponse.class, () -> XmlRpcResponseGuard.read(stream(
                "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>" + OK_RESPONSE.substring(21))));
    }

    @Test
    void xmlRpcGuard_boundsADecompressionBomb_cve_2016_5004() throws Exception {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream z = new GZIPOutputStream(gz)) {
            byte[] zeros = new byte[1 << 20];
            for (int i = 0; i < 64; i++) z.write(zeros);      // 64 MiB expands from ~64 KiB
        }
        assertTrue(gz.size() < 200_000);
        CountingStream counted = new CountingStream(new GZIPInputStream(new ByteArrayInputStream(gz.toByteArray())));
        assertThrows(XmlRpcResponseGuard.RefusedResponse.class, () -> XmlRpcResponseGuard.read(counted));
        assertTrue(counted.read < XmlRpcResponseGuard.MAX_BYTES + 16_384, "stops reading at the cap: read " + counted.read + " bytes");
    }

    static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    static class CountingStream extends InputStream {
        final InputStream in;
        long read;
        CountingStream(InputStream in) { this.in = in; }
        @Override public int read() throws java.io.IOException { int b = in.read(); if (b >= 0) read++; return b; }
        @Override public int read(byte[] b, int off, int len) throws java.io.IOException { int n = in.read(b, off, len); if (n > 0) read += n; return n; }
    }

    // ─── MTN MoMo and Airtel Money contract fixtures ────────────────────────

    record Seen(String method, String path, Map<String, List<String>> headers, String body) {}

    HttpServer server;
    final List<Seen> seen = new CopyOnWriteArrayList<>();
    final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    interface Handler { void handle(HttpExchange x, String body) throws Exception; }

    String start(Handler h) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", x -> {
            try {
                String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                seen.add(new Seen(x.getRequestMethod(), x.getRequestURI().getPath(), x.getRequestHeaders(), body));
                h.handle(x, body);
            } catch (Exception e) {
                x.sendResponseHeaders(599, -1);
            } finally {
                x.close();
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    static void reply(HttpExchange x, int status, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) x.getResponseBody().write(b);
    }

    @Test
    void mtnMomo_requestToPayAndStatus_matchTheCollectionApi() throws Exception {
        String attempt = "PP-WEB-20261022-000101";
        String refId = MtnMomoAdaptor.referenceId(attempt);
        String base = start((x, body) -> {
            String p = x.getRequestURI().getPath();
            if (p.equals("/collection/token/")) reply(x, 200, "{\"access_token\":\"tok-1\",\"token_type\":\"access_token\",\"expires_in\":3600}");
            else if (p.equals("/collection/v1_0/requesttopay")) reply(x, 202, "");
            else if (p.equals("/collection/v1_0/requesttopay/" + refId)) reply(x, 200, "{\"amount\":\"10.5\",\"currency\":\"ZMW\",\"financialTransactionId\":\"FT-77\",\"externalId\":\"" + attempt + "\",\"status\":\"SUCCESSFUL\"}");
            else reply(x, 404, "{}");
        });
        MtnMomoAdaptor mtn = new MtnMomoAdaptor(new RestTemplate(), base, "sub-key", "api-user", "api-key", "mtnzambia", "https://api.example/cb");
        PaymentProviderAdaptor.PaymentSubmitResult r = mtn.submitPayment(PaymentProviderAdaptor.PaymentRequest.builder()
                .payportReference(attempt).amountCents(1050).currency("ZMW").customerPhone("+260961234567").description("Airtime").build());
        assertTrue(r.isSubmitted(), r.getMessage());
        assertEquals(refId, r.getProviderReference());

        Seen token = seen.get(0), pay = seen.get(1);
        assertEquals("POST", token.method());
        assertEquals("Basic " + Base64.getEncoder().encodeToString("api-user:api-key".getBytes(StandardCharsets.UTF_8)), token.headers().get("Authorization").get(0));
        assertEquals("sub-key", token.headers().get("Ocp-apim-subscription-key").get(0));
        assertEquals("Bearer tok-1", pay.headers().get("Authorization").get(0));
        assertEquals(refId, pay.headers().get("X-reference-id").get(0));
        assertEquals("mtnzambia", pay.headers().get("X-target-environment").get(0));
        assertEquals("https://api.example/cb", pay.headers().get("X-callback-url").get(0));
        JsonNode b = json.readTree(pay.body());
        assertEquals("10.5", b.path("amount").asText());
        assertEquals("ZMW", b.path("currency").asText());
        assertEquals(attempt, b.path("externalId").asText());
        assertEquals("MSISDN", b.path("payer").path("partyIdType").asText());
        assertEquals("260961234567", b.path("payer").path("partyId").asText());

        PaymentProviderAdaptor.PaymentStatusResult s = mtn.queryStatus(attempt);
        assertEquals("SUCCESS", s.getStatus());
        assertEquals(1050, s.getAmountCents());
        assertEquals(1, seen.stream().filter(x -> x.path().equals("/collection/token/")).count(), "the token is reused while valid");
    }

    @Test
    void mtnMomo_serverErrorIsUnknown_clientErrorIsDefinite() throws Exception {
        String base = start((x, body) -> {
            String p = x.getRequestURI().getPath();
            if (p.equals("/collection/token/")) reply(x, 200, "{\"access_token\":\"t\",\"expires_in\":3600}");
            else if (body.contains("PP-WEB-20261022-000500")) reply(x, 500, "{\"code\":\"INTERNAL_PROCESSING_ERROR\"}");
            else reply(x, 400, "{\"code\":\"PAYER_NOT_FOUND\",\"message\":\"Payer not found\"}");
        });
        MtnMomoAdaptor mtn = new MtnMomoAdaptor(new RestTemplate(), base, "k", "u", "p", "sandbox", "");
        var unknown = mtn.submitPayment(PaymentProviderAdaptor.PaymentRequest.builder().payportReference("PP-WEB-20261022-000500").amountCents(100).currency("ZMW").customerPhone("+260961234567").build());
        assertFalse(unknown.isSubmitted());
        assertTrue(unknown.isOutcomeUnknown(), "a 5xx may have charged the customer: resolved by status query, never retried blindly");
        var refused = mtn.submitPayment(PaymentProviderAdaptor.PaymentRequest.builder().payportReference("PP-WEB-20261022-000400").amountCents(100).currency("ZMW").customerPhone("+260961234567").build());
        assertFalse(refused.isSubmitted());
        assertFalse(refused.isOutcomeUnknown());
        assertTrue(refused.getMessage().contains("Payer not found"), refused.getMessage());
    }

    @Test
    void airtelMoney_collectionAndStatus_matchTheOpenApi() throws Exception {
        String attempt = "PP-WEB-20261022-000202";
        String base = start((x, body) -> {
            String p = x.getRequestURI().getPath();
            if (p.equals("/auth/oauth2/token")) reply(x, 200, "{\"access_token\":\"atok\",\"expires_in\":180,\"token_type\":\"bearer\"}");
            else if (p.equals("/merchant/v1/payments/")) reply(x, 200, "{\"data\":{\"transaction\":{\"id\":\"" + attempt + "\",\"status\":\"Success.\"}},\"status\":{\"code\":\"200\",\"message\":\"SUCCESS\",\"result_code\":\"ESB000010\",\"success\":true}}");
            else if (p.equals("/standard/v1/payments/" + attempt)) reply(x, 200, "{\"data\":{\"transaction\":{\"airtel_money_id\":\"AM-9\",\"id\":\"" + attempt + "\",\"message\":\"Paid\",\"status\":\"TS\"}},\"status\":{\"success\":true}}");
            else reply(x, 404, "{}");
        });
        MarketRegistry markets = Mockito.mock(MarketRegistry.class);
        MarketRegistry.Market zm = new MarketRegistry.Market("ZM", "Zambia", "LIVE", null, "PAYPORT_ZM", "Africa/Lusaka", "260",
                Pattern.compile("9[5-7]\\d{7}"), List.of(), "H", Map.of(), "FIC", List.of(), null, 1);
        Mockito.when(markets.requireForCurrency("ZMW")).thenReturn(zm);
        AirtelMoneyAdaptor airtel = new AirtelMoneyAdaptor(new RestTemplate(), markets, base, "cid", "csecret", "ZMW");
        var r = airtel.submitPayment(PaymentProviderAdaptor.PaymentRequest.builder().payportReference(attempt).amountCents(500).currency("ZMW")
                .customerPhone("+260971234567").description("Bill").build());
        assertTrue(r.isSubmitted(), r.getMessage());
        JsonNode token = json.readTree(seen.get(0).body());
        assertEquals("client_credentials", token.path("grant_type").asText());
        assertEquals("cid", token.path("client_id").asText());
        Seen pay = seen.get(1);
        assertEquals("ZM", pay.headers().get("X-country").get(0));
        assertEquals("ZMW", pay.headers().get("X-currency").get(0));
        assertEquals("Bearer atok", pay.headers().get("Authorization").get(0));
        JsonNode b = json.readTree(pay.body());
        assertEquals("971234567", b.path("subscriber").path("msisdn").asText(), "national number, without the country code");
        assertEquals(attempt, b.path("transaction").path("id").asText());
        assertEquals(5.0, b.path("transaction").path("amount").asDouble());
        var s = airtel.queryStatus(attempt);
        assertEquals("SUCCESS", s.getStatus());
        assertEquals("AM-9", s.getProviderReference());
    }

    // ─── production configuration ───────────────────────────────────────────

    @Test
    void productionDefaults_failSafe() throws Exception {
        assertTrue(DeploymentEnvironment.isProduction(null), "no setting = production rules");
        assertTrue(DeploymentEnvironment.isProduction(""));
        assertFalse(DeploymentEnvironment.isProduction("staging"));
        assertThrows(IllegalStateException.class, () -> new SecretBox("", "jwt", true), "no data key in production");
        assertThrows(IllegalStateException.class, () -> new SecretBox(Base64.getEncoder().encodeToString(new byte[32]), "jwt", true), "a key without entropy");

        Properties p = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
            p.load(in);
        }
        // No secret, password, token or key has a built-in default value: it must come from the environment.
        Pattern secretKey = Pattern.compile("(?i).*(secret|password|access-token|api-key|private-key|data-key|subscription-key|client-secret|app-secret|verify-token).*");
        Pattern withDefault = Pattern.compile("\\$\\{[A-Z0-9_]+:([^}$]+)}");
        List<String> problems = new ArrayList<>();
        for (String k : p.stringPropertyNames()) {
            if (!secretKey.matcher(k).matches()) continue;
            Matcher m = withDefault.matcher(p.getProperty(k));
            if (m.find() && !m.group(1).isBlank()) problems.add(k + " has a default");
        }
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals("${PAYPORT_ENV:production}", p.getProperty("payport.environment"));
        assertEquals("${SETTLEMENT_MODE:PARALLEL}", p.getProperty("payport.settlement.mode"), "settlement stays in parallel (comparison) mode");
        assertEquals("${SETTLEMENT_AUTOMATION_ENABLED:false}", p.getProperty("payport.settlement.automation.enabled"), "automated payouts stay off");
        assertEquals("${PAYPORT_METRICS_TOKEN:}", p.getProperty("payport.metrics.token"));
        assertEquals("when-authorized", p.getProperty("management.endpoint.health.show-details"));
        String exposed = p.getProperty("management.endpoints.web.exposure.include");
        for (String dangerous : List.of("env", "beans", "heapdump", "threaddump", "configprops", "loggers", "shutdown", "*")) {
            assertFalse(List.of(exposed.split(",")).contains(dangerous), "actuator exposes " + dangerous);
        }
    }
}
