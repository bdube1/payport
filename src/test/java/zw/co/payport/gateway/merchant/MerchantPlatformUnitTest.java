package zw.co.payport.gateway.merchant;

import org.junit.jupiter.api.Test;
import zw.co.payport.gateway.platform.SecretBox;
import zw.co.payport.gateway.service.WebhookService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Phase 4 rules that need no database: SSRF guard, key scopes, webhook signing, secret encryption, sandbox numbers. */
class MerchantPlatformUnitTest {

    private final WebhookTargetGuard strict = new WebhookTargetGuard(false, false);
    private final WebhookTargetGuard local = new WebhookTargetGuard(true, true);

    @Test
    void webhookTargets_onlyPublicHttps() {
        assertDoesNotThrow(() -> strict.check("https://8.8.8.8/hooks/payport"));
        assertDoesNotThrow(() -> strict.check("https://8.8.8.8:8443/hooks"));
        for (String bad : List.of(
                "http://8.8.8.8/hook",                 // not https
                "https://127.0.0.1/hook",              // loopback
                "https://10.1.2.3/hook",               // private
                "https://172.16.0.5/hook",
                "https://192.168.1.1/hook",
                "https://169.254.169.254/latest/meta-data", // cloud metadata
                "https://100.64.0.1/hook",             // CGNAT
                "https://0.0.0.0/hook",
                "https://[::1]/hook",
                "https://[::ffff:127.0.0.1]/hook",     // IPv4-mapped loopback
                "https://[fd00::1]/hook",              // unique local
                "https://user:pw@8.8.8.8/hook",        // credentials
                "https://8.8.8.8:22/hook",             // privileged port
                "ftp://8.8.8.8/hook")) {
            assertThrows(IllegalArgumentException.class, () -> strict.check(bad), bad);
        }
    }

    @Test
    void localTestingMode_stillRefusesMetadataAddresses() {
        assertDoesNotThrow(() -> local.check("http://127.0.0.1:18080/hook"));
        assertThrows(IllegalArgumentException.class, () -> local.check("http://169.254.169.254/latest/meta-data"));
        assertThrows(IllegalArgumentException.class, () -> local.check("http://[fe80::1]:8080/hook"));
    }

    @Test
    void scopes_areEnforcedPerEndpoint_andKeyManagementIsDashboardOnly() {
        Set<String> s = ApiKeyScopes.parse(ApiKeyScopes.normalise(List.of("checkout:read", "checkout:write")));
        assertTrue(ApiKeyScopes.allows(s, ApiKeyScopes.required("POST", "/api/v1/merchant/checkout-sessions")));
        assertTrue(ApiKeyScopes.allows(s, ApiKeyScopes.required("GET", "/api/v1/merchant/checkout-sessions/5")));
        assertFalse(ApiKeyScopes.allows(s, ApiKeyScopes.required("POST", "/api/v1/merchant/checkout-sessions/5/refunds")));
        assertFalse(ApiKeyScopes.allows(s, ApiKeyScopes.required("GET", "/api/v1/merchant/balances")));
        assertFalse(ApiKeyScopes.allows(s, ApiKeyScopes.required("POST", "/api/v1/merchant/webhooks")));
        assertFalse(ApiKeyScopes.allows(s, ApiKeyScopes.required("GET", "/api/v1/merchant/readiness")), "unscoped endpoints need a full key");
        Set<String> all = ApiKeyScopes.parse("*");
        assertTrue(ApiKeyScopes.allows(all, ApiKeyScopes.required("GET", "/api/v1/merchant/payouts")));
        assertFalse(ApiKeyScopes.allows(all, ApiKeyScopes.required("POST", "/api/v1/merchant/api-keys")), "even a full key cannot mint keys");
        assertFalse(ApiKeyScopes.allows(all, ApiKeyScopes.required("POST", "/api/v1/merchant/settlement-destinations")), "nor change where money goes");
        assertThrows(IllegalArgumentException.class, () -> ApiKeyScopes.normalise(List.of("admin")));
        assertEquals("*", ApiKeyScopes.normalise(null));
    }

    @Test
    void webhookSignatureV2_isHmacOfTimestampDotBody() throws Exception {
        String body = "{\"id\":\"evt_1\",\"type\":\"checkout_session.paid\"}";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("whsec_test".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal(("1760000000." + body).getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, WebhookService.signV2(1760000000L, body, "whsec_test"));
        assertNotEquals(expected, WebhookService.signV2(1760000001L, body, "whsec_test"), "the timestamp is signed (replay protection)");
    }

    @Test
    void secretBox_encryptsAtRest_andReadsLegacyPlaintext() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        SecretBox box = new SecretBox(key, "");
        String sealed = box.seal("whsec_abc");
        assertTrue(SecretBox.isSealed(sealed));
        assertFalse(sealed.contains("whsec_abc"));
        assertNotEquals(sealed, box.seal("whsec_abc"), "random IV per seal");
        assertEquals("whsec_abc", box.open(sealed));
        assertEquals("legacy-plain", box.open("legacy-plain"));
        String tampered = sealed.substring(0, sealed.length() - 2) + (sealed.endsWith("A") ? "BB" : "AA");
        assertThrows(RuntimeException.class, () -> box.open(tampered), "GCM detects tampering");
    }

    @Test
    void sandboxMagicNumbers() {
        assertTrue(SandboxScenarios.forPhone("+263771234567").success());
        assertEquals("Insufficient funds", SandboxScenarios.forPhone("+263770000001").providerMessage());
        assertFalse(SandboxScenarios.forPhone("0770000002").success());
    }

    @Test
    void production_requiresAnExplicitDataKey_andForbidsLocalTestingSwitches() {
        assertTrue(zw.co.payport.gateway.platform.DeploymentEnvironment.isProduction(null), "unset = production (fail safe)");
        assertTrue(zw.co.payport.gateway.platform.DeploymentEnvironment.isProduction("prod"));
        assertFalse(zw.co.payport.gateway.platform.DeploymentEnvironment.isProduction("staging"));
        String real = Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32));
        assertThrows(IllegalStateException.class, () -> new SecretBox("", "jwt-secret", true), "no derived key in production");
        assertThrows(IllegalStateException.class, () -> new SecretBox(Base64.getEncoder().encodeToString(new byte[32]), "j", true), "no zero key");
        assertThrows(IllegalStateException.class, () -> new SecretBox(Base64.getEncoder().encodeToString(new byte[16]), "j", true), "wrong length");
        assertThrows(IllegalStateException.class, () -> new SecretBox("not base64!!", "j", true));
        SecretBox prod = new SecretBox(real, "j", true);
        assertEquals("s", prod.open(prod.seal("s")));
        assertDoesNotThrow(() -> new SecretBox("", "jwt-secret", false), "derived key allowed outside production");
        assertThrows(IllegalStateException.class, () -> new WebhookTargetGuard(true, false, "production"));
        assertThrows(IllegalStateException.class, () -> new WebhookTargetGuard(false, true, null));
        assertDoesNotThrow(() -> new WebhookTargetGuard(false, false, "production"));
    }

    @Test
    void errorResponses_carryTheMessageWhereTheFrontendsReadIt() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var err = mapper.readTree(mapper.writeValueAsString(zw.co.payport.gateway.model.response.ApiResponse.error("PAYMENT_FAILED", "Insufficient funds", "r1")));
        assertEquals("Insufficient funds", err.path("message").asText(), "response.data.message (frontends)");
        assertEquals("Insufficient funds", err.path("error").path("message").asText(), "error.message (API reference)");
        var ok = mapper.readTree(mapper.writeValueAsString(zw.co.payport.gateway.model.response.ApiResponse.success(java.util.Map.of("a", 1), "r2")));
        assertFalse(ok.has("message"), "success bodies unchanged");
    }
}
