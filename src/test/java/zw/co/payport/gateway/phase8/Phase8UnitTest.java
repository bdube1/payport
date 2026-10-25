package zw.co.payport.gateway.phase8;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestTemplate;
import zw.co.payport.gateway.config.SecurityConfig;
import zw.co.payport.gateway.integration.IntegrationRegister;
import zw.co.payport.gateway.pos.PosService;
import zw.co.payport.gateway.service.adaptor.PaymentProviderAdaptor;
import zw.co.payport.gateway.service.adaptor.airtel.AirtelMoneyAdaptor;
import zw.co.payport.gateway.service.adaptor.mtn.MtnMomoAdaptor;
import zw.co.payport.gateway.whatsapp.WhatsAppService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Phase 8/9 pure rules: new-market rails fail closed, signatures, register overrides, CORS by environment. */
class Phase8UnitTest {

    @Test
    void mtnMomo_failsClosedUntilConfigured_andDerivesAStableV4ReferenceId() throws Exception {
        MtnMomoAdaptor mtn = new MtnMomoAdaptor(new RestTemplate(), "", "", "", "", "sandbox", "");
        assertFalse(mtn.configured());
        PaymentProviderAdaptor.PaymentSubmitResult r = mtn.submitPayment(PaymentProviderAdaptor.PaymentRequest.builder()
                .payportReference("PP-WEB-20261016-000001").amountCents(1050).currency("ZMW").customerPhone("+260961234567").build());
        assertFalse(r.isSubmitted());
        assertFalse(r.isOutcomeUnknown(), "nothing was sent, so the outcome is known");
        assertTrue(r.getMessage().contains("not configured"));
        assertThrows(IllegalStateException.class, () -> mtn.queryStatus("PP-WEB-20261016-000001"));

        String a = MtnMomoAdaptor.referenceId("PP-WEB-20261016-000001");
        assertEquals(a, MtnMomoAdaptor.referenceId("PP-WEB-20261016-000001"), "deterministic, so an unknown submit can still be queried");
        assertNotEquals(a, MtnMomoAdaptor.referenceId("PP-WEB-20261016-000001-2"));
        assertEquals(4, UUID.fromString(a).version());
        assertEquals(2, UUID.fromString(a).variant());

        PaymentProviderAdaptor.CallbackEvent e = mtn.parseCallback("{\"financialTransactionId\":\"123\",\"externalId\":\"PP-WEB-20261016-000001\",\"amount\":\"10.5\",\"currency\":\"ZMW\",\"status\":\"SUCCESSFUL\"}");
        assertEquals("SUCCESS", e.getStatus());
        assertEquals(1050, e.getAmountCents());
        assertEquals("PP-WEB-20261016-000001", e.getPayportReference());
        assertEquals("FAILED", mtn.parseCallback("{\"externalId\":\"x\",\"status\":\"FAILED\"}").getStatus());
        assertEquals("PENDING", mtn.parseCallback("{\"externalId\":\"x\",\"status\":\"PENDING\"}").getStatus());
    }

    @Test
    void airtelMoney_failsClosed_andMapsStatusCodes() throws Exception {
        AirtelMoneyAdaptor airtel = new AirtelMoneyAdaptor(new RestTemplate(), null, "", "", "", "ZMW");
        assertFalse(airtel.configured());
        PaymentProviderAdaptor.PaymentSubmitResult r = airtel.submitPayment(PaymentProviderAdaptor.PaymentRequest.builder()
                .payportReference("PP-WEB-20261016-000002").amountCents(500).currency("ZMW").customerPhone("+260971234567").build());
        assertFalse(r.isSubmitted());
        assertFalse(r.isOutcomeUnknown());
        PaymentProviderAdaptor.CallbackEvent e = airtel.parseCallback("{\"transaction\":{\"id\":\"PP-WEB-20261016-000002\",\"message\":\"ok\",\"status_code\":\"TS\",\"airtel_money_id\":\"A1\"}}");
        assertEquals("SUCCESS", e.getStatus());
        assertEquals("A1", e.getProviderReference());
        assertEquals("FAILED", airtel.parseCallback("{\"transaction\":{\"id\":\"x\",\"status_code\":\"TF\"}}").getStatus());
        assertEquals("UNKNOWN", airtel.parseCallback("{\"transaction\":{\"id\":\"x\",\"status_code\":\"TA\"}}").getStatus(), "ambiguous is never guessed");
    }

    @Test
    void integrationRegister_currencyOverrideFromConfiguration() {
        var loaded = IntegrationRegister.load();
        assertTrue(loaded.get("MTN_MOMO").supportsCurrency("ZMW"));
        assertFalse(loaded.get("MTN_MOMO").supportsCurrency("UGX"));
        MockEnvironment env = new MockEnvironment().withProperty("payport.integrations.MTN_MOMO.currencies", "ZMW, ugx");
        var over = new IntegrationRegister(env, null).all();
        assertTrue(over.get("MTN_MOMO").supportsCurrency("UGX"));
        assertTrue(over.get("ECOCASH").supportsCurrency("USD"), "others untouched");
        assertEquals("QUERY_CONFIRMED", over.get("AIRTEL_MONEY").callbackAuth());
        assertEquals("INTERNAL_PIN", over.get("WALLET").callbackAuth());
    }

    @Test
    void posSignature_coversMethodPathTimeNonceAndBody() {
        String a = PosService.sign("s3cret", "POST", "/api/v1/pos/sales", "1760000000", "nonce-0001", "{\"amountMinor\":100}");
        assertEquals(a, PosService.sign("s3cret", "post", "/api/v1/pos/sales", "1760000000", "nonce-0001", "{\"amountMinor\":100}"));
        assertNotEquals(a, PosService.sign("s3cret", "POST", "/api/v1/pos/sales", "1760000000", "nonce-0001", "{\"amountMinor\":1000}"), "body is signed");
        assertNotEquals(a, PosService.sign("s3cret", "POST", "/api/v1/pos/sales", "1760000001", "nonce-0001", "{\"amountMinor\":100}"), "time is signed");
        assertNotEquals(a, PosService.sign("s3cret", "POST", "/api/v1/pos/sales", "1760000000", "nonce-0002", "{\"amountMinor\":100}"), "nonce is signed");
        assertNotEquals(a, PosService.sign("other", "POST", "/api/v1/pos/sales", "1760000000", "nonce-0001", "{\"amountMinor\":100}"));
        assertEquals(64, a.length());
    }

    static zw.co.payport.gateway.whatsapp.WhatsAppSettings fixed(String appSecret, String verifyToken) {
        var v = new zw.co.payport.gateway.whatsapp.WhatsAppSettings.Values("", "", "", "", appSecret, verifyToken, "https://graph.facebook.com/v20.0");
        return new zw.co.payport.gateway.whatsapp.WhatsAppSettings(null, null, "", "", appSecret, verifyToken, "https://graph.facebook.com/v20.0") {
            @Override
            public Values get() {
                return v;
            }
        };
    }

    @Test
    void whatsappWebhook_signatureRequired_andFailsClosedWithoutSecret() throws Exception {
        WhatsAppService off = new WhatsAppService(null, null, null, fixed("", ""));
        assertFalse(off.configured());
        assertFalse(off.signatureValid("sha256=00", "{}"));
        WhatsAppService on = new WhatsAppService(null, null, null, fixed("app-secret", "verify-me"));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("app-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String body = "{\"entry\":[]}";
        String sig = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        assertTrue(on.signatureValid(sig, body));
        assertFalse(on.signatureValid(sig, body + " "), "any change to the body breaks it");
        assertFalse(on.signatureValid(null, body));
        assertEquals("challenge-1", on.verify("subscribe", "verify-me", "challenge-1"));
        assertThrows(SecurityException.class, () -> on.verify("subscribe", "wrong", "c"));
        assertThrows(SecurityException.class, () -> off.verify("subscribe", "", "c"), "no token configured: never verifies");
    }

    @Test
    void cors_localhostOnlyOutsideProduction_extraHttpsOriginsAllowed() {
        assertFalse(allows("production", "", "http://localhost:3000"));
        assertTrue(allows("production", "", "https://admin.payport.africa"));
        assertTrue(allows("staging", "", "http://localhost:3000"));
        assertTrue(allows("staging", "", "https://admin-staging.payport.africa"));
        assertTrue(allows("staging", "", "https://app-staging.payport.africa"));
        assertTrue(allows("staging", "", "https://merchant-staging.payport.africa"));
        assertFalse(allows("production", "", "https://app-staging.payport.africa"), "staging portals never reach production");
        assertTrue(allows("production", "https://ops.partner.example, http://insecure.example", "https://ops.partner.example"));
        assertFalse(allows("production", "https://ops.partner.example, http://insecure.example", "http://insecure.example"), "only https extras");
        assertTrue(allows("staging", "http://68.183.134.5:4001", "http://68.183.134.5:4001"), "staging may list an http origin");
        assertFalse(allows("staging", "http://68.183.134.5:4001", "http://68.183.134.5:9999"), "only the listed one");
        assertFalse(allows("", "http://68.183.134.5:4001", "http://68.183.134.5:4001"), "unset environment = production = https only");
    }

    private static boolean allows(String env, String extra, String origin) {
        SecurityConfig c = new SecurityConfig(null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(c, "environment", env);
        org.springframework.test.util.ReflectionTestUtils.setField(c, "extraOrigins", extra);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/products");
        req.setRequestURI("/api/v1/products");
        var cfg = c.corsConfigurationSource().getCorsConfiguration(req);
        return cfg != null && cfg.checkOrigin(origin) != null;
    }
}
