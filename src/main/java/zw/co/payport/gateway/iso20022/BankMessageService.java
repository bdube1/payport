package zw.co.payport.gateway.iso20022;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import zw.co.payport.gateway.markets.MarketRegistry;
import zw.co.payport.gateway.recon.ReconExceptionStore;
import zw.co.payport.gateway.settlement.PayoutLedger;
import zw.co.payport.gateway.settlement.SettlementBatchService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Processing of the bank's ISO 20022 messages (Phase 7):
 *
 *  - pain.002.001.10 status reports: validated against the official schema, matched to the
 *    original pain.001 (OrgnlMsgId = batch reference) and applied through
 *    SettlementBatchService.applyPayoutResults (the same rules as any bank result: PAID only
 *    with the bank's reference, the file hash as proof, re-applying is a no-op);
 *  - camt.053.001.08 statements of Payport's own accounts: validated, checked to be the
 *    market's account, checked to balance (opening + booked credits - booked debits = closing),
 *    stored, and every booked entry traced: payout -> settlement item -> ledger journal.
 *    Nothing is adjusted automatically; whatever does not trace becomes a reconciliation exception.
 */
@Slf4j
@Service
public class BankMessageService {

    public static final String HVPS_PLUS = "camt.053.001.08-HVPSPLUS";

    private final JdbcTemplate jdbc;
    private final Iso20022Schemas schemas;
    private final MarketRegistry markets;
    private final SettlementBatchService settlement;
    private final PayoutLedger payoutLedger;
    private final ReconExceptionStore exceptions;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProviderSettlementService providerSettlements;

    public BankMessageService(JdbcTemplate jdbc, Iso20022Schemas schemas, MarketRegistry markets, SettlementBatchService settlement,
                              PayoutLedger payoutLedger, ReconExceptionStore exceptions) {
        this.jdbc = jdbc;
        this.schemas = schemas;
        this.markets = markets;
        this.settlement = settlement;
        this.payoutLedger = payoutLedger;
        this.exceptions = exceptions;
    }

    // ─── pain.002 ─────────────────────────────────────────────────────────────

    public Map<String, Object> applyPaymentStatusReport(byte[] xml, String by) {
        Document doc = schemas.validate(Iso20022Schemas.PAIN_002, xml);
        List<String> refs = new ArrayList<>();
        Pain002Parser.Report pre = Pain002Parser.parse(doc, refs);
        List<Map<String, Object>> batch = jdbc.queryForList("SELECT id FROM stl_batches WHERE batch_ref = ?", pre.originalMsgId());
        if (batch.isEmpty()) throw new Iso20022Schemas.InvalidMessageException("pain.002 refers to unknown payout message " + pre.originalMsgId());
        long batchId = ((Number) batch.get(0).get("id")).longValue();
        refs.addAll(jdbc.queryForList("SELECT instruction_ref FROM stl_items WHERE batch_id = ? ORDER BY id", String.class, batchId));
        Pain002Parser.Report report = Pain002Parser.parse(doc, refs);
        for (SettlementBatchService.PayoutResult r : report.results()) {
            if (!refs.contains(r.instructionRef())) {
                throw new Iso20022Schemas.InvalidMessageException("pain.002 reports " + r.instructionRef() + ", which is not in payout message " + pre.originalMsgId());
            }
        }
        String sha = sha256(xml);
        long messageId = storeMessage("IN", Iso20022Schemas.PAIN_002, report.msgId(), pre.originalMsgId(), sha, xml, by);
        Map<String, Integer> applied = settlement.applyPayoutResults(report.results(), "pain.002:" + report.msgId(), sha, by);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("messageId", messageId);
        out.put("originalMsgId", report.originalMsgId());
        out.put("applied", applied);
        out.put("inProgress", report.inProgress());
        return out;
    }

    // ─── camt.053 ─────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> importStatement(byte[] xml, String by) {
        Document doc = schemas.validate(Iso20022Schemas.CAMT_053, xml);
        String sha = sha256(xml);
        List<Long> existing = jdbc.queryForList("SELECT id FROM iso_messages WHERE direction = 'IN' AND message_type = ? AND sha256 = ?", Long.class,
                Iso20022Schemas.CAMT_053, sha);
        if (!existing.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("alreadyImported", true);
            out.put("statements", jdbc.queryForList("SELECT id, statement_id, account_id, currency FROM bank_statements WHERE iso_message_id = ?", existing.get(0)));
            return out;
        }
        List<Camt053Parser.Statement> statements = Camt053Parser.parse(doc, 2);
        java.util.Set<String> guidelines = new java.util.TreeSet<>();
        for (Camt053Parser.Statement s : statements) {
            MarketRegistry.Market market = markets.requireForCurrency(s.currency());
            String g = market.bank().statementGuideline();
            if (g != null && !g.isBlank()) guidelines.add(g);
            MarketRegistry.BankAccount account = markets.bankAccount(s.currency());
            if (!account.number().equals(s.accountId())) {
                throw new Iso20022Schemas.InvalidMessageException("Statement " + s.statementId() + " is for account " + s.accountId()
                        + ", which is not Payport's " + s.currency() + " account in " + market.country());
            }
            if (s.openingMinor() + s.bookedCredits() - s.bookedDebits() != s.closingMinor()) {
                throw new Iso20022Schemas.InvalidMessageException("Statement " + s.statementId() + " does not balance: opening " + s.openingMinor()
                        + " + credits " + s.bookedCredits() + " - debits " + s.bookedDebits() + " != closing " + s.closingMinor());
            }
            Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM bank_statements WHERE account_id = ? AND currency = ? AND statement_id = ?", Integer.class,
                    s.accountId(), s.currency(), s.statementId());
            if (dup != null && dup > 0) throw new IllegalStateException("Statement " + s.statementId() + " was already imported from another file");
        }
        // The market's bank may require its usage guideline (e.g. HVPS+) on top of the base schema.
        for (String g : guidelines) schemas.validateGuideline(g, xml);
        long messageId = storeMessage("IN", Iso20022Schemas.CAMT_053, Xml.max(statements.isEmpty() ? "EMPTY" : statements.get(0).msgId(), 35), null, sha, xml, by);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Camt053Parser.Statement s : statements) results.add(store(messageId, s, by));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("alreadyImported", false);
        out.put("messageId", messageId);
        out.put("statements", results);
        return out;
    }

    private Map<String, Object> store(long messageId, Camt053Parser.Statement s, String by) {
        MarketRegistry.Market market = markets.requireForCurrency(s.currency());
        LocalDateTime now = LocalDateTime.now();
        long id = insert("INSERT INTO bank_statements (iso_message_id, country, entity_code, account_id, currency, statement_id, from_at, to_at, opening_minor, "
                        + "closing_minor, credits_minor, debits_minor, entry_count, imported_by, imported_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                messageId, market.country(), market.legalEntity(), s.accountId(), s.currency(), s.statementId(), ts(s.from()), ts(s.to()),
                s.openingMinor(), s.closingMinor(), s.bookedCredits(), s.bookedDebits(), s.entries().size(), by, Timestamp.valueOf(now));
        Map<String, Integer> counts = new LinkedHashMap<>();
        String provider = "BANK_" + market.country() + "_" + s.currency();
        for (Camt053Parser.Entry e : s.entries()) {
            Trace t = trace(e, s, provider, by);
            counts.merge(t.status(), 1, Integer::sum);
            jdbc.update("INSERT INTO bank_statement_entries (statement_id, entry_no, direction, amount_minor, currency, status, booking_date, value_date, "
                            + "bank_reference, end_to_end_id, counterparty, remittance, trace_status, stl_item_id, ledger_entry_ref, detail) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, e.no(), e.direction(), e.amountMinor(), e.currency(), e.status(), e.bookingDate(), e.valueDate(), e.bankReference(),
                    e.endToEndId(), Xml.max(e.counterparty(), 140), Xml.max(e.remittance(), 500), t.status(), t.itemId(), t.ledgerRef(), Xml.max(t.detail(), 500));
        }
        log.info("[BANK] {} statement {} for {} imported by {}: {}", market.country(), s.statementId(), s.currency(), by, counts);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("statementId", s.statementId());
        out.put("country", market.country());
        out.put("entity", market.legalEntity());
        out.put("currency", s.currency());
        out.put("openingMinor", s.openingMinor());
        out.put("closingMinor", s.closingMinor());
        out.put("trace", counts);
        return out;
    }

    record Trace(String status, Long itemId, String ledgerRef, String detail) {}

    private Trace trace(Camt053Parser.Entry e, Camt053Parser.Statement st, String provider, String by) {
        String currency = st.currency();
        if (!"BOOK".equals(e.status())) return new Trace("NOT_BOOKED", null, null, "entry status " + e.status());
        List<Map<String, Object>> items = e.endToEndId() == null ? List.of()
                : jdbc.queryForList("SELECT id, status, net_minor, currency FROM stl_items WHERE instruction_ref = ?", e.endToEndId());
        if (items.isEmpty() && "CRDT".equals(e.direction()) && providerSettlements != null) {
            var rule = providerSettlements.match(currency, e.counterparty(), e.remittance());
            if (rule.isPresent()) {
                // The bank's own entry reference identifies the money movement; the same line can appear on two
                // statements (intraday, then end of day), so the statement id and line number are only a fallback.
                String key = e.bankReference() != null && !e.bankReference().isBlank()
                        ? st.accountId() + ":BANKREF:" + e.bankReference()
                        : st.accountId() + ":" + st.statementId() + ":" + e.no();
                try {
                    var posted = providerSettlements.book(rule.get(), key, e.amountMinor(), currency, e.bookingDate(), by);
                    return new Trace("PROVIDER_SETTLEMENT", null, posted.entryRef(), rule.get().get("provider_code") + " settlement (rule " + rule.get().get("id") + ")"
                            + (posted.outcome() == zw.co.payport.gateway.ledger.LedgerClient.Outcome.DUPLICATE ? "; already booked from an earlier statement" : ""));
                } catch (RuntimeException ex) {
                    exceptions.raise("MISSING_IN_PAYPORT", provider, null, e.bankReference(), null, e.amountMinor(), currency,
                            rule.get().get("provider_code") + " settlement credit not booked: " + ex.getMessage(), "STATEMENT");
                    return new Trace("LEDGER_PENDING", null, null, rule.get().get("provider_code") + " settlement not booked: " + ex.getMessage());
                }
            }
        }
        if (items.isEmpty()) {
            exceptions.raise("MISSING_IN_PAYPORT", provider, null, e.bankReference(), null, e.amountMinor(), currency,
                    e.direction() + " " + e.amountMinor() + " " + currency + " not traceable to a Payport payout (" + nz(e.remittance()) + ")", "STATEMENT");
            return new Trace("UNIDENTIFIED", null, null, "no payout with end-to-end id " + nz(e.endToEndId()));
        }
        Map<String, Object> item = items.get(0);
        long itemId = ((Number) item.get("id")).longValue();
        long net = ((Number) item.get("net_minor")).longValue();
        String status = (String) item.get("status");
        if (net != e.amountMinor() || !currency.equals(item.get("currency"))) {
            exceptions.raise("AMOUNT_MISMATCH", provider, e.endToEndId(), e.bankReference(), net, e.amountMinor(), currency,
                    "bank booked " + e.amountMinor() + ", payout is " + net, "STATEMENT");
            return new Trace("AMOUNT_MISMATCH", itemId, null, "payout " + net + ", bank " + e.amountMinor());
        }
        String event = "DBIT".equals(e.direction()) ? "PAID" : "RETURNED";
        boolean confirmed = "PAID".equals(event) ? ("PAID".equals(status) || "RETURNED".equals(status)) : "RETURNED".equals(status);
        if (!confirmed) {
            exceptions.raise("STATUS_MISMATCH", provider, e.endToEndId(), e.bankReference(), net, e.amountMinor(), currency,
                    "bank booked the " + ("PAID".equals(event) ? "payout" : "return") + " but the payout is " + status, "STATEMENT");
            return new Trace("NOT_YET_CONFIRMED", itemId, null, "payout status " + status);
        }
        return payoutLedger.ensurePosted(itemId, event)
                .map(ref -> new Trace("TRACED", itemId, ref, event))
                .orElseGet(() -> new Trace("LEDGER_PENDING", itemId, null, "the ledger has not accepted the " + event + " posting yet"));
    }

    // ─── queries ──────────────────────────────────────────────────────────────

    public List<Map<String, Object>> statements() {
        return jdbc.queryForList("SELECT * FROM bank_statements ORDER BY id DESC LIMIT 200");
    }

    public List<Map<String, Object>> entries(long statementId) {
        return jdbc.queryForList("SELECT * FROM bank_statement_entries WHERE statement_id = ? ORDER BY entry_no", statementId);
    }

    public List<Map<String, Object>> messages(String type) {
        return jdbc.queryForList("SELECT id, direction, message_type, msg_id, related_ref, sha256, schema_valid, created_by, created_at FROM iso_messages "
                + "WHERE (? IS NULL OR message_type = ?) ORDER BY id DESC LIMIT 200", type, type);
    }

    public Map<String, Boolean> schemaStatus() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (String s : List.of(Iso20022Schemas.CAMT_053, Iso20022Schemas.PAIN_001, Iso20022Schemas.PAIN_002)) m.put(s, schemas.installed(s));
        m.put("guideline:" + HVPS_PLUS, schemas.guidelineInstalled(HVPS_PLUS));
        return m;
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    long storeMessage(String direction, String type, String msgId, String related, String sha, byte[] xml, String by) {
        List<Long> existing = jdbc.queryForList("SELECT id FROM iso_messages WHERE direction = ? AND message_type = ? AND sha256 = ?", Long.class, direction, type, sha);
        if (!existing.isEmpty()) return existing.get(0);
        return insert("INSERT INTO iso_messages (direction, message_type, msg_id, related_ref, sha256, schema_valid, content, created_by, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, TRUE, ?, ?, ?)", direction, type, Xml.max(msgId == null ? "UNKNOWN" : msgId, 35), related, sha,
                new String(xml, StandardCharsets.UTF_8), by, Timestamp.valueOf(LocalDateTime.now()));
    }

    private long insert(String sql, Object... args) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(c -> {
            var ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    private static Timestamp ts(LocalDateTime t) {
        return t == null ? null : Timestamp.valueOf(t);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
