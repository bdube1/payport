package zw.co.payport.gateway.risk;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class UnSanctionsListParseTest {

    @Test
    void parsesTheUnFormat_individualsEntitiesAliasesDocuments() throws Exception {
        var p = UnSanctionsList.parse(getClass().getResourceAsStream("/screening/un-consolidated-fixture.xml"));
        assertEquals("2026-10-15T09:00:01.123-04:00", p.generatedAt());
        assertEquals(3, p.entries().size());
        var temba = p.entries().stream().filter(e -> e.entryRef().equals("TSi.001")).findFirst().orElseThrow();
        assertEquals("PERSON", temba.entityType());
        assertEquals("TEMBA FICTIONAL TESTPERSON", temba.name());
        assertTrue(temba.aliases().contains("Temba Testperson"));
        assertTrue(temba.aliases().contains("LOW:The Banker"), "low-quality aliases are marked");
        assertEquals(java.util.List.of("TP0099001"), temba.identifiers());
        assertEquals("1970-05-04", ((java.util.List<?>) temba.details().get("dateOfBirth")).get(0));
        var nora = p.entries().stream().filter(e -> e.entryRef().equals("TSi.002")).findFirst().orElseThrow();
        assertTrue(nora.aliases().contains("Нора Экзамплева"), "name in original script kept as an alias");
        var org = p.entries().stream().filter(e -> e.entryRef().equals("TSe.001")).findFirst().orElseThrow();
        assertEquals("ORG", org.entityType());
        assertEquals("FICTITIOUS ARMS TRADING COMPANY", org.name());
    }

    @Test
    void refusesXxeAndWrongDocuments() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><CONSOLIDATED_LIST dateGenerated=\"x\"><INDIVIDUALS><INDIVIDUAL>"
                + "<FIRST_NAME>&e;</FIRST_NAME><REFERENCE_NUMBER>X</REFERENCE_NUMBER></INDIVIDUAL></INDIVIDUALS></CONSOLIDATED_LIST>";
        assertThrows(Exception.class, () -> UnSanctionsList.parse(new ByteArrayInputStream(xxe.getBytes(StandardCharsets.UTF_8))), "DTDs are refused");
        assertThrows(Exception.class, () -> UnSanctionsList.parse(new ByteArrayInputStream("<html/>".getBytes())));
        assertThrows(Exception.class, () -> UnSanctionsList.parse(new ByteArrayInputStream("<CONSOLIDATED_LIST/>".getBytes())), "no dateGenerated");
        assertThrows(Exception.class, () -> UnSanctionsList.parse(new ByteArrayInputStream(
                "<CONSOLIDATED_LIST dateGenerated=\"2026-01-01\"><INDIVIDUALS/></CONSOLIDATED_LIST>".getBytes())), "no entries");
    }

    @Test
    void auditHashChainDetectsChanges() {
        LocalDateTime at = LocalDateTime.of(2026, 10, 16, 12, 0, 0, 123_000);
        String h1 = ComplianceAudit.hash("0".repeat(64), at, "staff:a", "X", "s", "d");
        assertEquals(h1, ComplianceAudit.hash("0".repeat(64), at, "staff:a", "X", "s", "d"));
        assertNotEquals(h1, ComplianceAudit.hash("0".repeat(64), at, "staff:a", "X", "s", "d!"));
        assertNotEquals(h1, ComplianceAudit.hash("1".repeat(64), at, "staff:a", "X", "s", "d"), "chained to the previous entry");
    }
}
