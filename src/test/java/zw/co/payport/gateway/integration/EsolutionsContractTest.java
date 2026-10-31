package zw.co.payport.gateway.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import zw.co.payport.gateway.billers.BillerAdaptor;
import zw.co.payport.gateway.billers.EsolutionsBillers;
import zw.co.payport.gateway.service.adaptor.FulfilmentProviderAdaptor;
import zw.co.payport.gateway.service.adaptor.esolutions.ESolutionsClient;
import zw.co.payport.gateway.service.adaptor.esolutions.VendorReferences;
import zw.co.payport.gateway.service.adaptor.esolutions.zesa.ZesaTokenAdaptor;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for the eSolutions billers against spec-shaped fixtures (no eSolutions sandbox
 * access): what Payport sends (message type, processing code, references, amounts, currency,
 * auth) and how each answer is interpreted. Evidence level: SPEC_FIXTURE.
 */
class EsolutionsContractTest {

    EsolutionsStub stub;
    ESolutionsClient client;
    ZesaTokenAdaptor zesa;

    @BeforeEach
    void setUp() throws Exception {
        stub = new EsolutionsStub();
        client = new ESolutionsClient();
        ReflectionTestUtils.setField(client, "baseUrl", stub.url());
        ReflectionTestUtils.setField(client, "username", "payport");
        ReflectionTestUtils.setField(client, "password", "secret");
        ReflectionTestUtils.setField(client, "vendorNumber", "VE19257147501");
        ReflectionTestUtils.setField(client, "accountNumber", "0119217695806");
        ReflectionTestUtils.setField(client, "terminalId", "POS001");
        ReflectionTestUtils.setField(client, "timeoutSeconds", 2);
        zesa = new ZesaTokenAdaptor(client);
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    private FulfilmentProviderAdaptor.FulfilmentRequest vend(String deliveryRef, String currency) {
        return FulfilmentProviderAdaptor.FulfilmentRequest.builder().payportReference("PP-API-20261016-000042")
                .recipientIdentifier("37132587712").amountCents(1000).currency(currency).productTypeCode("ZESA_TOKEN")
                .deliveryReference(deliveryRef).build();
    }

    @Test
    void vend_sendsTheSpecMessage_withTheRecordedDeliveryReference() throws Exception {
        String ref = VendorReferences.forDelivery("PP-API-20261016-000042", 1);
        assertEquals("API20261016000042D1", ref);
        stub.responder = b -> EsolutionsStub.zesaSuccess((String) b.get("vendorReference"), "USD");

        FulfilmentProviderAdaptor.FulfilmentResult r = zesa.deliver(vend(ref, "USD"));

        assertTrue(r.isSuccess(), r.getMessage());
        assertTrue(zesa.usesDeliveryReference());
        Map<String, Object> sent = stub.requests.get(0).body();
        assertEquals("0200", sent.get("mti"));
        assertEquals("U50000", sent.get("processingCode"));
        assertEquals(ref, sent.get("vendorReference"), "the reference recorded before sending is the one sent");
        assertEquals(1000, ((Number) sent.get("transactionAmount")).intValue(), "minor units");
        assertEquals("USD", sent.get("currencyCode"));
        assertEquals("ZETDC", sent.get("merchantName"));
        assertEquals("ZETDC_PREPAID", sent.get("productName"));
        assertEquals("37132587712", sent.get("utilityAccount"));
        assertEquals("VE19257147501", sent.get("vendorNumber"));
        assertTrue(stub.requests.get(0).authorization().startsWith("Basic "));
        assertTrue(r.getFulfilmentData().contains("\"tokenNumber\":\"12345678901234567890\""), r.getFulfilmentData());
        assertTrue(r.getFulfilmentData().contains("\"energyCents\":870") && r.getFulfilmentData().contains("\"vatCents\":130"));
    }

    @Test
    void inProgress_isResolvedByResendOfTheOriginalReference_neverByASecondVend() throws Exception {
        String ref = VendorReferences.forDelivery("PP-API-20261016-000043", 1);
        stub.responder = b -> "0201".equals(b.get("mti")) ? EsolutionsStub.zesaSuccess(ref, "USD") : Map.of("mti", "0210", "responseCode", "09");

        FulfilmentProviderAdaptor.FulfilmentResult r = zesa.deliver(vend(ref, "USD"));

        assertTrue(r.isSuccess(), r.getMessage());
        assertEquals(1, stub.bodies("U50000", "0200").size(), "exactly one vend");
        Map<String, Object> resend = stub.bodies(null, "0201").get(0);
        assertEquals(ref, resend.get("originalReference"));
        assertNotEquals(ref, resend.get("vendorReference"), "the lookup has its own reference");
    }

    @Test
    void timeout_isResolvedByResend_andAStillUnknownOutcomeIsNeverAFailure() throws Exception {
        String ref = VendorReferences.forDelivery("PP-API-20261016-000044", 1);
        stub.responder = b -> "0201".equals(b.get("mti")) ? Map.of("mti", "0210", "responseCode", "09") : (Object) 3_000;

        FulfilmentProviderAdaptor.FulfilmentResult r = zesa.deliver(vend(ref, "USD"));

        assertFalse(r.isSuccess());
        assertEquals(ref, r.getFulfilmentReference(), "the outcome stays attached to the reference eSolutions received");
        assertTrue(r.getRawResponse().contains("\"09\""), "classified UNKNOWN by DeliveryOutcomeRules (looked up later, not re-vended)");
        assertEquals(zw.co.payport.gateway.payments.DeliveryOutcomeRules.Verdict.UNKNOWN, zw.co.payport.gateway.payments.DeliveryOutcomeRules.classify("ZESA",
                zw.co.payport.gateway.payments.DeliveryOutcomeRules.code("ZESA", r.getErrorCode(), r.getRawResponse())));
        assertEquals(1, stub.bodies("U50000", "0200").size(), "never a second vend");
    }

    @Test
    void definiteDecline_usesTheBillersMeaning() throws Exception {
        stub.responder = b -> Map.of("mti", "0210", "responseCode", "14", "narrative", "Invalid meter");
        FulfilmentProviderAdaptor.FulfilmentResult r = zesa.deliver(vend(VendorReferences.forDelivery("PP-API-20261016-000045", 1), "USD"));
        assertFalse(r.isSuccess());
        assertEquals("Invalid meter number. Please check and try again.", r.getMessage());
    }

    @Test
    void laterLookup_usesThePaymentsCurrency() throws Exception {
        String ref = VendorReferences.forDelivery("PP-API-20261016-000046", 2);
        stub.responder = b -> EsolutionsStub.zesaSuccess(ref, "ZWG");
        FulfilmentProviderAdaptor.FulfilmentStatusResult s = zesa.queryDelivery(ref, "ZWG");
        assertEquals("DELIVERED", s.getStatus());
        Map<String, Object> sent = stub.requests.get(0).body();
        assertEquals("0201", sent.get("mti"));
        assertEquals(ref, sent.get("originalReference"));
        assertEquals("ZWG", sent.get("currencyCode"), "ZWG meters were looked up as USD before");
    }

    @Test
    void customerInformation_throughTheBillerFramework() throws Exception {
        stub.responder = b -> Map.of("mti", "0210", "responseCode", "00", "currencyCode", "USD",
                "customerData", "T MOYO\n12 Fife Street\nBulawayo", "utilityAccount", "37132587712");
        BillerAdaptor biller = new EsolutionsBillers().zesaBillerForTest(zesa);

        assertEquals("A ZESA meter number has exactly 11 digits.", biller.profile().accountHint());
        assertNotNull(biller.validateAccount("1234"), "format checked before any call");
        BillerAdaptor.Account a = biller.lookup("37132587712", "ZWG", null);

        assertEquals("T MOYO", a.holderName());
        assertEquals("12 Fife Street, Bulawayo", a.details().get("address"));
        assertFalse(a.payable(), "a USD meter cannot be paid in ZWG (found before payment, not at delivery)");
        assertEquals(java.util.List.of("USD"), a.acceptedCurrencies());
        assertEquals("310000", stub.requests.get(0).body().get("processingCode"));
    }

    @Test
    void references_areUniqueAndWithinTheSpecLimit() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String r = VendorReferences.lookup(i % 2 == 0 ? "RS" : "ZCI1");
            assertTrue(r.length() <= 20 && r.matches("[A-Z0-9]+"), r);
            seen.add(r);
        }
        assertEquals(2000, seen.size(), "lookup references no longer collide within a second");
        assertTrue(VendorReferences.forDelivery("some-unusually-long-reference-from-a-partner-system-123456", 3).length() <= 20);
        assertNotEquals(VendorReferences.forDelivery("PP-WEB-20261016-000042", 1), VendorReferences.forDelivery("PP-API-20261016-000042", 1),
                "channel is part of the reference");
        assertNotEquals(VendorReferences.forDelivery("PP-API-20261016-000042", 1), VendorReferences.forDelivery("PP-API-20261016-000042", 2),
                "each delivery attempt has its own reference");
    }
}
