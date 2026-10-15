package zw.co.payport.gateway.psp.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.common.Refs;
import zw.co.payport.gateway.psp.ledger.domain.*;
import zw.co.payport.gateway.psp.platform.outbox.EventTypes;
import zw.co.payport.gateway.psp.platform.outbox.OutboxService;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The financial source of truth. Every money movement in PayPort is a
 * balanced double-entry journal posted through {@link #post}.
 *
 * Guarantees:
 *  - balanced: debits == credits, enforced before anything is written
 *  - atomic: header, lines and balance projection commit together or not at all
 *  - idempotent: a business event's idempotency key can only ever post once
 *  - no overdraft on protected accounts: MERCHANT_* / SETTLEMENT_PAYABLE / funds-in-transit
 *    cannot go below zero, checked under row locks so concurrent postings cannot race past it
 *  - immutable: corrections are new entries (reversals / adjustments), never edits;
 *    DB triggers reject UPDATE and DELETE on journal tables
 *  - derived balances: balances shown to merchants and regulators are SUMs over journal lines
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    public record PostedEntry(String entryRef, Long entryId, String idempotencyKey, EntryType entryType,
                              String currency, long amountMinor, boolean replayed) {}

    public record BalanceView(String accountCode, AccountCategory category, String ownerRef, String currency,
                              long balanceMinor, long totalDebitsMinor, long totalCreditsMinor) {}

    public record StatementLine(String entryRef, String entryType, LocalDateTime effectiveAt, String description,
                                String sourceType, String sourceRef, String direction, long amountMinor,
                                long signedAmountMinor, String memo) {}

    private final LedgerAccountRepository accounts;
    private final JournalEntryRepository entries;
    private final JournalLineRepository lines;
    private final AccountBalanceRepository balances;
    private final AccountProvisioner provisioner;
    private final OutboxService outbox;

    public LedgerService(LedgerAccountRepository accounts, JournalEntryRepository entries, JournalLineRepository lines,
                         AccountBalanceRepository balances, AccountProvisioner provisioner, OutboxService outbox) {
        this.accounts = accounts;
        this.entries = entries;
        this.lines = lines;
        this.balances = balances;
        this.provisioner = provisioner;
        this.outbox = outbox;
    }

    // ── Posting ────────────────────────────────────────────────────────────

    @Transactional
    public PostedEntry post(JournalDraft draft, String postedBy) {
        return post(draft, postedBy, null, null);
    }

    @Transactional
    public PostedEntry post(JournalDraft draft, String postedBy, Long reversesEntryId, String reversesEntryRef) {
        draft.validate();

        Optional<JournalEntry> existing = entries.findByIdempotencyKey(draft.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), draft);
        }

        // 1. Resolve (or create) every account the entry touches.
        Map<AccountKey, LedgerAccount> byKey = new LinkedHashMap<>();
        for (JournalDraft.Line l : draft.lines()) {
            byKey.computeIfAbsent(l.account(), provisioner::getOrCreate);
        }
        for (LedgerAccount a : byKey.values()) {
            if (!"OPEN".equals(a.getStatus())) {
                throw new LedgerException("Ledger account " + a.getCode() + " is " + a.getStatus());
            }
        }

        // 2. Lock their balance rows in a fixed order.
        List<Long> ids = byKey.values().stream().map(LedgerAccount::getId).sorted().distinct().toList();
        Map<Long, AccountBalance> locked = balances.lockForUpdate(ids).stream()
                .collect(Collectors.toMap(AccountBalance::getAccountId, b -> b));
        if (locked.size() != ids.size()) {
            throw new IllegalStateException("Missing balance rows for accounts " + ids);
        }

        // 3. Someone may have posted the same key while we waited for the locks.
        existing = entries.findByIdempotencyKey(draft.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), draft);
        }

        // 4. Apply deltas in memory and enforce the no-overdraft rule.
        Map<Long, long[]> deltas = new HashMap<>(); // accountId -> [debits, credits]
        for (JournalDraft.Line l : draft.lines()) {
            long[] d = deltas.computeIfAbsent(byKey.get(l.account()).getId(), k -> new long[2]);
            if (l.direction() == Direction.DEBIT) d[0] = Math.addExact(d[0], l.amountMinor());
            else d[1] = Math.addExact(d[1], l.amountMinor());
        }
        for (LedgerAccount a : byKey.values()) {
            AccountBalance b = locked.get(a.getId());
            long[] d = deltas.get(a.getId());
            long newDebits = Math.addExact(b.getDebitsMinor(), d[0]);
            long newCredits = Math.addExact(b.getCreditsMinor(), d[1]);
            long newBalance = a.getAccountType().balanceOf(newDebits, newCredits);
            if (!a.isAllowNegative() && newBalance < 0) {
                throw new InsufficientFundsException(a.getCode(), b.getBalanceMinor(), -a.getAccountType().balanceOf(d[0], d[1]));
            }
            b.setDebitsMinor(newDebits);
            b.setCreditsMinor(newCredits);
            b.setBalanceMinor(newBalance);
        }

        // 5. Write the entry, its lines and the projection.
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        JournalEntry e = new JournalEntry();
        e.setEntryRef(Refs.newRef("je"));
        e.setIdempotencyKey(draft.idempotencyKey());
        e.setEntryType(draft.entryType());
        e.setCurrency(draft.currency());
        e.setDescription(truncate(draft.description(), 500));
        e.setSourceType(draft.sourceType());
        e.setSourceRef(draft.sourceRef());
        e.setReversesEntryId(reversesEntryId);
        e.setReversesEntryRef(reversesEntryRef);
        e.setEffectiveAt(now);
        e.setPostedBy(postedBy);
        e.setCreatedAt(now);

        List<EntryHashing.HashLine> hashLines = new ArrayList<>();
        List<JournalLine> toSave = new ArrayList<>();
        int lineNo = 1;
        for (JournalDraft.Line l : draft.lines()) {
            LedgerAccount a = byKey.get(l.account());
            JournalLine jl = new JournalLine();
            jl.setLineNo(lineNo);
            jl.setAccountId(a.getId());
            jl.setAccountCode(a.getCode());
            jl.setDirection(l.direction());
            jl.setAmountMinor(l.amountMinor());
            jl.setCurrency(draft.currency());
            jl.setMemo(truncate(l.memo(), 200));
            toSave.add(jl);
            hashLines.add(new EntryHashing.HashLine(lineNo, a.getCode(), l.direction().name(), l.amountMinor()));
            lineNo++;
        }
        e.setEntryHash(EntryHashing.hash(e.getEntryRef(), e.getIdempotencyKey(), e.getEntryType().name(),
                e.getCurrency(), e.getEffectiveAt(), reversesEntryRef, hashLines));
        entries.save(e);

        for (JournalLine jl : toSave) jl.setEntryId(e.getId());
        lines.saveAll(toSave);
        for (AccountBalance b : locked.values()) b.setLastEntryId(e.getId());
        balances.saveAll(locked.values());

        outbox.publish(EventTypes.LEDGER_ENTRY_POSTED, "JOURNAL_ENTRY", e.getEntryRef(), Map.of(
                "entryRef", e.getEntryRef(), "entryType", e.getEntryType().name(), "currency", e.getCurrency(),
                "amountMinor", draft.totalDebits(), "sourceType", String.valueOf(e.getSourceType()),
                "sourceRef", String.valueOf(e.getSourceRef())));

        log.info("[LEDGER] Posted {} {} {} {} minor (key {})", e.getEntryRef(), e.getEntryType(), e.getCurrency(),
                draft.totalDebits(), e.getIdempotencyKey());
        return new PostedEntry(e.getEntryRef(), e.getId(), e.getIdempotencyKey(), e.getEntryType(), e.getCurrency(),
                draft.totalDebits(), false);
    }

    /**
     * Mirror-image entry for a previous one. An entry can be reversed at most
     * once (unique index on reverses_entry_id). Protected accounts are still
     * checked, so e.g. a collection cannot be reversed once its funds have
     * been paid out; that needs a reviewed adjustment instead.
     */
    @Transactional
    public PostedEntry reverse(String entryRef, String reason, String actor) {
        JournalEntry original = entries.findByEntryRef(entryRef)
                .orElseThrow(() -> PspException.notFound("Journal entry", entryRef));
        if (original.getEntryType() == EntryType.REVERSAL) {
            throw PspException.conflict("CANNOT_REVERSE_REVERSAL", "Post a new entry instead of reversing a reversal");
        }
        Optional<JournalEntry> already = entries.findByReversesEntryId(original.getId());
        if (already.isPresent()) {
            JournalEntry r = already.get();
            return new PostedEntry(r.getEntryRef(), r.getId(), r.getIdempotencyKey(), r.getEntryType(), r.getCurrency(), 0, true);
        }
        JournalDraft.Builder b = JournalDraft.builder(EntryType.REVERSAL, "REVERSAL:" + original.getEntryRef(), original.getCurrency())
                .description("Reversal of " + original.getEntryRef() + ": " + reason)
                .source(original.getSourceType(), original.getSourceRef());
        for (JournalLine l : lines.findByEntryIdOrderByLineNoAsc(original.getId())) {
            LedgerAccount a = accounts.findById(l.getAccountId()).orElseThrow();
            b.line(new AccountKey(a.getCategory(), a.getOwnerRef(), a.getCurrency()), l.getDirection().opposite(), l.getAmountMinor(), l.getMemo());
        }
        return post(b.build(), actor, original.getId(), original.getEntryRef());
    }

    private PostedEntry replay(JournalEntry existing, JournalDraft draft) {
        List<JournalLine> stored = lines.findByEntryIdOrderByLineNoAsc(existing.getId());
        if (!sameContent(stored, draft)) {
            throw new LedgerException("Idempotency key " + draft.idempotencyKey()
                    + " was already used for a different journal entry (" + existing.getEntryRef() + ")");
        }
        return new PostedEntry(existing.getEntryRef(), existing.getId(), existing.getIdempotencyKey(),
                existing.getEntryType(), existing.getCurrency(), draft.totalDebits(), true);
    }

    private static boolean sameContent(List<JournalLine> stored, JournalDraft draft) {
        if (stored.size() != draft.lines().size()) return false;
        for (int i = 0; i < stored.size(); i++) {
            JournalLine s = stored.get(i);
            JournalDraft.Line d = draft.lines().get(i);
            if (!s.getAccountCode().equals(d.account().code()) || s.getDirection() != d.direction()
                    || s.getAmountMinor() != d.amountMinor()) {
                return false;
            }
        }
        return true;
    }

    // ── Reading (always derived from journal lines) ─────────────────────────

    @Transactional(readOnly = true)
    public long balance(AccountKey key) {
        return accounts.findByCode(key.code()).map(this::derivedBalance).orElse(0L);
    }

    @Transactional(readOnly = true)
    public long balanceAsOf(AccountKey key, LocalDateTime asOf) {
        return accounts.findByCode(key.code()).map(a -> {
            Object[] row = first(lines.sumsForAccountAsOf(a.getId(), asOf));
            return a.getAccountType().balanceOf(num(row[0]), num(row[1]));
        }).orElse(0L);
    }

    @Transactional(readOnly = true)
    public BalanceView view(AccountKey key) {
        return accounts.findByCode(key.code()).map(this::view)
                .orElse(new BalanceView(key.code(), key.category(), key.ownerRef(), key.currency(), 0, 0, 0));
    }

    @Transactional(readOnly = true)
    public List<BalanceView> ownerBalances(String ownerRef, Collection<AccountCategory> categories) {
        return accounts.findByOwnerRefAndCategoryIn(ownerRef, categories).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<BalanceView> categoryBalances(AccountCategory category) {
        return accounts.findByCategory(category).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<StatementLine> statement(AccountKey key, LocalDateTime from, LocalDateTime to, int limit) {
        LedgerAccount a = accounts.findByCode(key.code()).orElse(null);
        if (a == null) return List.of();
        Direction normal = a.getAccountType().normalSide();
        return lines.statement(a.getId(), from, to, PageRequest.of(0, Math.min(limit, 1000))).stream().map(r -> {
            String dir = String.valueOf(r[7]);
            long amt = num(r[8]);
            long signed = dir.equals(normal.name()) ? amt : -amt;
            return new StatementLine(String.valueOf(r[1]), String.valueOf(r[2]), toLdt(r[3]), (String) r[4],
                    (String) r[5], (String) r[6], dir, amt, signed, (String) r[9]);
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<JournalEntry> entriesForSource(String sourceType, String sourceRef) {
        return entries.findBySourceTypeAndSourceRefOrderByIdAsc(sourceType, sourceRef);
    }

    @Transactional(readOnly = true)
    public Optional<JournalEntry> entryByKey(String idempotencyKey) {
        return entries.findByIdempotencyKey(idempotencyKey);
    }

    @Transactional(readOnly = true)
    public List<JournalLine> linesOf(Long entryId) {
        return lines.findByEntryIdOrderByLineNoAsc(entryId);
    }

    private BalanceView view(LedgerAccount a) {
        Object[] row = first(lines.sumsForAccount(a.getId()));
        long d = num(row[0]), c = num(row[1]);
        return new BalanceView(a.getCode(), a.getCategory(), a.getOwnerRef(), a.getCurrency(), a.getAccountType().balanceOf(d, c), d, c);
    }

    private long derivedBalance(LedgerAccount a) {
        Object[] row = first(lines.sumsForAccount(a.getId()));
        return a.getAccountType().balanceOf(num(row[0]), num(row[1]));
    }

    private static Object[] first(List<Object[]> rows) {
        return rows == null || rows.isEmpty() ? new Object[]{0L, 0L} : rows.get(0);
    }

    static long num(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }

    static LocalDateTime toLdt(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDateTime l) return l;
        if (o instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        return LocalDateTime.parse(o.toString().replace(' ', 'T'));
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
