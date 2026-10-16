package zw.co.payport.gateway.agents;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.payport.gateway.channels.ChannelService;
import zw.co.payport.gateway.ledger.AccountKey;
import zw.co.payport.gateway.ledger.Book;
import zw.co.payport.gateway.ledger.JournalRequest;
import zw.co.payport.gateway.ledger.LedgerCategory;
import zw.co.payport.gateway.ledger.LedgerClient;
import zw.co.payport.gateway.markets.MarketRegistry;
import zw.co.payport.gateway.products.ServiceLineService;
import zw.co.payport.gateway.risk.ComplianceAudit;
import zw.co.payport.gateway.risk.ComplianceParameters;
import zw.co.payport.gateway.wallets.PinVerifier;
import zw.co.payport.gateway.wallets.WalletService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Cash-in / cash-out agents (Phase 8). An agent is an approved merchant that holds e-value float.
 *
 *  - Becoming an agent: the merchant applies; a COMPLIANCE officer approves (agent due diligence).
 *  - Float: the agent deposits cash at PayPort's bank; the deposit is recorded by one person and
 *    approved by another (database CHECK maker ≠ checker, bank reference unique), then posted
 *    Dr BANK_CASH[market account] · Cr AGENT_FLOAT[agent].
 *  - Cash-in: customer gives cash, agent sends e-value: Dr AGENT_FLOAT · Cr CUSTOMER_WALLET.
 *  - Cash-out: the holder makes a one-time code with their PIN; the agent redeems it with the holder's
 *    phone and pays out cash: Dr CUSTOMER_WALLET · Cr AGENT_FLOAT. Only the hash is stored, it is
 *    single-use and expires; wrong codes are counted per agent and redemption blocks after the limit.
 *  - Commission, in the same journal: Dr AGENT_COMMISSION_EXPENSE · Cr AGENT_FLOAT.
 *
 * AGENT_FLOAT may not go negative (ledger rule), so an agent cannot hand out more e-value than it bought.
 * Behind the AGENTS licence switch and the AGENT channel.
 */
@Slf4j
@Service
public class AgentService {

    public static final String LINE = "AGENTS";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final LedgerClient ledger;
    private final MarketRegistry markets;
    private final ServiceLineService lines;
    private final ChannelService channels;
    private final ComplianceParameters params;
    private final ComplianceAudit audit;
    private final WalletService wallets;
    private final PinVerifier pins;
    private final TransactionTemplate tx;

    public AgentService(JdbcTemplate jdbc, LedgerClient ledger, MarketRegistry markets, ServiceLineService lines, ChannelService channels,
                        ComplianceParameters params, ComplianceAudit audit, WalletService wallets, PinVerifier pins, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.markets = markets;
        this.lines = lines;
        this.channels = channels;
        this.params = params;
        this.audit = audit;
        this.wallets = wallets;
        this.pins = pins;
        this.tx = new TransactionTemplate(tm);
    }

    private void requireOn() {
        lines.require(LINE, "The agent network");
        channels.require("AGENT");
    }

    // ─── onboarding ──────────────────────────────────────────────────────────

    public Map<String, Object> apply(long merchantId, String name, String phone, String location, String by) {
        lines.require(LINE, "The agent network");
        if (name == null || name.isBlank() || location == null || location.isBlank()) throw new IllegalArgumentException("Name and location are required");
        MarketRegistry.Market m = markets.forMsisdn(phone).orElseThrow(() -> new IllegalArgumentException("The agent phone must be a mobile number in a country PayPort serves"));
        if (markets.normaliseMobile(phone, m.country()).isEmpty()) throw new IllegalArgumentException("The agent phone is not a valid mobile number");
        if (forMerchant(merchantId).isPresent()) throw new IllegalStateException("This merchant has already applied to be an agent");
        String code = "AG" + digits(8);
        jdbc.update("INSERT INTO agents (agent_code, merchant_id, name, phone, location, country, applied_by, applied_at) VALUES (?,?,?,?,?,?,?,?)",
                code, merchantId, name.trim(), "+" + phone.replaceAll("[^0-9]", ""), location.trim(), m.country(), by, now());
        audit.record(by, "AGENT_APPLIED", "agent:" + code, name);
        return view(forMerchant(merchantId).orElseThrow());
    }

    public Optional<Map<String, Object>> forMerchant(long merchantId) {
        return jdbc.queryForList("SELECT * FROM agents WHERE merchant_id = ?", merchantId).stream().findFirst();
    }

    public Map<String, Object> get(long id) {
        return jdbc.queryForList("SELECT * FROM agents WHERE id = ?", id).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("No such agent"));
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT a.*, m.business_name FROM agents a LEFT JOIN merchants m ON m.id = a.merchant_id ORDER BY a.id DESC LIMIT 500");
        rows.replaceAll(this::view);
        return rows;
    }

    /** The agent row plus its float per currency of its market. */
    public Map<String, Object> view(Map<String, Object> a) {
        Map<String, Object> out = new LinkedHashMap<>(a);
        Map<String, Long> floats = new LinkedHashMap<>();
        markets.market((String) a.get("country")).ifPresent(m -> {
            for (MarketRegistry.Currency c : m.currencies()) floats.put(c.code(), ledger.balance(floatAccount(m.legalEntity(), (String) a.get("agent_code"), c.code())).balance());
        });
        out.put("float", floats);
        return out;
    }

    public Map<String, Object> decide(long id, boolean approve, String reason, Integer commissionBps, String by) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("A reason is required (due-diligence evidence)");
        Map<String, Object> a = get(id);
        if (!"PENDING".equals(a.get("status"))) throw new IllegalStateException("Agent is " + a.get("status"));
        if (by.equals(a.get("applied_by"))) throw new IllegalStateException("The applicant cannot approve their own application");
        jdbc.update("UPDATE agents SET status = ?, status_reason = ?, commission_bps = ?, decided_by = ?, decided_at = ? WHERE id = ?",
                approve ? "ACTIVE" : "REJECTED", reason, commissionBps, by, now(), id);
        audit.record(by, approve ? "AGENT_APPROVED" : "AGENT_REJECTED", "agent:" + a.get("agent_code"), reason);
        return view(get(id));
    }

    public Map<String, Object> setStatus(long id, String status, String reason, String by) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("A reason is required");
        String s = status == null ? "" : status.toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "SUSPENDED").contains(s)) throw new IllegalArgumentException("status must be ACTIVE or SUSPENDED");
        Map<String, Object> a = get(id);
        if ("PENDING".equals(a.get("status")) || "REJECTED".equals(a.get("status"))) throw new IllegalStateException("Agent is " + a.get("status"));
        jdbc.update("UPDATE agents SET status = ?, status_reason = ?, decided_by = ?, decided_at = ? WHERE id = ?", s, reason, by, now(), id);
        audit.record(by, "AGENT_" + s, "agent:" + a.get("agent_code"), reason);
        return view(get(id));
    }

    // ─── float ───────────────────────────────────────────────────────────────

    public Map<String, Object> recordDeposit(long agentId, long amount, String currency, String bankReference, String by) {
        Map<String, Object> a = get(agentId);
        if (!"ACTIVE".equals(a.get("status"))) throw new IllegalStateException("Agent is " + a.get("status"));
        if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");
        if (bankReference == null || bankReference.isBlank()) throw new IllegalArgumentException("The bank deposit reference is required");
        String ccy = currency(currency, (String) a.get("country"));
        try {
            jdbc.update("INSERT INTO agent_float_deposits (agent_id, amount_minor, currency, bank_reference, created_by, created_at) VALUES (?,?,?,?,?,?)",
                    agentId, amount, ccy, bankReference.trim(), by, now());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalStateException("Bank reference " + bankReference + " has already been used for a float deposit");
        }
        return jdbc.queryForMap("SELECT * FROM agent_float_deposits WHERE bank_reference = ? AND currency = ?", bankReference.trim(), ccy);
    }

    public List<Map<String, Object>> deposits(Long agentId, String status) {
        StringBuilder sql = new StringBuilder("SELECT d.*, a.agent_code, a.name FROM agent_float_deposits d JOIN agents a ON a.id = d.agent_id WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (agentId != null) { sql.append(" AND d.agent_id = ?"); args.add(agentId); }
        if (status != null && !status.isBlank()) { sql.append(" AND d.status = ?"); args.add(status.toUpperCase(Locale.ROOT)); }
        sql.append(" ORDER BY d.id DESC LIMIT 500");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> decideDeposit(long depositId, boolean approve, String note, String by) {
        return tx.execute(s -> {
            Map<String, Object> d = jdbc.queryForMap("SELECT * FROM agent_float_deposits WHERE id = ? FOR UPDATE", depositId);
            if (!"PENDING".equals(d.get("status"))) throw new IllegalStateException("Deposit is " + d.get("status"));
            if (by.equals(d.get("created_by"))) throw new IllegalStateException("The person who recorded the deposit cannot approve it");
            Map<String, Object> a = get(((Number) d.get("agent_id")).longValue());
            String ccy = (String) d.get("currency");
            long amount = ((Number) d.get("amount_minor")).longValue();
            if (!approve) {
                jdbc.update("UPDATE agent_float_deposits SET status = 'REJECTED', approved_by = ?, approved_at = ?, note = ? WHERE id = ?", by, now(), note, depositId);
                audit.record(by, "AGENT_FLOAT_REJECTED", "agent:" + a.get("agent_code"), d.get("bank_reference") + ": " + note);
                return jdbc.queryForMap("SELECT * FROM agent_float_deposits WHERE id = ?", depositId);
            }
            MarketRegistry.Market m = markets.requireForCurrency(ccy);
            MarketRegistry.BankAccount bank = markets.bankAccount(ccy);
            if (bank == null || bank.ledgerOwner() == null) throw new IllegalStateException("No PayPort bank account is configured for " + ccy);
            JournalRequest j = JournalRequest.builder("AGENT_FLOAT:" + depositId).entity(m.legalEntity()).book(Book.LIVE).template("AGENT_FLOAT_DEPOSIT", 1)
                    .effectiveDate(java.time.LocalDate.now()).description("Agent float " + a.get("agent_code") + " bank ref " + d.get("bank_reference"))
                    .source("agent_float_deposit", depositId).postedBy(by)
                    .debit(AccountKey.of(m.legalEntity(), Book.LIVE, LedgerCategory.BANK_CASH, bank.ledgerOwner(), ccy), amount, "agent deposit")
                    .credit(floatAccount(m.legalEntity(), (String) a.get("agent_code"), ccy), amount, "agent float").build();
            String entry = ledger.post(j).entryRef();
            jdbc.update("UPDATE agent_float_deposits SET status = 'APPROVED', approved_by = ?, approved_at = ?, note = ?, entry_ref = ? WHERE id = ?",
                    by, now(), note, entry, depositId);
            audit.record(by, "AGENT_FLOAT_APPROVED", "agent:" + a.get("agent_code"), d.get("bank_reference") + " " + ccy + " " + amount);
            return jdbc.queryForMap("SELECT * FROM agent_float_deposits WHERE id = ?", depositId);
        });
    }

    // ─── cash-in / cash-out ─────────────────────────────────────────────────

    public Map<String, Object> cashIn(long merchantId, String customer, String currency, long amount, String clientRef, String by) {
        requireOn();
        wallets.requireOn();
        Map<String, Object> a = activeAgent(merchantId);
        if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");
        String ccy = currency(currency, (String) a.get("country"));
        WalletService.Wallet w = wallets.forPhone(customer, ccy).or(() -> wallets.byRef(customer))
                .orElseThrow(() -> new IllegalArgumentException("No PayPort " + ccy + " wallet for " + customer));
        String code = (String) a.get("agent_code");
        String opRef = "CASHIN:" + code + ":" + (clientRef == null || clientRef.isBlank() ? token() : clientRef.trim());
        long commission = commission(a, "CASH_IN", amount);
        JournalRequest.Builder b = wallets.journal(opRef, w.entity(), "AGENT_CASH_IN", "Cash-in at " + code + " to " + w.ref(), "agent_transaction", opRef, by)
                .debit(floatAccount(w.entity(), code, ccy), amount, "e-value sent")
                .credit(w.account(), amount, "cash-in");
        if (commission > 0) b.debit(commissionAccount(w.entity(), ccy), commission, "agent commission").credit(floatAccount(w.entity(), code, ccy), commission, "commission");
        return tx.execute(s -> {
            String entry = wallets.apply(opRef, b.build(), List.of(new WalletService.Leg(w.id(), "CASH_IN", "CR", amount, code, opRef)), by,
                    "Not enough agent float. Buy more float first.");
            record(opRef, a, "CASH_IN", w, amount, ccy, commission, entry, by);
            return result(opRef, a, w, amount, ccy, commission, entry);
        });
    }

    public Map<String, Object> issueCashOutCode(String phone, String pin, String currency, long amount) {
        requireOn();
        wallets.requireOn();
        var user = pins.verify(phone, pin);
        String ccy = currency == null ? "" : currency.toUpperCase(Locale.ROOT);
        WalletService.Wallet w = wallets.forUser(user.getId(), ccy).orElseThrow(() -> new IllegalArgumentException("You have no " + ccy + " wallet"));
        if (!"ACTIVE".equals(w.status())) throw new IllegalStateException("Your wallet is " + w.status());
        if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");
        if (wallets.balance(w) < amount) throw new zw.co.payport.gateway.platform.Web.InsufficientFundsException("Not enough money in your wallet");
        int ttl = (int) params.number("WALLET.CASH_OUT_CODE_TTL_MINUTES");
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = digits(8);
            try {
                LocalDateTime exp = LocalDateTime.now().plusMinutes(ttl);
                jdbc.update("INSERT INTO cash_out_codes (code_hash, wallet_id, amount_minor, currency, expires_at, created_at) VALUES (?,?,?,?,?,?)",
                        hash(w.phone(), code), w.id(), amount, ccy, Timestamp.valueOf(exp), now());
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("code", code);
                out.put("amountMinor", amount);
                out.put("currency", ccy);
                out.put("expiresAt", exp.toString());
                out.put("note", "Give this code and your phone number to a PayPort agent. It works once.");
                return out;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // astronomically unlikely; try another code
            }
        }
        throw new IllegalStateException("Could not create a code, please try again");
    }

    public List<Map<String, Object>> cashOutCodes(String phone) {
        return jdbc.queryForList("SELECT c.id, c.amount_minor, c.currency, IF(c.status = 'ISSUED' AND c.expires_at < NOW(6), 'EXPIRED', c.status) AS status, "
                + "c.expires_at, c.created_at, c.redeemed_at FROM cash_out_codes c JOIN wallets w ON w.id = c.wallet_id WHERE w.phone = ? ORDER BY c.id DESC LIMIT 50", phone);
    }

    public Map<String, Object> cashOut(long merchantId, String customerPhone, String code, String by) {
        requireOn();
        wallets.requireOn();
        Map<String, Object> a = activeAgent(merchantId);
        long agentId = ((Number) a.get("id")).longValue();
        int max = (int) params.number("AGENT.MAX_CASH_OUT_FAILURES_PER_HOUR");
        Integer failures = jdbc.queryForObject("SELECT COUNT(*) FROM cash_out_attempts WHERE agent_id = ? AND success = FALSE AND at > ?",
                Integer.class, agentId, Timestamp.valueOf(LocalDateTime.now().minusHours(1)));
        if (failures != null && failures >= max) throw new SecurityException("Too many wrong cash-out codes. Try again later or contact PayPort.");
        String phone = "+" + (customerPhone == null ? "" : customerPhone.replaceAll("[^0-9]", ""));
        String h = hash(phone, code == null ? "" : code.trim());
        Map<String, Object> result = tx.execute(s -> {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM cash_out_codes WHERE code_hash = ? FOR UPDATE", h);
            if (rows.isEmpty() || !"ISSUED".equals(rows.get(0).get("status"))) return null;
            Map<String, Object> c = rows.get(0);
            if (((Timestamp) c.get("expires_at")).toLocalDateTime().isBefore(LocalDateTime.now())) {
                jdbc.update("UPDATE cash_out_codes SET status = 'EXPIRED' WHERE id = ?", c.get("id"));
                return Map.<String, Object>of("expired", true);
            }
            WalletService.Wallet w = wallets.get(((Number) c.get("wallet_id")).longValue());
            String ccy = (String) c.get("currency");
            if (!markets.requireForCurrency(ccy).country().equals(a.get("country"))) throw new IllegalArgumentException("This code is for another country");
            long amount = ((Number) c.get("amount_minor")).longValue();
            String agentCode = (String) a.get("agent_code");
            String opRef = "CASHOUT:" + c.get("id");
            long commission = commission(a, "CASH_OUT", amount);
            JournalRequest.Builder b = wallets.journal(opRef, w.entity(), "AGENT_CASH_OUT", "Cash-out at " + agentCode + " from " + w.ref(), "agent_transaction", opRef, by)
                    .debit(w.account(), amount, "cash-out")
                    .credit(floatAccount(w.entity(), agentCode, ccy), amount, "e-value received");
            if (commission > 0) b.debit(commissionAccount(w.entity(), ccy), commission, "agent commission").credit(floatAccount(w.entity(), agentCode, ccy), commission, "commission");
            String entry = wallets.apply(opRef, b.build(), List.of(new WalletService.Leg(w.id(), "CASH_OUT", "DR", amount, agentCode, opRef)), by,
                    "The customer's wallet no longer has enough money for this code");
            jdbc.update("UPDATE cash_out_codes SET status = 'REDEEMED', redeemed_by_agent = ?, redeemed_at = ? WHERE id = ?", agentId, now(), c.get("id"));
            record(opRef, a, "CASH_OUT", w, amount, ccy, commission, entry, by);
            return result(opRef, a, w, amount, ccy, commission, entry);
        });
        jdbc.update("INSERT INTO cash_out_attempts (agent_id, success, at) VALUES (?,?,?)", agentId, result != null && !result.containsKey("expired"), now());
        if (result == null) throw new IllegalArgumentException("Wrong code or phone number, or the code was already used");
        if (result.containsKey("expired")) throw new IllegalStateException("This code has expired. Ask the customer for a new one.");
        return result;
    }

    public List<Map<String, Object>> transactions(long agentId) {
        return jdbc.queryForList("SELECT t.op_ref, t.kind, t.amount_minor, t.currency, t.commission_minor, t.entry_ref, t.created_at, w.wallet_ref "
                + "FROM agent_transactions t JOIN wallets w ON w.id = t.wallet_id WHERE t.agent_id = ? ORDER BY t.id DESC LIMIT 500", agentId);
    }

    public List<Map<String, Object>> commissions(long agentId) {
        return jdbc.queryForList("SELECT DATE_FORMAT(created_at, '%Y-%m') AS month, currency, kind, COUNT(*) AS operations, SUM(amount_minor) AS volume_minor, "
                + "SUM(commission_minor) AS commission_minor FROM agent_transactions WHERE agent_id = ? GROUP BY month, currency, kind ORDER BY month DESC, currency, kind", agentId);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    Map<String, Object> activeAgent(long merchantId) {
        Map<String, Object> a = forMerchant(merchantId).orElseThrow(() -> new IllegalArgumentException("This merchant is not a PayPort agent"));
        if (!"ACTIVE".equals(a.get("status"))) throw new IllegalStateException("Agent status is " + a.get("status"));
        return a;
    }

    long commission(Map<String, Object> agent, String kind, long amount) {
        Object own = agent.get("commission_bps");
        long bps;
        if (own != null) {
            bps = ((Number) own).longValue();
        } else {
            JsonNode n = params.get("AGENT.COMMISSION_BPS").path(kind);
            bps = n.isMissingNode() ? 0 : n.asLong();
        }
        return amount * bps / 10_000;
    }

    private void record(String opRef, Map<String, Object> a, String kind, WalletService.Wallet w, long amount, String ccy, long commission, String entry, String by) {
        jdbc.update("INSERT INTO agent_transactions (op_ref, agent_id, kind, wallet_id, amount_minor, currency, commission_minor, entry_ref, commission_entry_ref, created_by, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE op_ref = op_ref",
                opRef, a.get("id"), kind, w.id(), amount, ccy, commission, entry, commission > 0 ? entry : null, by, now());
    }

    private Map<String, Object> result(String opRef, Map<String, Object> a, WalletService.Wallet w, long amount, String ccy, long commission, String entry) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("opRef", opRef);
        out.put("wallet", w.ref());
        out.put("amountMinor", amount);
        out.put("currency", ccy);
        out.put("commissionMinor", commission);
        out.put("entryRef", entry);
        out.put("floatMinor", ledger.balance(floatAccount(w.entity(), (String) a.get("agent_code"), ccy)).balance());
        return out;
    }

    private String currency(String currency, String country) {
        String ccy = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        MarketRegistry.Market m = markets.require(country);
        if (m.currency(ccy).isEmpty()) throw new IllegalArgumentException(ccy + " is not a currency of " + m.name());
        if (!markets.liveCurrency(ccy)) throw new IllegalArgumentException(ccy + " is not live at the moment");
        return ccy;
    }

    public static AccountKey floatAccount(String entity, String agentCode, String ccy) {
        return AccountKey.of(entity, Book.LIVE, LedgerCategory.AGENT_FLOAT, agentCode, ccy);
    }

    static AccountKey commissionAccount(String entity, String ccy) {
        return AccountKey.of(entity, Book.LIVE, LedgerCategory.AGENT_COMMISSION_EXPENSE, "AGENTS", ccy);
    }

    static String hash(String phone, String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((phone + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String digits(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(RANDOM.nextInt(10));
        return b.toString();
    }

    static String token() {
        byte[] b = new byte[9];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b).toUpperCase(Locale.ROOT);
    }

    static Timestamp now() {
        return Timestamp.valueOf(LocalDateTime.now());
    }
}
