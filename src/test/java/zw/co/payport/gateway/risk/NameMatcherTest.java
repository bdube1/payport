package zw.co.payport.gateway.risk;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class NameMatcherTest {

    @Test
    void spellingVariantsMatch_unrelatedNamesDoNot() {
        assertTrue(NameMatcher.score("Jon Smyth", "John Smith") >= 0.88, "variant spelling is a (possible) hit");
        assertEquals(1.0, NameMatcher.score("ACME SMUGGLING (PRIVATE) LIMITED", "Acme Smuggling Ltd"), "legal suffixes ignored");
        assertEquals(1.0, NameMatcher.score("Dr. José Núñez", "JOSE NUNEZ"), "accents and titles ignored");
        assertTrue(NameMatcher.score("Smith John", "John Smith") >= 0.99, "word order does not matter");
        assertTrue(NameMatcher.score("Tendai Moyo", "John Smith") < 0.7);
        assertTrue(NameMatcher.score("John", "John Smith") < 0.88, "every listed token must be found");
        assertEquals(0.0, NameMatcher.score("", "John Smith"));
    }

    @Test
    void identifiersNormalise() {
        assertEquals("TEL:779990001", NameMatcher.identifier("+263 77 999 0001"));
        assertEquals("TEL:779990001", NameMatcher.identifier("0779990001"));
        assertEquals("ID:REGACME9", NameMatcher.identifier("reg-acme-9"));
    }

    @Test
    void disputeDeadlineStates() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 16, 12, 0);
        assertEquals("BREACHED", DisputeSlaService.state(now.minusHours(1), null, now));
        assertEquals("AT_RISK", DisputeSlaService.state(now.plusHours(5), null, now));
        assertEquals("ON_TRACK", DisputeSlaService.state(now.plusDays(3), null, now));
        assertEquals("MET", DisputeSlaService.state(now, now.minusHours(2), now));
        assertEquals("MET_LATE", DisputeSlaService.state(now.minusHours(3), now, now));
    }
}
