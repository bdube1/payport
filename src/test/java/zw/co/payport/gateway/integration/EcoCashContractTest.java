package zw.co.payport.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import zw.co.payport.gateway.service.adaptor.PaymentProviderAdaptor;
import zw.co.payport.gateway.service.adaptor.ecocash.EcoCashAdaptor;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EcoCash Instant Payments contract against spec-shaped fixtures (no EcoCash sandbox access):
 * the charge request Payport sends, and how each answer is classified (sent / declined / unknown).
 */
class EcoCashContractTest {

    HttpServer server;
    final List<String[]> requests = new CopyOnWriteArrayList<>();   // method, path, auth, body
    volatile int status = 200;
    volatile String answer = "{}";
    EcoCashAdaptor ecocash;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            requests.add(new String[]{ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestHeaders().getFirst("Authorization"),
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)});
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        ecocash = new EcoCashAdaptor(new RestTemplate(), new ObjectMapper());
        Map<String, Object> f = Map.of("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/ecocashGateway/payment/v1",
                "notifyUrl", "https://api.payport.africa/api/v1/callbacks/ecocash", "merchantCode", "42467", "merchantPin", "1357",
                "merchantNumber", "771999999", "countryCode", "ZW", "terminalID", "TERM1", "location", "Harare",
                "superMerchantName", "Payport", "merchantName", "Payport");
        f.forEach((k, v) -> ReflectionTestUtils.setField(ecocash, k, v));
        ReflectionTestUtils.setField(ecocash, "username", "payport");
        ReflectionTestUtils.setField(ecocash, "password", "pw");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private PaymentProviderAdaptor.PaymentRequest charge(long cents, String currency) {
        return PaymentProviderAdaptor.PaymentRequest.builder().payportReference("PP-USD-20261016-000101").amountCents(cents)
                .currency(currency).customerPhone("+263 77 123 4567").description("Airtime").build();
    }

    @Test
    void chargeRequest_matchesTheSpec() throws Exception {
        answer = "{\"clientCorrelator\":\"PP-USD-20261016-000101\",\"transactionOperationStatus\":\"PENDING SUBSCRIBER VALIDATION\"}";
        PaymentProviderAdaptor.PaymentSubmitResult r = ecocash.submitPayment(charge(1999, "ZWG"));

        assertTrue(r.isSubmitted(), r.getMessage());
        String[] req = requests.get(0);
        assertEquals("POST", req[0]);
        assertEquals("/ecocashGateway/payment/v1/transactions/amount", req[1]);
        assertTrue(req[2].startsWith("Basic "));
        Map<?, ?> body = new ObjectMapper().readValue(req[3], Map.class);
        assertEquals("PP-USD-20261016-000101", body.get("clientCorrelator"), "the payment (attempt) reference is the correlator");
        assertEquals("771234567", body.get("endUserId"), "MSISDN normalised to 9 digits");
        assertEquals("MER", body.get("tranType"));
        Map<?, ?> charging = (Map<?, ?>) ((Map<?, ?>) body.get("paymentAmount")).get("charginginformation");
        assertEquals("19.99", charging.get("amount").toString(), "decimal amount, exact");
        assertEquals("ZWG", charging.get("currency"));
        assertEquals("https://api.payport.africa/api/v1/callbacks/ecocash", body.get("notifyUrl"));
    }

    @Test
    void immediateDecline_isDefinite_withEcoCashsOwnWords() throws Exception {
        answer = "{\"transactionOperationStatus\":\"FAILED\",\"text\":\"Subscriber is not registered for EcoCash\"}";
        PaymentProviderAdaptor.PaymentSubmitResult r = ecocash.submitPayment(charge(500, "USD"));
        assertFalse(r.isSubmitted());
        assertFalse(r.isOutcomeUnknown(), "a decline is definite: the customer is told now, not after the window");
        assertEquals("Subscriber is not registered for EcoCash", r.getMessage());
    }

    @Test
    void clientError_isDefinite_serverError_isUnknown() throws Exception {
        status = 400;
        answer = "{\"text\":\"Invalid request\"}";
        assertFalse(ecocash.submitPayment(charge(500, "USD")).isOutcomeUnknown(), "4xx: EcoCash refused it, no money moved");
        status = 502;
        PaymentProviderAdaptor.PaymentSubmitResult r = ecocash.submitPayment(charge(500, "USD"));
        assertFalse(r.isSubmitted());
        assertTrue(r.isOutcomeUnknown(), "5xx: the charge may have been processed; resolved by query, never re-sent");
    }

    @Test
    void callback_carriesItsOwnCurrency_andExactAmount() throws Exception {
        String cb = "{\"clientCorrelator\":\"PP-USD-20261016-000101\",\"ecocashReference\":\"MP261016.1234.A00001\","
                + "\"transactionOperationStatus\":\"COMPLETED\",\"paymentAmount\":{\"charginginformation\":{\"amount\":0.29,\"currency\":\"ZWG\"}}}";
        PaymentProviderAdaptor.CallbackEvent e = ecocash.parseCallback(cb);
        assertEquals("SUCCESS", e.getStatus());
        assertEquals(29, e.getAmountCents(), "0.29 -> 29 exactly");
        assertEquals("ZWG", e.getCurrency(), "was always reported as USD");
        assertEquals("MP261016.1234.A00001", e.getProviderReference());
        assertEquals("PENDING", ecocash.mapEcoCashStatus("CHARGED"), "CHARGED is the push, not a payment");
    }

    @Test
    void statusQuery_usesTheSpecPath() throws Exception {
        answer = "{\"clientCorrelator\":\"PP-USD-20261016-000101\",\"transactionOperationStatus\":\"COMPLETED\",\"ecocashReference\":\"MP1\","
                + "\"paymentAmount\":{\"charginginformation\":{\"amount\":19.99,\"currency\":\"USD\"}}}";
        EcoCashAdaptor.QueryResult q = ecocash.queryTransaction("PP-USD-20261016-000101", "0771234567");
        assertEquals("GET", requests.get(0)[0]);
        assertEquals("/ecocashGateway/payment/v1/771234567/transactions/amount/PP-USD-20261016-000101", requests.get(0)[1]);
        assertEquals("COMPLETED", q.getTransactionStatus());
    }
}
