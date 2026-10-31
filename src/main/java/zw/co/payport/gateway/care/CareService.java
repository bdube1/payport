package zw.co.payport.gateway.care;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.payments.TransactionOutcomeService;
import zw.co.payport.gateway.risk.ComplianceAudit;
import zw.co.payport.gateway.service.SmsNotificationService;
import zw.co.payport.gateway.wallets.WalletService;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Complaints (owner, 2026-10-30): each complaint gets a reference the customer can quote, a category from the
 * complaint list (which sets its priority and deadline), an owner and a history of notes. Tracing a reference shows
 * what PayPort itself recorded (the transaction and its outcome, wallet lines, possible duplicates) and which
 * reversals are possible - reversals are then requested from the central reversal desk.
 */
@Service
public class CareService {

    public static final Set<String> STATUSES = Set.of("OPEN", "INVESTIGATING", "AWAITING_REVERSAL", "RESOLVED", "CLOSED", "REJECTED");
    static final Set<String> CHANNELS = Set.of("PHONE", "WALK_IN", "EMAIL", "WHATSAPP", "SOCIAL", "MERCHANT", "AGENT", "OTHER");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final TransactionOutcomeService outcomes;
    private final WalletService wallets;
    private final ComplianceAudit audit;
    private final SmsNotificationService sms;

    public CareService(JdbcTemplate jdbc, TransactionOutcomeService outcomes, WalletService wallets, ComplianceAudit audit, SmsNotificationService sms) {
        this.jdbc = jdbc;
        this.outcomes = outcomes;
        this.wallets = wallets;
        this.audit = audit;
        this.sms = sms;
    }

    // ─── trace ───────────────────────────────────────────────────────────────

    /**
     * Everything PayPort recorded for a reference (PayPort reference, provider reference, wallet operation or
     * wallet line reference) and the reversals it allows.
     */
    public Map<String, Object> trace(String raw) {
        String ref = raw == null ? "" : raw.trim();
        if (ref.length() < 4) throw new IllegalArgumentException("Enter a reference");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reference", ref);
        List<Map<String, Object>> options = new ArrayList<>();

        List<Map<String, Object>> txs = jdbc.queryForList("SELECT t.reference, t.status, t.channel, t.customer_phone, t.recipient_identifier, t.amount_cents, t.currency, "
                + "COALESCE(t.product_name, p.name) AS product, t.payment_method_name, t.payment_provider_code, t.provider_reference, t.failure_reason, "
                + "t.refund_reference, t.refunded_at, t.created_at, t.product_type_id FROM transactions t LEFT JOIN product_types p ON p.id = t.product_type_id "
                + "WHERE t.reference = ? OR t.provider_reference = ? OR t.payment_attempt_reference = ? OR t.fulfilment_reference = ? LIMIT 5", ref, ref, ref, ref);
        List<Map<String, Object>> txView = new ArrayList<>();
        for (Map<String, Object> t : txs) {
            String r = (String) t.get("reference");
            Map<String, Object> v = new LinkedHashMap<>(t);
            v.remove("product_type_id");
            outcomes.current(r).ifPresent(o -> {
                v.put("outcome", o.outcome().name());
                v.put("outcomeMessage", o.message());
            });
            boolean fromWallet = wallets.operation(r, "PAYMENT").isPresent();
            boolean refunded = "REFUNDED".equals(t.get("status")) || wallets.operation(r, "REFUND").isPresent();
            v.put("paidFromWallet", fromWallet);
            v.put("refunded", refunded);
            List<Map<String, Object>> merchant = jdbc.queryForList("SELECT m.business_name, s.id AS session_id FROM checkout_sessions s JOIN merchants m ON m.id = s.merchant_id "
                    + "WHERE s.transaction_reference = ? LIMIT 1", r);
            if (!merchant.isEmpty()) v.put("merchant", merchant.get(0).get("business_name"));
            // Same payer, same amount and product within 15 minutes: possibly paid twice.
            if (t.get("customer_phone") != null && t.get("created_at") != null) {
                LocalDateTime at = at(t.get("created_at"));
                v.put("possibleDuplicates", jdbc.queryForList("SELECT reference, status, created_at FROM transactions WHERE customer_phone = ? AND amount_cents = ? "
                                + "AND product_type_id <=> ? AND reference <> ? AND created_at BETWEEN ? AND ? AND status NOT IN ('PAYMENT_FAILED','INITIATED','PAYMENT_PENDING') ORDER BY created_at",
                        t.get("customer_phone"), t.get("amount_cents"), t.get("product_type_id"), r,
                        Timestamp.valueOf(at.minusMinutes(15)), Timestamp.valueOf(at.plusMinutes(15))));
            }
            txView.add(v);
            if (!refunded) {
                String st = String.valueOf(t.get("status"));
                String what = t.get("product") + " " + t.get("currency") + " " + money(t.get("amount_cents")) + " (" + r + ")";
                long amount = ((Number) t.get("amount_cents")).longValue();
                if (fromWallet && !Set.of("PAYMENT_FAILED", "INITIATED", "PAYMENT_PENDING").contains(st)) {
                    options.add(option("WALLET_REFUND", r, amount, (String) t.get("currency"), "Refund to the customer's wallet: " + what));
                } else if (Set.of("PAYMENT_CONFIRMED", "FULFILMENT_PENDING", "FULFILMENT_RETRY", "FULFILMENT_FAILED", "COMPLETED", "REFUND_PENDING").contains(st)) {
                    options.add(option("PAYMENT_REFUND", r, amount, (String) t.get("currency"), "Refund the payment: " + what));
                }
            }
        }
        out.put("transactions", txView);

        List<Map<String, Object>> ops = jdbc.queryForList("SELECT o.op_ref, o.kind, o.direction, o.amount_minor, o.currency, o.counterparty, o.reference, o.created_at, w.wallet_ref "
                + "FROM wallet_operations o JOIN wallets w ON w.id = o.wallet_id WHERE o.op_ref = ? OR o.reference = ? ORDER BY o.id LIMIT 20", ref, ref);
        out.put("walletLines", ops);
        Set<String> seen = new java.util.HashSet<>();
        for (Map<String, Object> o : ops) {
            String op = (String) o.get("op_ref");
            if (!seen.add(op) || !WalletService.REVERSIBLE.contains((String) o.get("kind")) || wallets.reversed(op)) continue;
            long amount = ((Number) o.get("amount_minor")).longValue();
            String kind = switch (String.valueOf(o.get("kind"))) {
                case "P2P_OUT", "P2P_IN" -> "transfer";
                case "CASH_IN" -> "cash-in";
                case "CASH_OUT" -> "cash-out";
                default -> "currency conversion";
            };
            options.add(option("WALLET_REVERSAL", op, amount, (String) o.get("currency"), "Reverse the " + kind + " of " + o.get("currency") + " " + money(amount) + " (" + op + ")"));
        }
        out.put("reversals", jdbc.queryForList("SELECT reversal_ref, kind, status, requested_by, requested_at FROM reversal_requests WHERE target_ref = ? ORDER BY id DESC", ref));
        out.put("options", options);
        out.put("found", !txs.isEmpty() || !ops.isEmpty());
        return out;
    }

    private static Map<String, Object> option(String kind, String target, long amount, String ccy, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("targetRef", target);
        m.put("amountMinor", amount);
        m.put("currency", ccy);
        m.put("label", label);
        return m;
    }

    // ─── complaints ──────────────────────────────────────────────────────────

    public List<Map<String, Object>> list(String status, String q) {
        StringBuilder sql = new StringBuilder("SELECT * FROM complaints WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            if ("ACTIVE".equalsIgnoreCase(status)) sql.append(" AND status IN ('OPEN','INVESTIGATING','AWAITING_REVERSAL')");
            else { sql.append(" AND status = ?"); args.add(status.trim().toUpperCase(Locale.ROOT)); }
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim() + "%";
            sql.append(" AND (complaint_ref LIKE ? OR customer_phone LIKE ? OR customer_name LIKE ? OR reference LIKE ?)");
            for (int i = 0; i < 4; i++) args.add(like);
        }
        sql.append(" ORDER BY FIELD(status,'OPEN','INVESTIGATING','AWAITING_REVERSAL','RESOLVED','CLOSED','REJECTED'), due_at LIMIT 1000");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        LocalDateTime now = LocalDateTime.now();
        for (Map<String, Object> r : rows) {
            ComplaintCatalog.find((String) r.get("category")).ifPresent(c -> r.put("categoryTitle", c.title()));
            boolean open = Set.of("OPEN", "INVESTIGATING", "AWAITING_REVERSAL").contains(r.get("status"));
            r.put("overdue", open && at(r.get("due_at")).isBefore(now));
        }
        return rows;
    }

    public Map<String, Object> get(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM complaints WHERE id = ?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("No such complaint");
        Map<String, Object> c = new LinkedHashMap<>(rows.get(0));
        ComplaintCatalog.find((String) c.get("category")).ifPresent(k -> {
            c.put("categoryTitle", k.title());
            c.put("cause", k.cause());
            c.put("howToResolve", k.resolution());
        });
        c.put("notes", jdbc.queryForList("SELECT note, created_by, created_at FROM complaint_notes WHERE complaint_id = ? ORDER BY id", id));
        c.put("reversals", jdbc.queryForList("SELECT reversal_ref, kind, target_ref, amount_minor, currency, status, requested_by, decided_by FROM reversal_requests WHERE complaint_id = ? ORDER BY id", id));
        c.put("earlier", c.get("customer_phone") == null ? List.of() : jdbc.queryForList(
                "SELECT complaint_ref, category, status, created_at FROM complaints WHERE customer_phone = ? AND id <> ? ORDER BY id DESC LIMIT 10", c.get("customer_phone"), id));
        if (c.get("reference") != null && !String.valueOf(c.get("reference")).isBlank()) {
            try { c.put("trace", trace((String) c.get("reference"))); } catch (RuntimeException e) { c.put("trace", null); }
        }
        return c;
    }

    public record NewComplaint(String category, String channel, String customerPhone, String customerName, String reference, String description) {}

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private zw.co.payport.gateway.intelligence.ComplaintTriage triage;

    public Map<String, Object> create(NewComplaint b, String by) {
        ComplaintCatalog.Category cat = ComplaintCatalog.find(b.category()).orElseThrow(() -> new IllegalArgumentException("Choose the type of complaint"));
        String channel = b.channel() == null || b.channel().isBlank() ? "PHONE" : b.channel().trim().toUpperCase(Locale.ROOT);
        if (!CHANNELS.contains(channel)) throw new IllegalArgumentException("Choose how the complaint came in");
        String description = b.description() == null ? "" : b.description().trim();
        if (description.length() < 5) throw new IllegalArgumentException("Describe the complaint");
        String phone = phone(b.customerPhone());
        if (phone == null && (b.reference() == null || b.reference().isBlank())) throw new IllegalArgumentException("Enter the customer's phone number or a reference");
        LocalDateTime now = LocalDateTime.now();
        String ref = null;
        for (int i = 0; i < 10 && ref == null; i++) {
            String candidate = "CMP-" + now.format(DateTimeFormatter.ofPattern("yyMMdd")) + "-" + String.format("%05d", RANDOM.nextInt(100_000));
            try {
                jdbc.update("INSERT INTO complaints (complaint_ref, area, category, priority, status, channel, customer_phone, customer_name, reference, description, due_at, created_by, created_at, updated_at) "
                                + "VALUES (?,?,?,?, 'OPEN', ?,?,?,?,?,?,?,?,?)",
                        candidate, cat.area(), cat.code(), cat.priority(), channel, phone, clip(trim(b.customerName()), 120), clip(trim(b.reference()), 100),
                        clip(description, 2000), Timestamp.valueOf(now.plusHours(ComplaintCatalog.hours(cat.priority()))), by, Timestamp.valueOf(now), Timestamp.valueOf(now));
                ref = candidate;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // try another reference
            }
        }
        if (ref == null) throw new IllegalStateException("Please try again");
        long id = jdbc.queryForObject("SELECT id FROM complaints WHERE complaint_ref = ?", Long.class, ref);
        audit.record(by, "COMPLAINT_OPENED", "complaint:" + ref, cat.code());
        if (triage != null) triage.record(ref, description, cat.code());   // measured against what staff chose
        if (phone != null) sms.sendText(phone, "PayPort: we have received your complaint. Your reference is " + ref
                + ". We will update you by SMS. Quote this reference when you contact us.", ref, "COMPLAINT_OPENED");
        return get(id);
    }

    public Map<String, Object> note(long id, String note, String by) {
        if (note == null || note.isBlank()) throw new IllegalArgumentException("Write a note");
        get(id);
        addNote(id, note.trim(), by);
        touch(id);
        return get(id);
    }

    void addNote(long id, String note, String by) {
        jdbc.update("INSERT INTO complaint_notes (complaint_id, note, created_by, created_at) VALUES (?,?,?,?)", id, clip(note, 2000), by, Timestamp.valueOf(LocalDateTime.now()));
    }

    public Map<String, Object> assign(long id, String to, String by) {
        Map<String, Object> c = get(id);
        String who = to == null || to.isBlank() ? by : to.trim();
        jdbc.update("UPDATE complaints SET assigned_to = ?, status = CASE WHEN status = 'OPEN' THEN 'INVESTIGATING' ELSE status END, updated_at = ? WHERE id = ?",
                who, Timestamp.valueOf(LocalDateTime.now()), id);
        addNote(id, "Assigned to " + who, by);
        audit.record(by, "COMPLAINT_ASSIGNED", "complaint:" + c.get("complaint_ref"), who);
        return get(id);
    }

    /** Status change; resolving, closing or rejecting needs the outcome, which the customer is told by SMS. */
    public Map<String, Object> status(long id, String status, String resolution, String by) {
        Map<String, Object> c = get(id);
        String s = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(s)) throw new IllegalArgumentException("Unknown status");
        boolean finishing = Set.of("RESOLVED", "CLOSED", "REJECTED").contains(s);
        if (finishing && (resolution == null || resolution.isBlank())) throw new IllegalArgumentException("Write the outcome for the customer");
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("UPDATE complaints SET status = ?, resolution = COALESCE(?, resolution), resolved_at = ?, updated_at = ? WHERE id = ?",
                s, finishing ? clip(resolution.trim(), 2000) : null, finishing ? Timestamp.valueOf(now) : null, Timestamp.valueOf(now), id);
        addNote(id, "Status " + s + (finishing ? ": " + resolution.trim() : ""), by);
        audit.record(by, "COMPLAINT_" + s, "complaint:" + c.get("complaint_ref"), finishing ? resolution : null);
        if (finishing && c.get("customer_phone") != null) {
            sms.sendText((String) c.get("customer_phone"), "PayPort: your complaint " + c.get("complaint_ref") + " is "
                    + s.toLowerCase(Locale.ROOT) + ". " + clip(resolution.trim(), 300), (String) c.get("complaint_ref"), "COMPLAINT_" + s);
        }
        return get(id);
    }

    void touch(long id) {
        jdbc.update("UPDATE complaints SET updated_at = ? WHERE id = ?", Timestamp.valueOf(LocalDateTime.now()), id);
    }

    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("open", jdbc.queryForObject("SELECT COUNT(*) FROM complaints WHERE status IN ('OPEN','INVESTIGATING','AWAITING_REVERSAL')", Long.class));
        out.put("overdue", jdbc.queryForObject("SELECT COUNT(*) FROM complaints WHERE status IN ('OPEN','INVESTIGATING','AWAITING_REVERSAL') AND due_at < ?", Long.class,
                Timestamp.valueOf(LocalDateTime.now())));
        out.put("high", jdbc.queryForObject("SELECT COUNT(*) FROM complaints WHERE status IN ('OPEN','INVESTIGATING','AWAITING_REVERSAL') AND priority = 'HIGH'", Long.class));
        out.put("pendingReversals", jdbc.queryForObject("SELECT COUNT(*) FROM reversal_requests WHERE status IN ('PENDING_APPROVAL','AWAITING_PAYOUT')", Long.class));
        return out;
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    static String phone(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String d = raw.replaceAll("[^0-9]", "");
        if (d.startsWith("0") && d.length() == 10) d = "263" + d.substring(1);
        if (d.length() < 9 || d.length() > 15) throw new IllegalArgumentException("Enter a valid phone number");
        return "+" + d;
    }

    static LocalDateTime at(Object v) {
        return v instanceof Timestamp t ? t.toLocalDateTime() : v instanceof LocalDateTime l ? l : LocalDateTime.now();
    }

    static String money(Object minor) {
        return java.math.BigDecimal.valueOf(minor == null ? 0 : ((Number) minor).longValue(), 2).toPlainString();
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static String clip(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }
}
