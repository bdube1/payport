package zw.co.payport.gateway.markets;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** The country and entity model is configuration: invariants, lookups, and no market-specific code for the sandbox country. */
class MarketRegistryTest {

    private final MarketRegistry markets = new MarketRegistry(new MockEnvironment(), null);

    MarketRegistryTest() throws Exception {}

    @Test
    void liveAndSandboxMarketsLoadFromConfiguration() {
        MarketRegistry.Market zw = markets.require("ZW"), zm = markets.require("ZM");
        assertTrue(zw.live());
        assertEquals("PAYPORT_ZW", zw.legalEntity());
        assertEquals("SANDBOX", zm.status());
        assertEquals("PAYPORT_ZM_SANDBOX", zm.legalEntity());
        assertTrue(markets.liveCurrency("USD"));
        assertTrue(markets.liveCurrency("zwg"));
        assertFalse(markets.liveCurrency("ZMW"), "a sandbox market's currency never takes live money");
        assertFalse(markets.liveCurrency("EUR"));
        assertEquals("ZMW", markets.requireSupportedCurrency("zmw"));
        var e = assertThrows(IllegalArgumentException.class, () -> markets.requireSupportedCurrency("EUR"));
        assertTrue(e.getMessage().contains("USD") && e.getMessage().contains("ZMW"), e.getMessage());
        assertEquals("ZM", markets.requireForCurrency("ZMW").country());
        assertEquals("ISO20022_PAIN_001_001_09", zm.bank().payoutFormat());
        assertEquals("PAYPORT_CSV_V1", zw.bank().payoutFormat(), "ZW keeps the interim CSV until the sponsor bank confirms its format");
        assertEquals("MERCHANT_CHECKOUT_ZWG", markets.checkoutProductType("ZWG"));
        assertEquals("K", markets.symbol("ZMW"));
    }

    @Test
    void phoneNumbersFollowEachMarketsNumberingPlan() {
        assertEquals("263771234567", markets.normaliseMobile("+263 77 123 4567", "ZW").orElseThrow());
        assertEquals("263771234567", markets.normaliseMobile("0771234567", "ZW").orElseThrow());
        assertTrue(markets.normaliseMobile("0961234567", "ZW").isEmpty());
        assertEquals("260961234567", markets.normaliseMobile("0961234567", "ZM").orElseThrow());
    }

    @Test
    void deploymentCanOverrideBankDetailsWithoutCodeChanges() throws Exception {
        MockEnvironment env = new MockEnvironment()
                .withProperty("payport.markets.ZW.bank.payout-format", "ISO20022_PAIN_001_001_09")
                .withProperty("payport.markets.ZW.bank.accounts.USD.number", "ACC-123");
        MarketRegistry r = new MarketRegistry(env, null);
        assertEquals("ISO20022_PAIN_001_001_09", r.require("ZW").bank().payoutFormat());
        assertEquals("ACC-123", r.bankAccount("USD").number());
        assertTrue(r.summary().toString().contains("****-123"), "account numbers are masked in the summary");
    }

    @Test
    void invariantsAreEnforced() throws Exception {
        ObjectMapper om = new ObjectMapper();
        var zm = (com.fasterxml.jackson.databind.node.ObjectNode) om.readTree(getClass().getResourceAsStream("/markets/ZM.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) zm.path("currencies").get(0)).put("live", true);
        var e = assertThrows(IllegalStateException.class, () -> MarketRegistry.validate(MarketRegistry.parse(zm, new MockEnvironment())));
        assertTrue(e.getMessage().contains("SANDBOX"), e.getMessage());

        var zw = (com.fasterxml.jackson.databind.node.ObjectNode) om.readTree(getClass().getResourceAsStream("/markets/ZW.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) zw.path("bank")).put("payoutFormat", "SOMETHING_ELSE");
        assertThrows(IllegalStateException.class, () -> MarketRegistry.validate(MarketRegistry.parse(zw, new MockEnvironment())));

        var noAccount = (com.fasterxml.jackson.databind.node.ObjectNode) om.readTree(getClass().getResourceAsStream("/markets/ZW.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) noAccount.path("bank").path("accounts")).remove("ZWG");
        assertThrows(IllegalStateException.class, () -> MarketRegistry.validate(MarketRegistry.parse(noAccount, new MockEnvironment())));
    }

    /**
     * Phase 7 acceptance: "adding a sandbox country needs configuration plus adapters only".
     * The sandbox country appears in configuration and reference data, never in application code.
     */
    @Test
    void noApplicationCodeNamesTheSandboxCountry() throws Exception {
        Pattern p = Pattern.compile("\"(ZM|ZMW)\"|Zambia|Kwacha|PAYPORT_ZM");
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) if (p.matcher(lines.get(i)).find()) hits.add(f + ":" + (i + 1));
            }
        }
        assertEquals(List.of(), hits);
    }
}
