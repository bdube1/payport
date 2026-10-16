package zw.co.payport.gateway.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The payment windows are owner-approved values. This test pins them: changing a window in
 * application.properties fails the build until the change is approved and this test updated.
 */
class PaymentWindowsTest {

    private static PaymentTimeoutWatchdog watchdog(org.springframework.core.env.Environment env, int defaultSeconds) {
        return new PaymentTimeoutWatchdog(null, null, null, null, env, defaultSeconds, false);
    }

    @Test
    void approvedValuesInApplicationProperties() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        new PropertiesPropertySourceLoader().load("app", new ClassPathResource("application.properties"))
                .forEach(ps -> env.getPropertySources().addLast(ps));
        int def = Integer.parseInt(env.resolvePlaceholders("${payport.payments.timeout-seconds}"));
        PaymentTimeoutWatchdog w = watchdog(env, def);
        assertEquals(180, def, "default window (owner approval needed to change)");
        assertEquals(180, w.windowSeconds("ECOCASH"));
        assertEquals(180, w.windowSeconds("ONEMONEY"));
        assertEquals(300, w.windowSeconds("OMARI"));
        assertEquals(1800, w.windowSeconds("ZIMSWITCH"));
        assertEquals(180, w.windowSeconds("ECOCASH", "USD"), "USSD uses the provider window until a channel value is approved");
        assertEquals(1800, w.windowSeconds("ZIMSWITCH", "WEB"));
    }

    @Test
    void mostSpecificSettingWins() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("payport.payments.provider-timeout-seconds.ECOCASH", "200")
                .withProperty("payport.payments.window-seconds.USD", "90")
                .withProperty("payport.payments.window-seconds.USD.ONEMONEY", "60");
        PaymentTimeoutWatchdog w = watchdog(env, 180);
        assertEquals(60, w.windowSeconds("ONEMONEY", "USD"));
        assertEquals(90, w.windowSeconds("ECOCASH", "USD"), "channel beats provider");
        assertEquals(200, w.windowSeconds("ECOCASH", "WEB"));
        assertEquals(180, w.windowSeconds("OMARI", "WEB"));
        assertEquals(180, w.windowSeconds(null, null));
    }
}
