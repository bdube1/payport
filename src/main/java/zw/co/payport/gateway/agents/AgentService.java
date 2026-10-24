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
import zw.co.payport.gateway.platform.PartyCodes;
import zw.co.payport.gateway.rules.PartyRules;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Cash-in / cash-out agents. An agent is its own kind of business, separate from merchants: it
 * provides cash-in and cash-out, holds cash and e-float, and earns commission. A merchant is never
 * an agent by default. Agents log in to their own portal (AgentAuthService).
 *
 *  - Becoming an agent: ops onboards it (live at once), or it applies and compliance approves.
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

    @org.springframework.beans.factory.annotation.Autowired
    private PartyCodes codes;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PartyRules rules;

    private void requireOn() {
        lines.require(LINE, "The agent network");
        channels.require("AGENT");
    }

    // ─── onboarding ──────────────────────────────────────────────────────────

    /** Public application from the agent portal: PENDING until compliance decides. Returns the application reference. */
    public Map<String, Object> apply(String name, String contactPerson, String phone, String email, String location, String idNumber, String by) {
        Fields f = fields(name, contactPerson, phone, email, location, idNumber);
        String code = "AG" + digits(8);
        String ref = "AGT-" + LocalDate.now().toString().replace("-", "") + "-" + digits(6);
        try {
            jdbc.update("INSERT INTO agents (agent_code, application_reference, name, contact_person, phone, email, id_number, location, country, applied_by, applied_at) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?)", code, ref, f.name, f.contact, f.phone, f.email, f.idNumber, f.location, f.country, by, now());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalStateException("An agent with this phone number already exists");
        }
        codes.allocate(PartyCodes.AGENT, ((Number) byCode(code).orElseThrow().get("id")).longValue());
        audit.record(by, "AGENT_APPLIED", "agent:" + code, f.name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applicationReference", ref);
        out.put("status", "PENDING");
        out.put("name", f.name);
        return out;
    }

    /** Status only, by application reference (references are guessable, so nothing else is shown). */
    public Map<String, Object> track(String reference) {
        Map<String, Object> a = jdbc.queryForList("SELECT status, applied_at, decided_at FROM agents WHERE application_reference = ?",
                reference == null ? "" : reference.trim().toUpperCase(Locale.ROOT)).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No application with that reference"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applicationReference", reference.trim().toUpperCase(Locale.ROOT));
        out.put("status", a.get("status"));
        out.put("appliedAt", a.get("applied_at"));
        out.put("decidedAt", a.get("decided_at"));
        return out;
    }

    private record Fields(String name, String contact, String phone, String email, String idNumber, String location, String country) {}

    private Fields fields(String name, String contactPerson, String phone, String email, String location, String idNumber) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("The agent's business name is required");
        if (location == null || location.isBlank()) throw new IllegalArgumentException("The location is required");
        if (phone == null || phone.isBlank()) throw new IllegalArgumentException("The mobile number is required");
        MarketRegistry.Market m = markets.forMsisdn(phone).orElseThrow(() -> new IllegalArgumentException("Enter the mobile number with its country code, e.g. +263771234567"));
        if (markets.normaliseMobile(phone, m.country()).isEmpty()) throw new IllegalArgumentException("The mobile number is not valid");
        String e = email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
        if (e != null && !e.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw new IllegalArgumentException("The email address is not valid");
        return new Fields(name.trim(), blank(contactPerson), "+" + phone.replaceAll("[^0-9]", ""), e, blank(idNumber), location.trim(), m.country());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public Optional<Map<String, Object>> byCode(String agentCode) {
        return jdbc.queryForList("SELECT * FROM agents WHERE agent_code = ?", agentCode).stream().findFirst();
    }

    public Map<String, Object> get(long id) {
        return jdbc.queryForList("SELECT * FROM agents WHERE id = ?", id).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("No such agent"));
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT a.* FROM agents a ORDER BY a.id DESC LIMIT 500");
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
        if (reason == null || reason.isBlank()) reason = approve ? "Approved from the ops console" : "Rejected from the ops console";
        Map<String, Object> a = get(id);
        if (!"PENDING".equals(a.get("status"))) throw new IllegalStateException("Agent is " + a.get("status"));
        if (by.equals(a.get("applied_by"))) throw new IllegalStateException("The applicant cannot approve their own application");
        jdbc.update("UPDATE agents SET status = ?, status_reason = ?, commission_bps = ?, decided_by = ?, decided_at = ? WHERE id = ?",
                approve ? "ACTIVE" : "REJECTED", reason, commissionBps, by, now(), id);
        audit.record(by, approve ? "AGENT_APPROVED" : "AGENT_REJECTED", "agent:" + a.get("agent_code"), reason);
        return view(get(id));
    }

    public Map<String, Object> setStatus(long id, String status, String reason, String by) {
        String s = status == null ? "" : status.toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "SUSPENDED").contains(s)) throw new IllegalArgumentException("status must be ACTIVE or SUSPENDED");
        Map<String, Object> a = get(id);
        if ("PENDING".equals(a.get("status")) || "REJECTED".equals(a.get("status"))) throw new IllegalStateException("Agent is " + a.get("status"));
        if (reason == null || reason.isBlank()) reason = ("ACTIVE".equals(s) ? "Reinstated" : "Suspended") + " from the ops console";
        jdbc.update("UPDATE agents SET status = ?, status_reason = ?, decided_by = ?, decided_at = ? WHERE id = ?", s, reason, by, now(), id);
        audit.record(by, "AGENT_" + s, "agent:" + a.get("agent_code"), reason);
        return view(get(id));
    }

    /** Ops onboarding: a new agent, live at once, with its agent code. */
    public Map<String, Object> onboard(String name, String contactPerson, String phone, String email, String location, String idNumber,
                                       Integer commissionBps, String by) {
        Fields f = fields(name, contactPerson, phone, email, location, idNumber);
        if (commissionBps != null && (commissionBps < 0 || commissionBps > 1000)) throw new IllegalArgumentException("Commission must be between 0 and 1000 basis points");
        String code = "AG" + digits(8);
        Timestamp now = now();
        try {
            jdbc.update("INSERT INTO agents (agent_code, name, contact_person, phone, email, id_number, location, country, status, status_reason, commission_bps, "
                            + "applied_by, applied_at, decided_by, decided_at) VALUES (?,?,?,?,?,?,?,?, 'ACTIVE', 'Onboarded by PayPort', ?,?,?,?,?)",
                    code, f.name, f.contact, f.phone, f.email, f.idNumber, f.location, f.country, commissionBps, by, now, by, now);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalStateException("An agent with this phone number already exists");
        }
        codes.allocate(PartyCodes.AGENT, ((Number) byCode(code).orElseThrow().get("id")).longValue());
        audit.record(by, "AGENT_ONBOARDED", "agent:" + code, f.name);
        return view(byCode(code).orElseThrow());
    }

    public Map<String, Object> update(long id, String name, String contactPerson, String phone, String email, String location, String idNumber,
                                      Integer commissionBps, String by) {
        Map<String, Object> a = get(id);
        Fields f = fields(name == null || name.isBlank() ? (String) a.get("name") : name,
                contactPerson == null ? (String) a.get("contact_person") : contactPerson,
                phone == null || phone.isBlank() ? (String) a.get("phone") : phone,
                email == null ? (String) a.get("email") : email,
                location == null || location.isBlank() ? (String) a.get("location") : location,
                idNumber == null ? (String) a.get("id_number") : idNumber);
        if (commissionBps != null && (commissionBps < 0 || commissionBps > 1000)) throw new IllegalArgumentException("Commission must be between 0 and 1000 basis points");
        try {
            jdbc.update("UPDATE agents SET name = ?, contact_person = ?, phone = ?, email = ?, id_number = ?, location = ?, commission_bps = ? WHERE id = ?",
                    f.name, f.contact, f.phone, f.email, f.idNumber, f.location, commissionBps, id);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalStateException("Another agent already uses this phone number");
        }
        audit.record(by, "AGENT_UPDATED", "agent:" + a.get("agent_code"), f.name + " / " + f.location + " / " + commissionBps);
        return view(get(id));
    }

    /** One agent with its float, recent transactions, commissions and float deposits. */
    public Map<String, Object> detail(long id) {
        Map<String, Object> out = new LinkedHashMap<>(view(get(id)));
        out.put("transactions", transactions(id));
        out.put("deposits", deposits(id, null));
        out.put("totals", jdbc.queryForList("SELECT kind, currency, COUNT(*) AS count, SUM(amount_minor) AS amount_minor, SUM(commission_minor) AS commission_minor "
                + "FROM agent_transactions WHERE agent_id = ? GROUP BY kind, currency ORDER BY kind, currency", id));
        return out;
    }

    /** Network-wide figures for the agents dashboard. */
    public Map<String, Object> dashboard() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byStatus", jdbc.queryForList("SELECT status, COUNT(*) AS count FROM agents GROUP BY status ORDER BY status"));
        out.put("float", jdbc.queryForList("SELECT a.currency, COUNT(*) AS accounts, COALESCE(SUM(b.balance), 0) AS balance_minor FROM ledger_accounts a "
                + "LEFT JOIN ledger_balances b ON b.account_id = a.id WHERE a.category = 'AGENT_FLOAT' AND a.book = 'LIVE' GROUP BY a.currency ORDER BY a.currency"));
        Timestamp today = Timestamp.valueOf(LocalDate.now().atStartOfDay());
        Timestamp month = Timestamp.valueOf(LocalDate.now().minusDays(30).atStartOfDay());
        out.put("today", jdbc.queryForList("SELECT kind, currency, COUNT(*) AS count, SUM(amount_minor) AS amount_minor, SUM(commission_minor) AS commission_minor "
                + "FROM agent_transactions WHERE created_at >= ? GROUP BY kind, currency ORDER BY kind, currency", today));
        out.put("last30Days", jdbc.queryForList("SELECT kind, currency, COUNT(*) AS count, SUM(amount_minor) AS amount_minor, SUM(commission_minor) AS commission_minor "
                + "FROM agent_transactions WHERE created_at >= ? GROUP BY kind, currency ORDER BY kind, currency", month));
        out.put("topAgents", jdbc.queryForList("SELECT a.id, a.code, a.agent_code, a.name, a.location, t.currency, COUNT(*) AS count, SUM(t.amount_minor) AS amount_minor, "
                + "SUM(t.commission_minor) AS commission_minor FROM agent_transactions t JOIN agents a ON a.id = t.agent_id WHERE t.created_at >= ? "
                + "GROUP BY a.id, a.code, a.agent_code, a.name, a.location, t.currency ORDER BY amount_minor DESC LIMIT 10", month));
        out.put("pendingApplications", jdbc.queryForObject("SELECT COUNT(*) FROM agents WHERE status = 'PENDING'", Integer.class));
        out.put("pendingDeposits", jdbc.queryForObject("SELECT COUNT(*) FROM agent_float_deposits WHERE status = 'PENDING'", Integer.class));
        return out;
    }

    /** The agent's own dashboard: float, today's and the last 30 days' operations, commission this month, recent operations. */
    public Map<String, Object> agentDashboard(long agentId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agent", view(get(agentId)));
        Timestamp today = Timestamp.valueOf(LocalDate.now().atStartOfDay());
        Timestamp month = Timestamp.valueOf(LocalDate.now().minusDays(30).atStartOfDay());
        Timestamp monthStart = Timestamp.valueOf(LocalDate.now().withDayOfMonth(1).atStartOfDay());
        String sql = "SELECT kind, currency, COUNT(*) AS count, SUM(amount_minor) AS amount_minor, SUM(commission_minor) AS commission_minor "
                + "FROM agent_transactions WHERE agent_id = ? AND created_at >= ? GROUP BY kind, currency ORDER BY kind, currency";
        out.put("today", jdbc.queryForList(sql, agentId, today));
        out.put("last30Days", jdbc.queryForList(sql, agentId, month));
        out.put("commissionThisMonth", jdbc.queryForList("SELECT currency, SUM(commission_minor) AS commission_minor FROM agent_transactions "
                + "WHERE agent_id = ? AND created_at >= ? GROUP BY currency ORDER BY currency", agentId, monthStart));
        out.put("pendingDeposits", jdbc.queryForObject("SELECT COUNT(*) FROM agent_float_deposits WHERE agent_id = ? AND status = 'PENDING'", Integer.class, agentId));
        List<Map<String, Object>> recent = transactions(agentId);
        out.put("recent", recent.size() > 10 ? recent.subList(0, 10) : recent);
        return out;
    }

    /** What an agent may see about a customer before cash-in: that the wallet exists, and a masked name. */
    public Map<String, Object> customer(long agentId, String customer, String currency) {
        Map<String, Object> a = activeAgent(agentId);
        String ccy = currency(currency, (String) a.get("country"));
        WalletService.Wallet w = wallets.forPhone(customer, ccy).or(() -> wallets.byRef(customer))
                .orElseThrow(() -> new IllegalArgumentException("No PayPort " + ccy + " wallet for " + customer));
        String name = jdbc.queryForList("SELECT full_name FROM users WHERE id = ?", String.class, w.userId()).stream().findFirst().orElse("");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wallet", w.ref());
        out.put("currency", w.currency());
        out.put("status", w.status());
        out.put("name", mask(name));
        return out;
    }

    static String mask(String fullName) {
        StringBuilder b = new StringBuilder();
        for (String p : fullName.trim().split("\\s+")) {
            if (p.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(b.length() == 0 ? p : p.charAt(0) + "***");
        }
        return b.toString();
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
        StringBuilder sql = new StringBuilder("SELECT d.*, a.code, a.agent_code, a.name FROM agent_float_deposits d JOIN agents a ON a.id = d.agent_id WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (agentId != null) { sql.append(" AND d.agent_id = ?"); args.add(agentId); }
        if (status != null && !status.isBlank()) { sql.append(" AND d.status = ?"); args.add(status.toUpperCase(Locale.ROOT)); }
        sql.append(" ORDER BY d.id DESC LIMIT 500");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> decideDeposit(long depositId, boolean approve, String note, String by) {
        if (approve) {   // open the two accounts before the deposit row is locked
            Map<String, Object> d0 = jdbc.queryForMap("SELECT agent_id, currency FROM agent_float_deposits WHERE id = ?", depositId);
            Map<String, Object> a0 = get(((Number) d0.get("agent_id")).longValue());
            String c0 = (String) d0.get("currency");
            MarketRegistry.Market m0 = markets.requireForCurrency(c0);
            MarketRegistry.BankAccount bank0 = markets.bankAccount(c0);
            if (bank0 != null && bank0.ledgerOwner() != null) {
                ledger.openAccount(AccountKey.of(m0.legalEntity(), Book.LIVE, LedgerCategory.BANK_CASH, bank0.ledgerOwner(), c0), by);
            }
            ledger.openAccount(floatAccount(m0.legalEntity(), (String) a0.get("agent_code"), c0), by);
        }
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
            String entry = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c -> ledger.postInTransaction(c, j).entryRef());
            jdbc.update("UPDATE agent_float_deposits SET status = 'APPROVED', approved_by = ?, approved_at = ?, note = ?, entry_ref = ? WHERE id = ?",
                    by, now(), note, entry, depositId);
            audit.record(by, "AGENT_FLOAT_APPROVED", "agent:" + a.get("agent_code"), d.get("bank_reference") + " " + ccy + " " + amount);
            return jdbc.queryForMap("SELECT * FROM agent_float_deposits WHERE id = ?", depositId);
        });
    }

    // ─── cash-in / cash-out ─────────────────────────────────────────────────

    public Map<String, Object> cashIn(long agentId, String customer, String currency, long amount, String clientRef, String by) {
        return cashIn(agentId, customer, currency, amount, clientRef, by, "AGENT");
    }

    /** Cash-in: the customer gives the agent cash and the agent sends the same value from its float to the customer's wallet. */
    public Map<String, Object> cashIn(long agentId, String customer, String currency, long amount, String clientRef, String by, String channel) {
        requireOn();
        wallets.requireOn();
        Map<String, Object> a = activeAgent(agentId);
        if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");
        String ccy = currency(currency, (String) a.get("country"));
        WalletService.Wallet w = wallets.forPhone(customer, ccy).or(() -> wallets.byRef(customer))
                .orElseThrow(() -> new IllegalArgumentException("No PayPort " + ccy + " wallet for " + customer));
        String code = (String) a.get("agent_code");
        String opRef = "CASHIN:" + code + ":" + (clientRef == null || clientRef.isBlank() ? token() : clientRef.trim());
        boolean replay = isDone(opRef);
        PartyRules.Charge agentCharge = replay ? PartyRules.Charge.NONE : agentCheck(agentId, "CASH_IN", ccy, channel, amount);
        long commission = commission(a, "CASH_IN", amount);
        JournalRequest.Builder b = wallets.journal(opRef, w.entity(), "AGENT_CASH_IN", "Cash-in at " + code + " to " + w.ref(), "agent_transaction", opRef, by)
                .debit(floatAccount(w.entity(), code, ccy), amount + agentCharge.total(), "e-value sent")
                .credit(w.account(), amount, "cash-in");
        withAgentCharge(b, w.entity(), ccy, agentCharge);
        if (commission > 0) b.debit(commissionAccount(w.entity(), ccy), commission, "agent commission").credit(floatAccount(w.entity(), code, ccy), commission, "commission");
        wallets.prepareAccounts(floatAccount(w.entity(), code, ccy), commissionAccount(w.entity(), ccy), w.account(),
                agentFeeAccount(w.entity(), ccy), agentTaxAccount(w.entity(), ccy));
        Map<String, Object> done = tx.execute(s -> {
            String entry = wallets.apply(opRef, b.build(), List.of(new WalletService.Leg(w.id(), "CASH_IN", "CR", amount, agentLabel(a), opRef)), by,
                    "Not enough agent float. Buy more float first.");
            record(opRef, a, "CASH_IN", w, amount, ccy, commission, entry, by);
            return result(opRef, a, w, amount, ccy, commission, entry);
        });
        if (!replay && rules != null) rules.record("AGENT", String.valueOf(agentId), "CASH_IN", ccy, channel, amount, agentCharge, opRef);
        done.put("agentChargeMinor", agentCharge.total());
        return withFloat(done, a);
    }

    /** What a customer is shown before cashing out: the agent behind a six-digit agent code (active agents only). */
    public Map<String, Object> lookup(String agentCode) {
        long id = codes.resolve(PartyCodes.AGENT, agentCode).orElseThrow(() -> new IllegalArgumentException("No agent with code " + agentCode));
        Map<String, Object> a = get(id);
        if (!"ACTIVE".equals(a.get("status"))) throw new IllegalStateException("This agent cannot serve customers at the moment");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", a.get("code"));
        out.put("name", a.get("name"));
        out.put("location", a.get("location"));
        return out;
    }

    /**
     * Cash-out at an agent, started by the customer: the customer types the agent's six-digit code and the
     * amount, sees the agent's name, and confirms with the wallet PIN (checked by the caller). The value moves
     * at once from the customer's wallet to the agent's float; the agent then pays out the cash.
     */
    public Map<String, Object> customerCashOut(zw.co.payport.gateway.entity.User holder, String agentCode, String currency, long amount, String clientRef, String channel) {
        requireOn();
        wallets.requireOn();
        long agentId = codes.resolve(PartyCodes.AGENT, agentCode).orElseThrow(() -> new IllegalArgumentException("No agent with code " + agentCode));
        Map<String, Object> a = activeAgent(agentId);
        if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");
        String ccy = currency(currency, (String) a.get("country"));
        WalletService.Wallet w = wallets.forUser(holder.getId(), ccy).orElseThrow(() -> new IllegalArgumentException("You have no " + ccy + " wallet"));
        if (!"ACTIVE".equals(w.status())) throw new IllegalStateException("Your wallet is " + w.status());
        String code = (String) a.get("agent_code");
        String opRef = "CASHOUT:" + holder.getId() + ":" + (clientRef == null || clientRef.isBlank() ? token() : clientRef.trim());
        boolean replay = isDone(opRef);
        PartyRules.Charge customerCharge = replay ? PartyRules.Charge.NONE : wallets.walletCharge(holder.getId(), "CASH_OUT", ccy, channel, amount);
        PartyRules.Charge agentCharge = replay ? PartyRules.Charge.NONE : agentCheck(agentId, "CASH_OUT", ccy, channel, amount);
        long commission = commission(a, "CASH_OUT", amount);
        JournalRequest.Builder b = wallets.journal(opRef, w.entity(), "AGENT_CASH_OUT", "Cash-out at " + code + " from " + w.ref(), "agent_transaction", opRef,
                        "customer:" + holder.getPhone())
                .debit(w.account(), amount + customerCharge.total(), "cash-out")
                .credit(floatAccount(w.entity(), code, ccy), amount, "e-value received");
        WalletService.withCharge(b, w.entity(), ccy, customerCharge);
        if (agentCharge.total() > 0) {
            b.debit(floatAccount(w.entity(), code, ccy), agentCharge.total(), "agent charge");
            withAgentCharge(b, w.entity(), ccy, agentCharge);
        }
        if (commission > 0) b.debit(commissionAccount(w.entity(), ccy), commission, "agent commission").credit(floatAccount(w.entity(), code, ccy), commission, "commission");
        wallets.prepareAccounts(w.account(), floatAccount(w.entity(), code, ccy), commissionAccount(w.entity(), ccy), WalletService.feeAccount(w.entity(), ccy),
                WalletService.taxAccount(w.entity(), ccy), agentFeeAccount(w.entity(), ccy), agentTaxAccount(w.entity(), ccy));
        String by = "customer:" + holder.getPhone();
        Map<String, Object> done = tx.execute(s -> {
            String entry = wallets.apply(opRef, b.build(), List.of(new WalletService.Leg(w.id(), "CASH_OUT", "DR", amount + customerCharge.total(), agentLabel(a), opRef)), by,
                    "Not enough money in your wallet");
            record(opRef, a, "CASH_OUT", w, amount, ccy, commission, entry, by);
            return result(opRef, a, w, amount, ccy, commission, entry);
        });
        if (!replay) {
            wallets.recordUsage(holder.getId(), "CASH_OUT", ccy, channel, amount, customerCharge, opRef);
            if (rules != null) rules.record("AGENT", String.valueOf(agentId), "CASH_OUT", ccy, channel, amount, agentCharge, opRef);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("opRef", opRef);
        out.put("agentCode", a.get("code"));
        out.put("agentName", a.get("name"));
        out.put("agentLocation", a.get("location"));
        out.put("amountMinor", amount);
        out.put("currency", ccy);
        out.put("chargeMinor", customerCharge.charge());
        out.put("taxMinor", customerCharge.tax());
        out.put("balanceMinor", wallets.balance(w));
        out.put("entryRef", done.get("entryRef"));
        return out;
    }

    private boolean isDone(String opRef) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM agent_transactions WHERE op_ref = ?", Integer.class, opRef);
        return n != null && n > 0;
    }

    private PartyRules.Charge agentCheck(long agentId, String product, String ccy, String channel, long amount) {
        if (rules == null) return PartyRules.Charge.NONE;
        return rules.check("AGENT", List.of(product), ccy, channel, amount, rules.recorded("AGENT", String.valueOf(agentId), ccy));
    }

    private static void withAgentCharge(JournalRequest.Builder b, String entity, String ccy, PartyRules.Charge c) {
        if (c.charge() > 0) b.credit(agentFeeAccount(entity, ccy), c.charge(), "agent charge");
        if (c.tax() > 0) b.credit(agentTaxAccount(entity, ccy), c.tax(), "tax on agent charge");
    }

    static AccountKey agentFeeAccount(String entity, String ccy) {
        return AccountKey.of(entity, Book.LIVE, LedgerCategory.FEE_REVENUE, "AGENTS", ccy);
    }

    static AccountKey agentTaxAccount(String entity, String ccy) {
        return AccountKey.of(entity, Book.LIVE, LedgerCategory.TAX_PAYABLE, "AGENTS", ccy);
    }

    private static String agentLabel(Map<String, Object> a) {
        return a.get("code") + " " + a.get("name");
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

    Map<String, Object> activeAgent(long agentId) {
        Map<String, Object> a = get(agentId);
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
        return out;
    }

    /** The agent's float, read after the operation's transaction has committed (another connection sees only committed rows). */
    private Map<String, Object> withFloat(Map<String, Object> out, Map<String, Object> agent) {
        String ccy = (String) out.get("currency");
        out.put("floatMinor", ledger.balance(floatAccount(markets.requireForCurrency(ccy).legalEntity(), (String) agent.get("agent_code"), ccy)).balance());
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
