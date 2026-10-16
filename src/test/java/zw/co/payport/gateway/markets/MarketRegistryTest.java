package zw.co.payport.gateway.markets;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Market settings rules (no database), and no market-specific code for the sandbox country. */
class MarketRegistryTest {

    static MarketRegistry.Market market(String status, boolean live, String account) {
        return new MarketRegistry.Market("XX", "Example", status, null, "PAYPORT_XX", "Africa/Harare", "999", Pattern.compile("^7[0-9]{8}$"),
                List.of(new MarketRegistry.Currency("XXA", true, live, "A ", "MERCHANT_CHECKOUT", "999")), "CALENDAR.XX.HOLIDAYS", Map.of(), "NOT_CONFIGURED",
                List.of("UN-CONSOLIDATED"), new MarketRegistry.Bank("Bank", null, "PAYPORT_CSV_V1", "ISO20022_CAMT_053_001_08", null, null,
                Map.of("XXA", new MarketRegistry.BankAccount("XXA", account, "Settlement", "SETTLEMENT_XXA"))), 1);
    }

    @Test
    void validSettingsPass() {
        assertEquals(List.of(), MarketRegistry.validate(market("LIVE", true, "0123456789")));
        assertEquals(List.of(), MarketRegistry.validate(market("SANDBOX", false, "0123456789")));
    }

    @Test
    void invariantsAreEnforced() {
        assertTrue(MarketRegistry.validate(market("SANDBOX", true, "0123")).toString().contains("SANDBOX"), "a sandbox market never takes live money");
        assertFalse(MarketRegistry.validate(market("LIVE", true, "")).isEmpty(), "every currency needs a bank account");
        assertFalse(MarketRegistry.validate(market("OPEN", false, "1")).isEmpty(), "unknown status");
        MarketRegistry.Market disabledLive = new MarketRegistry.Market("XX", "Example", "LIVE", null, "E", "Africa/Harare", "999", Pattern.compile("x"),
                List.of(new MarketRegistry.Currency("XXA", false, true, "A", "P", null)), "C", Map.of(), "F", List.of(),
                new MarketRegistry.Bank("B", "NOT A BIC", "SOMETHING_ELSE", "ISO20022_CAMT_053_001_08", null, null,
                        Map.of("XXA", new MarketRegistry.BankAccount("XXA", "1", "n", "O"))), 1);
        String p = MarketRegistry.validate(disabledLive).toString();
        assertTrue(p.contains("cannot be live while disabled"), p);
        assertTrue(p.contains("unknown payout format"), p);
        assertTrue(p.contains("not a valid BIC"), p);
        assertEquals("****6789", MarketRegistry.mask("0123456789"));
    }

    /**
     * "Adding or switching on a country needs configuration only": no application code names the
     * sandbox country, its currency or entity. Its settings live in the database (markets).
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
