package zw.co.payport.gateway.service.reconciliation.parsers;

import org.junit.jupiter.api.Test;
import zw.co.payport.gateway.service.reconciliation.ProviderStatementLine;
import zw.co.payport.gateway.service.reconciliation.StatementParser;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Each provider's statement parser against a synthetic file in the exact layout of the sample
 * report the owner supplied (scripts/test-fixtures/make_statement_fixtures.py; no real data).
 */
class StatementParsersTest {

    private static List<ProviderStatementLine> parse(StatementParser p, String file) throws Exception {
        try (InputStream in = StatementParsersTest.class.getResourceAsStream("/statements/" + file)) {
            assertNotNull(in, file);
            return p.parse(in);
        }
    }

    @Test
    void ecocash_creditsOnly_bothCurrencySheets_exactAmounts_dateOnly() throws Exception {
        List<ProviderStatementLine> l = parse(new EcoCashStatementParser(), "ecocash.xlsx");
        assertEquals(3, l.size(), "the DR liquidation sweep is not a payment");
        assertEquals(List.of("MP260819.0002.T0000002", "PP-WEB-20260819-000101"), l.get(0).getCandidateReferences());
        assertEquals(2200, l.get(0).getAmountCents());
        assertEquals("ZWG", l.get(0).getCurrency());
        assertEquals("771000001", l.get(0).getPhone());
        assertEquals("PP-WEB-20260819-000102-2", l.get(1).getCandidateReferences().get(1));
        assertEquals(125050, l.get(2).getAmountCents(), "thousands separator, exact cents");
        assertEquals(LocalDateTime.of(2026, 8, 19, 0, 0), l.get(0).getTimestamp());
    }

    @Test
    void innbucks_completedOnly() throws Exception {
        List<ProviderStatementLine> l = parse(new InnbucksStatementParser(), "innbucks.xlsx");
        assertEquals(2, l.size());
        assertEquals(1030, l.get(0).getAmountCents());
        assertTrue(l.get(0).getCandidateReferences().containsAll(List.of("PP-API-20260819-000201", "500000001")));
        assertEquals(LocalDateTime.of(2026, 8, 19, 11, 18, 17), l.get(0).getTimestamp());
        assertEquals(50, l.get(1).getAmountCents());
    }

    @Test
    void netone_successOnly() throws Exception {
        List<ProviderStatementLine> l = parse(new NetOneStatementParser(), "netone.xlsx");
        assertEquals(2, l.size());
        assertEquals(List.of("PP-WEB-20260819-000301"), l.get(0).getCandidateReferences());
        assertEquals(800, l.get(0).getAmountCents());
        assertEquals(LocalDateTime.of(2026, 8, 19, 23, 59, 58), l.get(0).getTimestamp());
        assertEquals(700, l.get(1).getAmountCents());
    }

    @Test
    void onemoney_successfulReceiptsOnly() throws Exception {
        List<ProviderStatementLine> l = parse(new OneMoneyStatementParser(), "onemoney.xlsx");
        assertEquals(2, l.size(), "status 40 (failed) and '-' (refund) rows are not receipts");
        assertEquals("PP-MOB-20260820-000401", l.get(0).getCandidateReferences().get(0));
        assertEquals(150, l.get(0).getAmountCents());
        assertEquals("ZWG", l.get(1).getCurrency());
        assertEquals(20, l.get(1).getAmountCents());
    }

    @Test
    void telecel_approvedOnly_numericCellsReadExactly() throws Exception {
        List<ProviderStatementLine> l = parse(new TelecelStatementParser(), "telecel.xlsx");
        assertEquals(1, l.size());
        assertEquals(5600, l.get(0).getAmountCents(), "agent debit shown negative");
        assertEquals(List.of("PPWEB20260819000501", "20000001"), l.get(0).getCandidateReferences(), "no 2.0000001E7");
        assertEquals("733000001", l.get(0).getPhone());
        assertEquals(LocalDateTime.of(2026, 8, 19, 18, 10, 54), l.get(0).getTimestamp());
    }

    @Test
    void zimswitch_approvedFinancialOnly_rrnAndStan() throws Exception {
        List<ProviderStatementLine> l = parse(new ZimSwitchStatementParser(), "zimswitch.xlsx");
        assertEquals(2, l.size(), "RspCode 51 is a decline");
        assertEquals(List.of("627418000000", "83502"), l.get(0).getCandidateReferences());
        assertEquals(100, l.get(0).getAmountCents());
        assertEquals(2000, l.get(1).getAmountCents());
        assertEquals(LocalDateTime.of(2026, 10, 1, 18, 38), l.get(0).getTimestamp());
        assertFalse(l.get(0).getRawRow().contains("500000*"), "card numbers are not kept");
    }

    @Test
    void omari_merchantSettlementsOnly() throws Exception {
        List<ProviderStatementLine> l = parse(new OmariStatementParser(), "omari.pdf");
        assertEquals(2, l.size(), "the liquidation (Adjustment Debit) is not a payment");
        assertEquals(1000, l.get(0).getAmountCents());
        assertTrue(l.get(0).getCandidateReferences().contains("aaaaaaaa0001"));
        assertEquals("263770000001", l.get(0).getPhone());
        assertEquals(4000, l.get(1).getAmountCents());
    }
}
