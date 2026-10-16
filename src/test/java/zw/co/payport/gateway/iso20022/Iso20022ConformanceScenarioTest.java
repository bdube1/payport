package zw.co.payport.gateway.iso20022;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import zw.co.payport.gateway.ledger.AccountKey;
import zw.co.payport.gateway.ledger.Book;
import zw.co.payport.gateway.ledger.LedgerCategory;
import zw.co.payport.gateway.ledger.LedgerClient;
import zw.co.payport.gateway.ledger.templates.PostingTemplates;
import zw.co.payport.gateway.markets.MarketRegistry;
import zw.co.payport.gateway.merchant.MerchantPlatformService;
import zw.co.payport.gateway.service.CheckoutSessionService;
import zw.co.payport.gateway.service.SmsNotificationService;
import zw.co.payport.gateway.settlement.PayoutFormats;
import zw.co.payport.gateway.settlement.SettlementBatchService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 7 acceptance on MySQL 8 with the official ISO 20022 schemas:
 *  - a settlement batch becomes a pain.001.001.09 that validates against the schema;
 *  - the bank's pain.002.001.10 is validated and applied (PAID with the bank's reference, FAILED with the reason);
 *  - the paid payout is posted to the ledger of the market's legal entity;
 *  - the bank's camt.053.001.08 is validated, balance-checked and every booked entry traced:
 *    bank entry -> payout -> ledger journal; anything else becomes a reconciliation exception;
 *  - the sandbox market works from configuration only and can never take live money.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class Iso20022ConformanceScenarioTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0");

    static final String ACCOUNT = "ZW-SPONSOR-USD-0001";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("DB_URL", MYSQL::getJdbcUrl);
        r.add("DB_USERNAME", MYSQL::getUsername);
        r.add("DB_PASSWORD", MYSQL::getPassword);
        r.add("JWT_SECRET", () -> "phase7-test-secret-phase7-test-secret-phase7-test-0123456789");
        r.add("payport.payments.resolver.enabled", () -> "false");
        r.add("payport.payments.watchdog.enabled", () -> "false");
        r.add("payport.fulfilment.worker.enabled", () -> "false");
        r.add("payport.settlement.mode", () -> "LIVE");
        r.add("payport.settlement.require-reconciled", () -> "false");
        r.add("payport.screening.un.enabled", () -> "false");
    }

    /** Market settings are data (set from the ops console in real life): this ZW bank takes ISO 20022. */
    @org.junit.jupiter.api.BeforeEach
    void bankSettings() {
        jdbc.update("UPDATE markets SET payout_format = 'ISO20022_PAIN_001_001_09', bank_bic = 'PPTSZWHA', bank_name = 'Test Sponsor Bank', "
                + "statement_guideline = NULL WHERE country = 'ZW'");
        jdbc.update("UPDATE market_currencies SET bank_account_number = ? WHERE currency = 'USD'", ACCOUNT);
        markets.reload();
    }

    @MockitoBean SmsNotificationService sms;

    @Autowired JdbcTemplate jdbc;
    @Autowired SettlementBatchService settlement;
    @Autowired BankMessageService bank;
    @Autowired Iso20022Schemas schemas;
    @Autowired LedgerClient ledger;
    @Autowired MarketRegistry markets;
    @Autowired PayoutFormats formats;
    @Autowired MerchantPlatformService platform;
    @Autowired CheckoutSessionService checkout;

    private long merchant(String name, String type, String number, String institution, String branch) {
        jdbc.update("INSERT INTO merchants (business_name, registration_number, contact_email, contact_phone, status, default_currency) "
                + "VALUES (?, ?, ?, '+263770000000', 'ACTIVE', 'USD')", name, "REG-" + name, name.toLowerCase().replace(' ', '.') + "@example.test");
        long id = jdbc.queryForObject("SELECT MAX(id) FROM merchants", Long.class);
        jdbc.update("INSERT INTO merchant_settlement_destinations (merchant_id, currency, type, account_name, account_number, institution, branch, status, "
                + "requested_by, requested_at, verified_by, verified_at) VALUES (?, 'USD', ?, ?, ?, ?, ?, 'VERIFIED', 'owner', NOW(6), 'ops.verifier', NOW(6))",
                id, type, name, number, institution, branch);
        return id;
    }

    private long paidSession(long merchantId, long amount, long fee) {
        LocalDateTime t = LocalDateTime.now().minusDays(2);
        jdbc.update("INSERT INTO checkout_sessions (merchant_id, reference, amount_cents, currency, status, transaction_reference, expires_at, "
                        + "created_at, updated_at, livemode, commission_amount_cents, net_amount_cents) VALUES (?, ?, ?, 'USD', 'PAID', ?, ?, ?, ?, TRUE, ?, ?)",
                merchantId, "ORD-" + merchantId + "-" + amount, amount, "TX-" + merchantId + "-" + amount, t, t, t, fee, amount - fee);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM checkout_sessions", Long.class);
        // What the shadow ledger posts for a paid session (Phase 1 template).
        ledger.post(PostingTemplates.merchantCollection("PAYPORT_ZW", new PostingTemplates.Collection(id, merchantId, true, "USD", amount, fee,
                amount - fee, "SIM", t.toLocalDate())));
        return id;
    }

    private long balance(LedgerCategory c, Object owner) {
        return ledger.balance(AccountKey.of("PAYPORT_ZW", Book.LIVE, c, String.valueOf(owner), "USD")).balance();
    }

    @Test
    void payoutFlow_pain001_pain002_camt053_tracedThroughTheLedger() throws Exception {
        assertEquals(Map.of(Iso20022Schemas.CAMT_053, true, Iso20022Schemas.PAIN_001, true, Iso20022Schemas.PAIN_002, true,
                "guideline:" + BankMessageService.HVPS_PLUS, true), bank.schemaStatus(), "schemas must be installed and pinned (scripts/iso20022)");
        assertEquals(List.of(), markets.referenceDataProblems());

        long alpha = merchant("Alpha Store", "BANK", "1234567890", "Test Bank Harare", "Samora Machel Ave");
        long beta = merchant("Beta Kiosk", "MOBILE_WALLET", "263771234567", "Wallet Operator", null);
        paidSession(alpha, 10_000, 300);   // net 9,700
        paidSession(beta, 20_000, 600);    // net 19,400
        assertEquals(9_700, balance(LedgerCategory.MERCHANT_PAYABLE, alpha));

        // 1. Settlement batch -> pain.001.001.09, schema-valid by construction.
        SettlementBatchService.Batch batch = settlement.draft("USD", "fin.maker");
        settlement.approve(batch.id(), "fin.checker");
        SettlementBatchService.PayoutFile file = settlement.instruct(batch.id(), "fin.ops");
        assertEquals("ISO20022_PAIN_001_001_09", file.format());
        assertTrue(file.fileName().endsWith(".xml"));
        assertDoesNotThrow(() -> schemas.validate(Iso20022Schemas.PAIN_001, file.content().getBytes(StandardCharsets.UTF_8)));
        Map<Long, String> refs = new java.util.HashMap<>();
        for (Map<String, Object> i : settlement.items(batch.id())) refs.put(((Number) i.get("merchant_id")).longValue(), (String) i.get("instruction_ref"));
        String x = file.content();
        assertTrue(x.contains("<MsgId>" + batch.batchRef() + "</MsgId>"));
        assertTrue(x.contains("<NbOfTxs>2</NbOfTxs>") && x.contains("<CtrlSum>291.00</CtrlSum>"));
        assertTrue(x.contains("<DbtrAcct><Id><Othr><Id>" + ACCOUNT + "</Id></Othr></Id><Ccy>USD</Ccy></DbtrAcct>"));
        assertTrue(x.contains("<BICFI>PPTSZWHA</BICFI>"));
        assertTrue(x.contains("<EndToEndId>" + refs.get(alpha) + "</EndToEndId>") && x.contains("<InstdAmt Ccy=\"USD\">97.00</InstdAmt>"));
        assertTrue(x.contains("<Id>263771234567</Id><SchmeNm><Prtry>MSISDN</Prtry></SchmeNm>"), "wallet payee by MSISDN");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM iso_messages WHERE direction = 'OUT' AND message_type = 'pain.001.001.09' AND related_ref = ?",
                Integer.class, batch.batchRef()));
        assertEquals(file.sha256(), settlement.instruct(batch.id(), "fin.ops").sha256(), "re-instructing returns the same file");

        // 2. The bank's pain.002.001.10: alpha accepted and settled, beta rejected (closed account).
        String sts = Iso20022MappingTest.fixture("pain002-sample.xml").replace("{{BATCH}}", batch.batchRef())
                .replace("{{REF1}}", refs.get(alpha)).replace("{{REF2}}", refs.get(beta))
                .replaceFirst("(?s)<TxInfAndSts><OrgnlInstrId>\\{\\{REF3}}.*?</TxInfAndSts>", "");
        Map<String, Object> applied = bank.applyPaymentStatusReport(sts.getBytes(StandardCharsets.UTF_8), "fin.ops");
        assertEquals(Map.of("PAID", 1, "FAILED", 1), applied.get("applied"));
        assertEquals("BNK-REF-0001", jdbc.queryForObject("SELECT bank_reference FROM stl_items WHERE instruction_ref = ?", String.class, refs.get(alpha)));
        assertEquals("AC04: Closed account number", jdbc.queryForObject("SELECT return_reason FROM stl_items WHERE instruction_ref = ?", String.class, refs.get(beta)));
        assertEquals(Map.of("ALREADY_PAID", 1, "ALREADY_FAILED", 1), bank.applyPaymentStatusReport(sts.getBytes(StandardCharsets.UTF_8), "fin.ops").get("applied"),
                "re-applying the same report changes nothing");
        assertThrows(Iso20022Schemas.InvalidMessageException.class, () -> bank.applyPaymentStatusReport(
                sts.replace(batch.batchRef(), "STL-UNKNOWN").getBytes(StandardCharsets.UTF_8), "fin.ops"));

        // 3. Ledger of the market's entity: the paid payout leaves MERCHANT_PAYABLE and the bank account.
        assertEquals("POSTED", jdbc.queryForObject("SELECT status FROM stl_item_ledger s JOIN stl_items i ON i.id = s.stl_item_id WHERE i.instruction_ref = ?",
                String.class, refs.get(alpha)));
        assertEquals(0, balance(LedgerCategory.MERCHANT_PAYABLE, alpha));
        assertEquals(19_400, balance(LedgerCategory.MERCHANT_PAYABLE, beta), "the rejected payout is still owed");
        assertEquals(-9_700, balance(LedgerCategory.BANK_CASH, "SETTLEMENT_USD"));

        // 4. The bank's camt.053.001.08: validated, balanced, traced.
        String stmt = Iso20022MappingTest.fixture("camt053-sample.xml").replace("{{E2E_PAID}}", refs.get(alpha));
        Map<String, Object> imported = bank.importStatement(stmt.getBytes(StandardCharsets.UTF_8), "fin.carol");
        @SuppressWarnings("unchecked") Map<String, Object> st = ((List<Map<String, Object>>) imported.get("statements")).get(0);
        assertEquals("PAYPORT_ZW", st.get("entity"));
        assertEquals(Map.of("TRACED", 1, "UNIDENTIFIED", 1, "NOT_BOOKED", 1), st.get("trace"));
        Map<String, Object> traced = jdbc.queryForMap("SELECT * FROM bank_statement_entries WHERE end_to_end_id = ?", refs.get(alpha));
        String entryRef = (String) traced.get("ledger_entry_ref");
        assertNotNull(entryRef);
        Map<String, Object> journal = jdbc.queryForMap("SELECT entity_code, template_code, source_type, source_ref FROM ledger_entries WHERE entry_ref = ?", entryRef);
        assertEquals(Map.of("entity_code", "PAYPORT_ZW", "template_code", "STL_PAYOUT_PAID", "source_type", "stl_item", "source_ref", refs.get(alpha)), journal,
                "bank entry -> payout -> ledger journal");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM recon_exceptions WHERE category = 'MISSING_IN_PAYPORT' AND provider_code = 'BANK_ZW_USD' "
                + "AND provider_reference = 'BNK-REF-0002'", Integer.class), "the unidentified credit is an exception, not an adjustment");
        assertEquals(true, bank.importStatement(stmt.getBytes(StandardCharsets.UTF_8), "fin.carol").get("alreadyImported"));

        // Refusals: wrong account, a statement that does not balance, a non-conforming document.
        var wrongAccount = assertThrows(Iso20022Schemas.InvalidMessageException.class, () -> bank.importStatement(
                stmt.replace(ACCOUNT, "SOMEONE-ELSE").replace("STMT-20261016-USD", "S2").getBytes(StandardCharsets.UTF_8), "fin.carol"));
        assertTrue(wrongAccount.getMessage().contains("not PayPort's USD account"), wrongAccount.getMessage());
        var unbalanced = assertThrows(Iso20022Schemas.InvalidMessageException.class, () -> bank.importStatement(
                stmt.replace("1403.00", "1404.00").replace("STMT-20261016-USD", "S3").getBytes(StandardCharsets.UTF_8), "fin.carol"));
        assertTrue(unbalanced.getMessage().contains("does not balance"), unbalanced.getMessage());
        assertThrows(Iso20022Schemas.InvalidMessageException.class, () -> bank.importStatement(
                stmt.replace("<CdtDbtInd>DBIT</CdtDbtInd>", "<CdtDbtInd>DEBIT</CdtDbtInd>").getBytes(StandardCharsets.UTF_8), "fin.carol"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM bank_statements WHERE account_id IN (?, 'SOMEONE-ELSE') AND statement_id <> 'STMT-HV-20261016-USD'",
                Integer.class, ACCOUNT), "nothing stored from refused files");

        // The bank's usage guideline (HVPS+) switched on for ZW from the market settings: a base-only statement is
        // now refused; one that follows the guideline is accepted and traced to the same journal.
        jdbc.update("UPDATE markets SET statement_guideline = ? WHERE country = 'ZW'", BankMessageService.HVPS_PLUS);
        markets.reload();
        var notGuideline = assertThrows(Iso20022Schemas.InvalidMessageException.class, () -> bank.importStatement(
                stmt.replace("STMT-20261016-USD", "S4").getBytes(StandardCharsets.UTF_8), "fin.carol"));
        assertTrue(notGuideline.getMessage().contains("usage guideline"), notGuideline.getMessage());
        String hv = Iso20022MappingTest.fixture("camt053-hvpsplus.xml").replace("{{E2E_PAID}}", refs.get(alpha));
        @SuppressWarnings("unchecked") Map<String, Object> hvSt = ((List<Map<String, Object>>) bank.importStatement(hv.getBytes(StandardCharsets.UTF_8), "fin.carol")
                .get("statements")).get(0);
        assertEquals(Map.of("TRACED", 1), hvSt.get("trace"));
        assertEquals(entryRef, jdbc.queryForObject("SELECT ledger_entry_ref FROM bank_statement_entries WHERE bank_reference = 'BNK-REF-HV-1'", String.class),
                "HVPS+ references (TxDtls/Refs/AcctSvcrRef) trace to the same journal");

        assertEquals(List.of(), ledger.integrityReport());
    }

    @Test
    void sandboxMarket_configurationOnly_neverLive() throws Exception {
        MarketRegistry.Market zm = markets.all().stream().filter(m -> !m.live()).findFirst().orElseThrow();
        String ccy = zm.currencies().get(0).code();
        assertEquals("SANDBOX", zm.status());
        assertEquals(Pain001PayoutAdapter.FORMAT, formats.forCurrency(ccy).format(), "payout format chosen by the market's configuration");

        long m = merchant("Sandbox Trader", "BANK", "999", "Sandbox Bank", null);
        var refused = assertThrows(IllegalArgumentException.class, () -> platform.enable(m, zm.country(), ccy, true, "ops.bob"));
        assertTrue(refused.getMessage().contains("SANDBOX market"), refused.getMessage());
        // Even an enablement written straight into the database cannot open live money in a sandbox currency.
        jdbc.update("INSERT INTO merchant_enablements (merchant_id, country, currency, status, enabled_by, enabled_at) VALUES (?, ?, ?, 'ENABLED', 'x', NOW())",
                m, zm.country(), ccy);
        assertThrows(CheckoutSessionService.CurrencyNotEnabledException.class, () -> checkout.requireLiveCurrency(m, ccy));
        assertTrue(checkout.isSupportedCurrency(ccy), "test-mode payments may use it");
        assertFalse(checkout.isSupportedCurrency("EUR"));

        // The sandbox bank's camt.053 goes through the same adapters into the sandbox entity's records.
        MarketRegistry.BankAccount account = markets.bankAccount(ccy);
        String stmt = Iso20022MappingTest.fixture("camt053-sample.xml").replace("{{E2E_PAID}}", "NOTPROVIDED")
                .replace(ACCOUNT, account.number()).replace("Ccy=\"USD\"", "Ccy=\"" + ccy + "\"").replace("<Ccy>USD</Ccy>", "<Ccy>" + ccy + "</Ccy>")
                .replace("STMT-20261016-USD", "SBX-0001").replace("BNK-REF-", "SBX-REF-");
        Map<String, Object> imported = bank.importStatement(stmt.getBytes(StandardCharsets.UTF_8), "fin.carol");
        @SuppressWarnings("unchecked") Map<String, Object> st = ((List<Map<String, Object>>) imported.get("statements")).get(0);
        assertEquals(zm.country(), st.get("country"));
        assertEquals(zm.legalEntity(), st.get("entity"));
        assertEquals(Map.of("UNIDENTIFIED", 2, "NOT_BOOKED", 1), st.get("trace"));
    }
}
