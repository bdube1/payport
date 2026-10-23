package zw.co.payport.gateway.wallets;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.payport.gateway.ledger.AccountKey;
import zw.co.payport.gateway.ledger.Book;
import zw.co.payport.gateway.ledger.JournalRequest;
import zw.co.payport.gateway.ledger.LedgerCategory;
import zw.co.payport.gateway.ledger.LedgerClient;
import zw.co.payport.gateway.ledger.LedgerException;
import zw.co.payport.gateway.markets.MarketRegistry;
import zw.co.payport.gateway.platform.Web;
import zw.co.payport.gateway.products.FxService;
import zw.co.payport.gateway.products.ServiceLineService;
import zw.co.payport.gateway.risk.ComplianceAudit;
import zw.co.payport.gateway.risk.ComplianceParameters;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Customer stored-value wallets (Phase 8). The balance IS the ledger balance of the wallet's
 * CUSTOMER_WALLET account: there is no balance column to drift, and the ledger refuses (at the
 * database, under row locks) any posting that would take a wallet below zero, so concurrent spends
 * can never overdraw it.
 *
 * Every movement goes through {@link #apply}: lock the wallets involved in id order, check status
 * and the tier limits (compliance parameter WALLET.TIER_LIMITS), post ONE balanced journal, record
 * the operations. Re-applying the same operation reference returns the first result (idempotent).
 *
 * Journals (entity = the currency's market legal entity, book LIVE):
 *   top-up        Dr PROVIDER_RECEIVABLE[rail]   Cr CUSTOMER_WALLET[w]
 *   payment       Dr CUSTOMER_WALLET[w]          Cr PROVIDER_RECEIVABLE[WALLET]
 *   refund        Dr PROVIDER_RECEIVABLE[WALLET] Cr CUSTOMER_WALLET[w]
 *   transfer      Dr CUSTOMER_WALLET[a]          Cr CUSTOMER_WALLET[b]
 *   conversion    Dr CUSTOMER_WALLET[a] Cr FX_CLEARING[FX] (from ccy) · Dr FX_CLEARING[FX] Cr CUSTOMER_WALLET[b] (to ccy)
 *   cash-in/out   see AgentService (AGENT_FLOAT against CUSTOMER_WALLET)
 *
 * The whole product line is behind the WALLETS licence switch (service_lines).
 */
@Slf4j
@Service
public class WalletService {

    public static final String LINE = "WALLETS";
    public static final String RAIL = "WALLET";
    static final Set<String> OUTGOING = Set.of("PAYMENT", "P2P_OUT", "CASH_OUT", "FX_OUT");
    static final Set<String> TIERS = Set.of("TIER1", "TIER2", "TIER3");
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Wallet(long id, String ref, long userId, String phone, String currency, String entity, String tier, String status) {
        public AccountKey account() {
            return AccountKey.of(entity, Book.LIVE, LedgerCategory.CUSTOMER_WALLET, ref, currency);
        }
    }

    /** One wallet's side of a movement. */
    public record Leg(long walletId, String kind, String direction, long amount, String counterparty, String reference) {}

    private final JdbcTemplate jdbc;
    private final LedgerClient ledger;
    private final MarketRegistry markets;
    private final ServiceLineService lines;
    private final ComplianceParameters params;
    private final ComplianceAudit audit;
    private final FxService fx;
    private final TransactionTemplate tx;

    public WalletService(JdbcTemplate jdbc, LedgerClient ledger, MarketRegistry markets, ServiceLineService lines, ComplianceParameters params,
                         ComplianceAudit audit, FxService fx, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.markets = markets;
        this.lines = lines;
        this.params = params;
        this.audit = audit;
        this.fx = fx;
        this.tx = new TransactionTemplate(tm);
    }

    public void requireOn() {
        lines.require(LINE, "PayPort wallets");
    }

    // ─── wallets ─────────────────────────────────────────────────────────────

    public Wallet open(long userId, String phone, String currency, String by) {
        requireOn();
        String ccy = upper(currency);
        if (!markets.liveCurrency(ccy)) throw new IllegalArgumentException("Wallets in " + ccy + " are not available at the moment");
        MarketRegistry.Market m = markets.requireForCurrency(ccy);
        if (markets.normaliseMobile(phone, m.country()).isEmpty()) {
            throw new IllegalArgumentException("A " + ccy + " wallet needs a " + m.name() + " mobile number");
        }
        Optional<Wallet> existing = forUser(userId, ccy);
        if (existing.isPresent()) return existing.get();
        String ref = "W" + digits(11);
        Timestamp now = now();
        try {
            jdbc.update("INSERT INTO wallets (wallet_ref, user_id, phone, currency, entity_code, created_at, updated_by, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                    ref, userId, e164(phone), ccy, m.legalEntity(), now, by, now);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return forUser(userId, ccy).orElseThrow();
        }
        Wallet w = byRef(ref).orElseThrow();
        ledger.openAccount(w.account(), by);
        log.info("[WALLET] {} opened {} for user {}", ref, ccy, userId);
        return w;
    }

    public Optional<Wallet> forUser(long userId, String currency) {
        return one("SELECT * FROM wallets WHERE user_id = ? AND currency = ?", userId, upper(currency));
    }

    public Optional<Wallet> forPhone(String phone, String currency) {
        return one("SELECT * FROM wallets WHERE phone = ? AND currency = ? AND status <> 'CLOSED'", e164(phone), upper(currency));
    }

    public Optional<Wallet> byRef(String ref) {
        return one("SELECT * FROM wallets WHERE wallet_ref = ?", ref == null ? "" : ref.trim().toUpperCase(Locale.ROOT));
    }

    public Wallet get(long id) {
        return one("SELECT * FROM wallets WHERE id = ?", id).orElseThrow(() -> new IllegalArgumentException("No such wallet"));
    }

    public List<Wallet> forUser(long userId) {
        return jdbc.query("SELECT * FROM wallets WHERE user_id = ? ORDER BY currency", (rs, i) -> map(rs), userId);
    }

    public long balance(Wallet w) {
        return ledger.balance(w.account()).balance();
    }

    public Map<String, Object> view(Wallet w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("walletRef", w.ref());
        m.put("currency", w.currency());
        m.put("balanceMinor", balance(w));
        m.put("tier", w.tier());
        m.put("status", w.status());
        m.put("phone", w.phone());
        try {
            long[] l = limits(w.tier(), w.currency());
            m.put("maxBalanceMinor", l[0]);
            m.put("dailyOutMinor", l[1]);
            m.put("usedTodayMinor", outToday(w.id()));
        } catch (IllegalStateException e) {
            m.put("limitsProblem", e.getMessage());
        }
        return m;
    }

    public List<Map<String, Object>> statement(Wallet w, int limit) {
        return jdbc.queryForList("SELECT op_ref, kind, direction, amount_minor, currency, counterparty, reference, entry_ref, created_at "
                + "FROM wallet_operations WHERE wallet_id = ? ORDER BY id DESC LIMIT ?", w.id(), Math.max(1, Math.min(limit, 500)));
    }

    // ─── the one way money moves ────────────────────────────────────────────

    /** Locks, checks, posts and records. Returns the ledger entry reference. Idempotent per (opRef, first leg's wallet). */
    public String apply(String opRef, JournalRequest journal, List<Leg> legs, String by, String insufficientMessage) {
        // Accounts are opened on short connections of their own, so only before any lock is taken. A caller that
        // already holds locks in an open transaction must have prepared them ({@link #prepareAccounts}); taking
        // more pooled connections while holding locks starves the pool under concurrency.
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) ledger.prepareAccounts(journal);
        return tx.execute(s -> {
            List<Long> ids = legs.stream().map(Leg::walletId).distinct().sorted().toList();
            Map<Long, Wallet> locked = new LinkedHashMap<>();
            for (Long id : ids) {
                locked.put(id, one("SELECT * FROM wallets WHERE id = ? FOR UPDATE", id).orElseThrow(() -> new IllegalArgumentException("No such wallet")));
            }
            List<String> done = jdbc.queryForList("SELECT entry_ref FROM wallet_operations WHERE op_ref = ? AND wallet_id = ?", String.class,
                    opRef, legs.get(0).walletId());
            if (!done.isEmpty()) return done.get(0);
            for (Leg l : legs) {
                Wallet w = locked.get(l.walletId());
                if ("CLOSED".equals(w.status())) throw new IllegalStateException("Wallet " + w.ref() + " is closed");
                if ("FROZEN".equals(w.status()) && !"REFUND".equals(l.kind())) {
                    throw new IllegalStateException("Wallet " + w.ref() + " is frozen. Please contact PayPort support.");
                }
                if (!"REFUND".equals(l.kind())) {
                    long[] lim = limits(w.tier(), w.currency());
                    if ("DR".equals(l.direction()) && OUTGOING.contains(l.kind()) && outToday(w.id()) + l.amount() > lim[1]) {
                        throw new IllegalArgumentException("This would exceed the daily limit of " + w.currency() + " " + major(lim[1]) + " for this wallet");
                    }
                    if ("CR".equals(l.direction()) && balance(w) + l.amount() > lim[0]) {
                        throw new IllegalArgumentException("This would take wallet " + w.ref() + " above its maximum balance of " + w.currency() + " " + major(lim[0]));
                    }
                }
            }
            String entryRef;
            try {
                // Same connection and transaction as the wallet rows: journal and operations commit together.
                entryRef = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c -> ledger.postInTransaction(c, journal).entryRef());
            } catch (LedgerException e) {
                if ("LEDGER_INSUFFICIENT_FUNDS".equals(e.code())) throw new Web.InsufficientFundsException(insufficientMessage);
                throw e;
            }
            Timestamp now = now();
            for (Leg l : legs) {
                Wallet w = locked.get(l.walletId());
                jdbc.update("INSERT INTO wallet_operations (op_ref, wallet_id, kind, direction, amount_minor, currency, counterparty, reference, entry_ref, created_by, created_at) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        opRef, w.id(), l.kind(), l.direction(), l.amount(), w.currency(), clip(l.counterparty(), 120), clip(l.reference(), 80), entryRef, by, now);
            }
            return entryRef;
        });
    }

    /** Opens these ledger accounts if missing (call before taking locks). */
    public void prepareAccounts(AccountKey... accounts) {
        for (AccountKey a : accounts) ledger.openAccount(a, "SYSTEM:wallet");
    }

    public JournalRequest.Builder journal(String key, String entity, String template, String description, String sourceType, String sourceRef, String by) {
        return JournalRequest.builder(key).entity(entity).book(Book.LIVE).template(template, 1).effectiveDate(LocalDate.now())
                .description(description).source(sourceType, sourceRef).postedBy(by);
    }

    public static AccountKey receivable(String entity, String rail, String ccy) {
        return AccountKey.of(entity, Book.LIVE, LedgerCategory.PROVIDER_RECEIVABLE, rail.toUpperCase(Locale.ROOT), ccy);
    }

    // ─── top-up (fulfilment of the WALLET_TOPUP product) ────────────────────

    /** Called before the customer pays: the wallet must exist, be usable and have room. */
    public Wallet validateTopUp(String recipient, String currency, long amount) {
        requireOn();
        Wallet w = recipient(recipient, currency);
        if (!"ACTIVE".equals(w.status())) throw new IllegalArgumentException("Wallet " + w.ref() + " cannot receive money at the moment");
        long[] lim = limits(w.tier(), w.currency());
        if (balance(w) + amount > lim[0]) {
            throw new IllegalArgumentException("This top-up would take the wallet above its maximum balance of " + w.currency() + " " + major(lim[0]));
        }
        return w;
    }

    public String creditTopUp(String reference, String recipient, String currency, long amount, String rail, String by) {
        Wallet w = recipient(recipient, currency);
        if (RAIL.equalsIgnoreCase(rail)) throw new IllegalArgumentException("A wallet cannot be topped up from a wallet");
        JournalRequest j = journal("WALLET_TOPUP:" + reference, w.entity(), "WALLET_TOPUP", "Wallet top-up " + reference, "transaction", reference, by)
                .debit(receivable(w.entity(), rail, w.currency()), amount, "collected by " + rail)
                .credit(w.account(), amount, "top-up").build();
        return apply(reference, j, List.of(new Leg(w.id(), "TOPUP", "CR", amount, rail, reference)), by, "Top-up refused");
    }

    Wallet recipient(String recipient, String currency) {
        if (recipient == null || recipient.isBlank()) throw new IllegalArgumentException("Wallet number or phone is required");
        String r = recipient.trim();
        Optional<Wallet> w = r.toUpperCase(Locale.ROOT).startsWith("W") ? byRef(r) : forPhone(r, currency);
        Wallet found = w.orElseThrow(() -> new IllegalArgumentException("No PayPort " + upper(currency) + " wallet for " + r));
        if (!found.currency().equals(upper(currency))) throw new IllegalArgumentException("Wallet " + found.ref() + " holds " + found.currency());
        return found;
    }

    // ─── pay with wallet ─────────────────────────────────────────────────────

    /** Records the PIN-confirmed consent to take this payment from this wallet. */
    public void authorizePayment(Wallet w, String reference, long amount, String currency, String by) {
        int ttl = (int) params.number("WALLET.PAYMENT_AUTH_TTL_MINUTES");
        Timestamp now = now();
        jdbc.update("INSERT INTO wallet_payment_authorizations (reference, wallet_id, amount_minor, currency, authorized_by, authorized_at, expires_at) VALUES (?,?,?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE wallet_id = VALUES(wallet_id), amount_minor = VALUES(amount_minor), currency = VALUES(currency), "
                        + "authorized_by = VALUES(authorized_by), authorized_at = VALUES(authorized_at), expires_at = VALUES(expires_at)",
                reference, w.id(), amount, upper(currency), by, now, Timestamp.valueOf(now.toLocalDateTime().plusMinutes(ttl)));
    }

    /** The payment adaptor's submit: only with a live, matching PIN authorisation. */
    public String debitForPayment(String intentReference, long amount, String currency) {
        requireOn();
        List<Map<String, Object>> a = jdbc.queryForList("SELECT * FROM wallet_payment_authorizations WHERE reference = ?", intentReference);
        if (a.isEmpty()) {
            // Hosted checkout: the holder confirmed the session before its payment reference existed.
            List<Long> cs = jdbc.queryForList("SELECT id FROM checkout_sessions WHERE transaction_reference = ?", Long.class, intentReference);
            if (!cs.isEmpty()) a = jdbc.queryForList("SELECT * FROM wallet_payment_authorizations WHERE reference = ?", "cs:" + cs.get(0));
        }
        if (a.isEmpty()) throw new SecurityException("This payment was not confirmed with the wallet PIN");
        Map<String, Object> auth = a.get(0);
        if (ldt(auth.get("expires_at")).isBefore(LocalDateTime.now())) {
            throw new IllegalStateException("The wallet confirmation expired. Please confirm again.");
        }
        if (((Number) auth.get("amount_minor")).longValue() != amount || !upper(currency).equals(auth.get("currency"))) {
            throw new IllegalStateException("The confirmed amount does not match the payment");
        }
        Wallet w = get(((Number) auth.get("wallet_id")).longValue());
        JournalRequest j = journal("WALLET_PAY:" + intentReference, w.entity(), "WALLET_PAYMENT", "Wallet payment " + intentReference, "transaction", intentReference, "SYSTEM:wallet")
                .debit(w.account(), amount, "paid from wallet")
                .credit(receivable(w.entity(), RAIL, w.currency()), amount, "collected by wallet").build();
        return apply(intentReference, j, List.of(new Leg(w.id(), "PAYMENT", "DR", amount, "PAYPORT", intentReference)), "SYSTEM:wallet",
                "Not enough money in your wallet");
    }

    public Optional<Map<String, Object>> operation(String reference, String kind) {
        return jdbc.queryForList("SELECT o.*, w.wallet_ref FROM wallet_operations o JOIN wallets w ON w.id = o.wallet_id WHERE o.reference = ? AND o.kind = ? ORDER BY o.id LIMIT 1",
                reference, kind).stream().findFirst();
    }

    /** Puts a wallet payment back into the wallet that paid it (whole amount, once). */
    public String refundPayment(String reference, String by) {
        Map<String, Object> paid = operation(reference, "PAYMENT").orElseThrow(() -> new IllegalArgumentException(reference + " was not paid from a wallet"));
        Wallet w = get(((Number) paid.get("wallet_id")).longValue());
        long amount = ((Number) paid.get("amount_minor")).longValue();
        JournalRequest j = journal("WALLET_REFUND:" + reference, w.entity(), "WALLET_REFUND", "Refund of " + reference + " to wallet", "transaction", reference, by)
                .debit(receivable(w.entity(), RAIL, w.currency()), amount, "refund of wallet payment")
                .credit(w.account(), amount, "refund").build();
        return apply("REFUND:" + reference, j, List.of(new Leg(w.id(), "REFUND", "CR", amount, "PAYPORT", reference)), by, "Refund refused");
    }

    // ─── transfers ───────────────────────────────────────────────────────────

    public Map<String, Object> transfer(long senderUserId, String toPhoneOrRef, String currency, long amount, String clientRef, String by) {
        requireOn();
        if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");
        String ccy = upper(currency);
        Wallet from = forUser(senderUserId, ccy).orElseThrow(() -> new IllegalArgumentException("You have no " + ccy + " wallet"));
        Wallet to = recipient(toPhoneOrRef, ccy);
        if (from.id() == to.id()) throw new IllegalArgumentException("You cannot send money to the same wallet");
        String opRef = "P2P:" + senderUserId + ":" + (clientRef == null || clientRef.isBlank() ? token() : clip(clientRef.trim(), 50));
        JournalRequest j = journal(opRef, from.entity(), "WALLET_P2P", "Transfer " + from.ref() + " to " + to.ref(), "wallet_transfer", opRef, by)
                .debit(from.account(), amount, "sent to " + to.ref())
                .credit(to.account(), amount, "received from " + from.ref()).build();
        String entry = apply(opRef, j, List.of(new Leg(from.id(), "P2P_OUT", "DR", amount, to.ref(), opRef),
                new Leg(to.id(), "P2P_IN", "CR", amount, from.ref(), opRef)), by, "Not enough money in your wallet");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transferRef", opRef);
        out.put("entryRef", entry);
        out.put("to", to.ref());
        out.put("amountMinor", amount);
        out.put("currency", ccy);
        out.put("balanceMinor", balance(from));
        return out;
    }

    // ─── conversions between a customer's own wallets ───────────────────────

    public Map<String, Object> quoteConversion(long userId, String from, String to, long amount) {
        requireOn();
        Wallet a = forUser(userId, from).orElseThrow(() -> new IllegalArgumentException("You have no " + upper(from) + " wallet"));
        Wallet b = forUser(userId, to).orElseThrow(() -> new IllegalArgumentException("Open a " + upper(to) + " wallet first"));
        if (!a.entity().equals(b.entity())) throw new IllegalArgumentException("Conversions between " + a.currency() + " and " + b.currency() + " are not available");
        Map<String, Object> q = fx.quote(a.currency(), b.currency(), amount);
        int ttl = (int) params.number("FX.QUOTE_TTL_SECONDS");
        String ref = "FXQ-" + token();
        Timestamp now = now();
        jdbc.update("INSERT INTO fx_quotes (quote_ref, user_id, from_wallet_id, to_wallet_id, from_ccy, to_ccy, amount_minor, converted_minor, rate, rate_id, expires_at, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                ref, userId, a.id(), b.id(), a.currency(), b.currency(), amount, q.get("convertedMinor"), q.get("rate"), q.get("rateId"),
                Timestamp.valueOf(now.toLocalDateTime().plusSeconds(ttl)), now);
        Map<String, Object> out = new LinkedHashMap<>(q);
        out.put("quoteRef", ref);
        out.put("validUntil", now.toLocalDateTime().plusSeconds(ttl).toString());
        out.put("note", "Executed at this rate if confirmed before it expires.");
        return out;
    }

    public Map<String, Object> convert(long userId, String quoteRef, String by) {
        requireOn();
        lines.require("FX_CONVERSION", "Currency conversion");
        // Open the four accounts before the quote is locked (see apply).
        jdbc.queryForList("SELECT from_wallet_id, to_wallet_id FROM fx_quotes WHERE quote_ref = ? AND user_id = ?", quoteRef, userId).stream().findFirst().ifPresent(pre -> {
            Wallet a0 = get(((Number) pre.get("from_wallet_id")).longValue()), b0 = get(((Number) pre.get("to_wallet_id")).longValue());
            prepareAccounts(a0.account(), b0.account(), AccountKey.of(a0.entity(), Book.LIVE, LedgerCategory.FX_CLEARING, "FX", a0.currency()),
                    AccountKey.of(b0.entity(), Book.LIVE, LedgerCategory.FX_CLEARING, "FX", b0.currency()));
        });
        return tx.execute(s -> {
            Map<String, Object> q = jdbc.queryForList("SELECT * FROM fx_quotes WHERE quote_ref = ? FOR UPDATE", quoteRef).stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No such quote"));
            if (((Number) q.get("user_id")).longValue() != userId) throw new IllegalArgumentException("No such quote");
            if ("EXECUTED".equals(q.get("status"))) return Map.<String, Object>of("quoteRef", quoteRef, "status", "EXECUTED", "entryRef", q.get("entry_ref_out"));
            if (!"OPEN".equals(q.get("status"))) throw new IllegalStateException("This quote is " + q.get("status"));
            if (ldt(q.get("expires_at")).isBefore(LocalDateTime.now())) {
                jdbc.update("UPDATE fx_quotes SET status = 'EXPIRED' WHERE quote_ref = ?", quoteRef);
                throw new IllegalStateException("The quote expired. Please get a new quote.");
            }
            Wallet a = get(((Number) q.get("from_wallet_id")).longValue());
            Wallet b = get(((Number) q.get("to_wallet_id")).longValue());
            long amount = ((Number) q.get("amount_minor")).longValue();
            long converted = ((Number) q.get("converted_minor")).longValue();
            JournalRequest j = journal("WALLET_FX:" + quoteRef, a.entity(), "WALLET_FX", "Conversion " + a.currency() + "->" + b.currency() + " at " + q.get("rate"),
                    "fx_quote", quoteRef, by)
                    .debit(a.account(), amount, "converted out")
                    .credit(AccountKey.of(a.entity(), Book.LIVE, LedgerCategory.FX_CLEARING, "FX", a.currency()), amount, "conversion in")
                    .debit(AccountKey.of(b.entity(), Book.LIVE, LedgerCategory.FX_CLEARING, "FX", b.currency()), converted, "conversion out")
                    .credit(b.account(), converted, "converted in").build();
            String entry = apply("FX:" + quoteRef, j, List.of(new Leg(a.id(), "FX_OUT", "DR", amount, b.ref(), quoteRef),
                    new Leg(b.id(), "FX_IN", "CR", converted, a.ref(), quoteRef)), by, "Not enough money in your " + a.currency() + " wallet");
            jdbc.update("UPDATE fx_quotes SET status = 'EXECUTED', executed_at = ?, entry_ref_out = ?, entry_ref_in = ? WHERE quote_ref = ?", now(), entry, entry, quoteRef);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("quoteRef", quoteRef);
            out.put("status", "EXECUTED");
            out.put("entryRef", entry);
            out.put("debitedMinor", amount);
            out.put("creditedMinor", converted);
            return out;
        });
    }

    // ─── administration ──────────────────────────────────────────────────────

    public List<Map<String, Object>> search(String q, int limit) {
        String like = "%" + (q == null ? "" : q.trim()) + "%";
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT w.*, u.full_name FROM wallets w LEFT JOIN users u ON u.id = w.user_id "
                + "WHERE w.wallet_ref LIKE ? OR w.phone LIKE ? ORDER BY w.id DESC LIMIT ?", like, like, Math.max(1, Math.min(limit, 200)));
        for (Map<String, Object> r : rows) {
            Wallet w = get(((Number) r.get("id")).longValue());
            r.put("balance_minor", balance(w));
        }
        return rows;
    }

    public Map<String, Object> setStatus(String ref, String status, String reason, String by) {
        String s = upper(status);
        if (reason == null || reason.isBlank()) reason = ("FROZEN".equals(s) ? "Frozen" : "Unfrozen") + " from the ops console";
        if (!Set.of("ACTIVE", "FROZEN").contains(s)) throw new IllegalArgumentException("status must be ACTIVE or FROZEN");
        Wallet w = byRef(ref).orElseThrow(() -> new IllegalArgumentException("No such wallet"));
        jdbc.update("UPDATE wallets SET status = ?, status_reason = ?, updated_by = ?, updated_at = ? WHERE id = ?", s, reason, by, now(), w.id());
        audit.record(by, "WALLET_" + s, "wallet:" + w.ref(), reason);
        return Map.of("walletRef", w.ref(), "status", s);
    }

    public Map<String, Object> setTier(String ref, String tier, String reason, String by) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("A reason is required (e.g. the KYC evidence)");
        String t = upper(tier);
        if (!TIERS.contains(t)) throw new IllegalArgumentException("tier must be one of " + TIERS);
        Wallet w = byRef(ref).orElseThrow(() -> new IllegalArgumentException("No such wallet"));
        jdbc.update("UPDATE wallets SET tier = ?, updated_by = ?, updated_at = ? WHERE id = ?", t, by, now(), w.id());
        audit.record(by, "WALLET_TIER", "wallet:" + w.ref(), w.tier() + " -> " + t + ": " + reason);
        return Map.of("walletRef", w.ref(), "tier", t);
    }

    /** Wallet balances per currency against the ledger accounts (they are the same numbers by construction). */
    public List<Map<String, Object>> totals() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT currency, COUNT(*) AS wallets, SUM(status = 'FROZEN') AS frozen FROM wallets GROUP BY currency ORDER BY currency")) {
            long total = 0;
            for (Wallet w : jdbc.query("SELECT * FROM wallets WHERE currency = ?", (rs, i) -> map(rs), r.get("currency"))) total += balance(w);
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("balance_minor", total);
            out.add(m);
        }
        return out;
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    /** {max_balance, daily_out} for a tier and currency; refuses when compliance has not configured them. */
    long[] limits(String tier, String ccy) {
        JsonNode n = params.get("WALLET.TIER_LIMITS").path(tier).path(ccy);
        if (n.isMissingNode() || !n.has("max_balance") || !n.has("daily_out")) {
            throw new IllegalStateException("No wallet limits configured for " + tier + " " + ccy + " (compliance parameter WALLET.TIER_LIMITS)");
        }
        return new long[]{n.get("max_balance").asLong(), n.get("daily_out").asLong()};
    }

    long outToday(long walletId) {
        Long v = jdbc.queryForObject("SELECT COALESCE(SUM(amount_minor), 0) FROM wallet_operations WHERE wallet_id = ? AND direction = 'DR' AND kind IN ('PAYMENT','P2P_OUT','CASH_OUT','FX_OUT') AND created_at >= ?",
                Long.class, walletId, Timestamp.valueOf(LocalDate.now().atStartOfDay()));
        return v == null ? 0 : v;
    }

    private Optional<Wallet> one(String sql, Object... args) {
        return jdbc.query(sql, (rs, i) -> map(rs), args).stream().findFirst();
    }

    private static Wallet map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Wallet(rs.getLong("id"), rs.getString("wallet_ref"), rs.getLong("user_id"), rs.getString("phone"), rs.getString("currency"),
                rs.getString("entity_code"), rs.getString("tier"), rs.getString("status"));
    }

    static String e164(String phone) {
        if (phone == null) return null;
        String d = phone.replaceAll("[^0-9]", "");
        return "+" + d;
    }

    static String upper(String s) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("currency is required");
        return s.trim().toUpperCase(Locale.ROOT);
    }

    static String major(long minor) {
        return java.math.BigDecimal.valueOf(minor, 2).toPlainString();
    }

    static String clip(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }

    static String digits(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(RANDOM.nextInt(10));
        return b.toString();
    }

    static String token() {
        byte[] b = new byte[9];
        RANDOM.nextBytes(b);
        return java.util.HexFormat.of().formatHex(b).toUpperCase(Locale.ROOT);
    }

    /** A DATETIME column as read by the driver (LocalDateTime with Connector/J 8+, Timestamp with older drivers). */
    public static LocalDateTime ldt(Object v) {
        if (v instanceof LocalDateTime l) return l;
        if (v instanceof Timestamp t) return t.toLocalDateTime();
        if (v instanceof java.util.Date d) return new Timestamp(d.getTime()).toLocalDateTime();
        throw new IllegalStateException("not a date-time: " + v);
    }

    static Timestamp now() {
        return Timestamp.valueOf(LocalDateTime.now());
    }
}
