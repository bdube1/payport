package zw.co.payport.gateway.iso20022;

import org.junit.jupiter.api.Test;
import zw.co.payport.gateway.settlement.SettlementBatchService.PayoutResult;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Mapping of camt.053.001.08 and pain.002.001.10 to Payport's model, and conformance of the fixtures to the official schemas. */
class Iso20022MappingTest {

    private final Iso20022Schemas schemas = new Iso20022Schemas();

    static String fixture(String name) throws Exception {
        try (var in = Iso20022MappingTest.class.getResourceAsStream("/iso20022/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void officialSchemasAreInstalled() {
        for (String m : List.of(Iso20022Schemas.CAMT_053, Iso20022Schemas.PAIN_001, Iso20022Schemas.PAIN_002)) {
            assertTrue(schemas.installed(m), m + ": run scripts/iso20022/fetch-schemas.sh src/main/resources/iso20022/xsd");
        }
    }

    @Test
    void bankUsageGuidelineIsAStricterLayer() throws Exception {
        String hv = fixture("camt053-hvpsplus.xml").replace("{{E2E_PAID}}", "PO-1");
        assertDoesNotThrow(() -> schemas.validateGuideline(BankMessageService.HVPS_PLUS, hv.getBytes(StandardCharsets.UTF_8)));
        var s = Camt053Parser.parse(schemas.validate(Iso20022Schemas.CAMT_053, hv.getBytes(StandardCharsets.UTF_8)), 2).get(0);
        assertEquals("BNK-REF-HV-1", s.entries().get(0).bankReference(), "HVPS+ carries the bank reference in TxDtls/Refs");
        assertEquals(java.time.LocalDate.of(2026, 10, 16), s.entries().get(0).bookingDate());
        // The base fixture is valid ISO 20022 but not HVPS+ (no pagination, no UTC offsets, a pending entry).
        String base = fixture("camt053-sample.xml").replace("{{E2E_PAID}}", "PO-1");
        var e = assertThrows(Iso20022Schemas.InvalidMessageException.class, () -> schemas.validateGuideline(BankMessageService.HVPS_PLUS, base.getBytes(StandardCharsets.UTF_8)));
        assertTrue(e.getMessage().contains("usage guideline"), e.getMessage());
        assertThrows(Iso20022Schemas.InvalidMessageException.class, () -> schemas.validateGuideline("../../etc/passwd", base.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void camt053_validatesAndMapsBalancesEntriesAndReferences() throws Exception {
        byte[] xml = fixture("camt053-sample.xml").replace("{{E2E_PAID}}", "PO-USD-20261016-abcd1234-1").getBytes(StandardCharsets.UTF_8);
        var doc = schemas.validate(Iso20022Schemas.CAMT_053, xml);
        var statements = Camt053Parser.parse(doc, 2);
        assertEquals(1, statements.size());
        var s = statements.get(0);
        assertEquals("STMT-20261016-USD", s.statementId());
        assertEquals("ZW-SPONSOR-USD-0001", s.accountId());
        assertEquals("USD", s.currency());
        assertEquals(100_000, s.openingMinor());
        assertEquals(140_300, s.closingMinor());
        assertEquals(50_000, s.bookedCredits());
        assertEquals(9_700, s.bookedDebits(), "the pending entry does not count");
        assertEquals(s.openingMinor() + s.bookedCredits() - s.bookedDebits(), s.closingMinor());
        var payout = s.entries().get(0);
        assertEquals("DBIT", payout.direction());
        assertEquals("BOOK", payout.status());
        assertEquals("PO-USD-20261016-abcd1234-1", payout.endToEndId());
        assertEquals("BNK-REF-0001", payout.bankReference());
        assertEquals("Alpha Store", payout.counterparty());
        assertEquals("Wallet operator settlement", s.entries().get(1).counterparty());
        assertEquals("PDNG", s.entries().get(2).status());
    }

    @Test
    void pain002_mapsAcceptedRejectedAndInProgress() throws Exception {
        byte[] xml = fixture("pain002-sample.xml").replace("{{BATCH}}", "STL-USD-20261016-abcd1234")
                .replace("{{REF1}}", "PO-1").replace("{{REF2}}", "PO-2").replace("{{REF3}}", "PO-3").getBytes(StandardCharsets.UTF_8);
        var report = Pain002Parser.parse(schemas.validate(Iso20022Schemas.PAIN_002, xml), List.of());
        assertEquals("STL-USD-20261016-abcd1234", report.originalMsgId());
        assertEquals(List.of(new PayoutResult("PO-1", "PAID", "BNK-REF-0001", null),
                new PayoutResult("PO-2", "FAILED", null, "AC04: Closed account number")), report.results());
        assertEquals(1, report.inProgress(), "ACSP is not final");
    }

    @Test
    void groupRejectionFailsEveryInstruction() {
        String xml = "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.002.001.10\"><CstmrPmtStsRpt><GrpHdr><MsgId>R1</MsgId>"
                + "<CreDtTm>2026-10-16T12:00:00</CreDtTm></GrpHdr><OrgnlGrpInfAndSts><OrgnlMsgId>STL-X</OrgnlMsgId><OrgnlMsgNmId>pain.001.001.09</OrgnlMsgNmId>"
                + "<GrpSts>RJCT</GrpSts><StsRsnInf><Rsn><Cd>FF01</Cd></Rsn></StsRsnInf></OrgnlGrpInfAndSts></CstmrPmtStsRpt></Document>";
        var report = Pain002Parser.parse(schemas.validate(Iso20022Schemas.PAIN_002, xml.getBytes(StandardCharsets.UTF_8)), List.of("PO-1", "PO-2"));
        assertEquals(2, report.results().size());
        assertTrue(report.results().stream().allMatch(r -> r.status().equals("FAILED") && r.reason().startsWith("FF01")));
    }

    @Test
    void refusesNonConformingHostileAndWrongDocuments() throws Exception {
        String ok = fixture("camt053-sample.xml").replace("{{E2E_PAID}}", "PO-1");
        // Required element missing (BkTxCd): the official schema refuses it.
        String noCode = ok.replaceFirst("(?s)<BkTxCd>.*?</BkTxCd>", "");
        var e = assertThrows(Iso20022Schemas.InvalidMessageException.class, () -> schemas.validate(Iso20022Schemas.CAMT_053, noCode.getBytes(StandardCharsets.UTF_8)));
        assertTrue(e.getMessage().contains("does not conform"), e.getMessage());
        // DTDs / external entities are never processed.
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.08\">&x;</Document>";
        assertThrows(Iso20022Schemas.InvalidMessageException.class, () -> schemas.validate(Iso20022Schemas.CAMT_053, xxe.getBytes(StandardCharsets.UTF_8)));
        // A different message type is not accepted in place of another.
        assertThrows(Iso20022Schemas.InvalidMessageException.class, () -> schemas.validate(Iso20022Schemas.PAIN_002, ok.getBytes(StandardCharsets.UTF_8)));
        // More decimals than the currency has: refused, never rounded.
        assertThrows(ArithmeticException.class, () -> Xml.minor("97.001", 2));
        assertEquals(9_700, Xml.minor("97.00", 2));
        assertEquals("291.00", Pain001PayoutAdapter.amount(29_100));
        assertEquals("0.05", Pain001PayoutAdapter.amount(5));
    }
}
