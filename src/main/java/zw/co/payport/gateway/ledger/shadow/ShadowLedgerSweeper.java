package zw.co.payport.gateway.ledger.shadow;

import zw.co.payport.gateway.ledger.JournalRequest;
import zw.co.payport.gateway.ledger.LedgerClient;
import zw.co.payport.gateway.ledger.LedgerException;
import zw.co.payport.gateway.ledger.templates.PostingTemplates;
import zw.co.payport.gateway.ledger.templates.PostingTemplates.Collection;
import zw.co.payport.gateway.ledger.templates.PostingTemplates.ConsumerPurchase;
import zw.co.payport.gateway.ledger.templates.PostingTemplates.Refund;
import zw.co.payport.gateway.ledger.templates.PostingTemplates.Settlement;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Observe-only shadow posting (ADR-0009). Reads gateway rows (never writes them) and posts
 * their financial effect to the ledger through the versioned templates.
 *
 * Streams run in dependency order (collections before the refunds and settlements that
 * draw on them). Each stream rescans from (watermark - overlap) so late commits are not
 * missed; re-posting is harmless because every posting is idempotent by key. A record
 * that fails is stored in shadow_ledger_failures and retried on every run until it posts
 * or a person resolves it. Nothing here can affect a payment: it runs on its own thread
 * and its own connections, and catches everything.
 */
public class ShadowLedgerSweeper {

    private static final Logger log = Logger.getLogger(ShadowLedgerSweeper.class.getName());

    public static final List<String> STREAMS = List.of("collections", "refunds", "settlements", "consumer");

    public record RunResult(Map<String, Integer> posted, Map<String, Integer> duplicates, Map<String, Integer> failed, int retriedOk) {}

    private final DataSource dataSource;
    private final LedgerClient ledger;
    private final String entity;
    private final Function<String, String> settlementBankAccount;
    private final Function<String, Integer> vendorCommissionBps;
    private final Duration overlap;
    private final int pageSize;

    public ShadowLedgerSweeper(DataSource dataSource, LedgerClient ledger, String entity,
                               Function<String, String> settlementBankAccount, Function<String, Integer> vendorCommissionBps,
                               Duration overlap, int pageSize) {
        this.dataSource = dataSource;
        this.ledger = ledger;
        this.entity = entity;
        this.settlementBankAccount = settlementBankAccount;
        this.vendorCommissionBps = vendorCommissionBps;
        this.overlap = overlap;
        this.pageSize = pageSize;
    }

    /** One full pass over every stream, then a retry of open failures. */
    public RunResult runOnce() {
        Map<String, Integer> posted = new java.util.LinkedHashMap<>(), dup = new java.util.LinkedHashMap<>(), failed = new java.util.LinkedHashMap<>();
        for (String stream : STREAMS) {
            int[] c = runStream(stream);
            posted.put(stream, c[0]);
            dup.put(stream, c[1]);
            failed.put(stream, c[2]);
        }
        int retried = retryFailures();
        return new RunResult(posted, dup, failed, retried);
    }

    /** Rescan everything changed since the given time (backfill). Idempotent. */
    public void resetWatermarks(LocalDateTime from) {
        for (String s : STREAMS) setWatermark(s, from, 0, 0);
    }

    // ── Streams ─────────────────────────────────────────────────────────

    private int[] runStream(String stream) {
        LocalDateTime watermark = watermark(stream);
        LocalDateTime from = watermark.minus(overlap);
        LocalDateTime maxSeen = watermark;
        int[] counts = new int[3];
        long lastId = -1;
        LocalDateTime lastTs = from;
        for (int page = 0; page < 1_000; page++) {
            List<Item> items = fetch(stream, lastTs, lastId);
            if (items.isEmpty()) break;
            for (Item it : items) {
                for (JournalRequest r : it.requests) post(r, counts);
                if (it.changedAt.isAfter(maxSeen)) maxSeen = it.changedAt;
                lastTs = it.changedAt;
                lastId = it.sortId;
            }
            if (items.size() < pageSize) break;
        }
        setWatermark(stream, maxSeen, counts[0], counts[2]);
        return counts;
    }

    private void post(JournalRequest r, int[] counts) {
        try {
            LedgerClient.PostResult res = ledger.post(r);
            counts[res.outcome() == LedgerClient.Outcome.POSTED ? 0 : 1]++;
            resolveFailureIfAny(r.idempotencyKey(), "posted on retry");
        } catch (LedgerException e) {
            counts[2]++;
            recordFailure(r, e.code(), e.getMessage());
        } catch (RuntimeException e) {
            counts[2]++;
            recordFailure(r, "UNEXPECTED", String.valueOf(e));
        }
    }

    private record Item(LocalDateTime changedAt, long sortId, List<JournalRequest> requests) {}

    private List<Item> fetch(String stream, LocalDateTime fromTs, long afterId) {
        return switch (stream) {
            case "collections" -> query(SQL_COLLECTIONS + " AND (cs.updated_at > ? OR (cs.updated_at = ? AND cs.id > ?)) ORDER BY cs.updated_at, cs.id LIMIT ?",
                    fromTs, afterId, this::collectionItem);
            case "refunds" -> query(SQL_REFUNDS + " AND (r.created_at > ? OR (r.created_at = ? AND r.id > ?)) ORDER BY r.created_at, r.id LIMIT ?",
                    fromTs, afterId, this::refundItem);
            case "settlements" -> query(SQL_SETTLEMENTS + " WHERE (GREATEST(s.created_at, IFNULL(s.paid_at, s.created_at)) > ? OR "
                    + "(GREATEST(s.created_at, IFNULL(s.paid_at, s.created_at)) = ? AND s.id > ?)) "
                    + "ORDER BY GREATEST(s.created_at, IFNULL(s.paid_at, s.created_at)), s.id LIMIT ?", fromTs, afterId, this::settlementItem);
            case "consumer" -> query(SQL_CONSUMER + " AND (t.updated_at > ? OR (t.updated_at = ? AND t.id > ?)) ORDER BY t.updated_at, t.id LIMIT ?",
                    fromTs, afterId, this::consumerItem);
            default -> throw new IllegalArgumentException(stream);
        };
    }

    // Gateway reads. Only the columns needed; nothing is written to gateway tables.
    static final String SQL_COLLECTIONS =
            "SELECT cs.id, cs.merchant_id, cs.livemode, cs.currency, cs.amount_cents, cs.commission_amount_cents, cs.net_amount_cents, "
            + "COALESCE(t.payment_provider_code, cs.payment_method_code) AS rail, COALESCE(t.payment_confirmed_at, cs.updated_at) AS paid_at, "
            + "cs.updated_at AS changed_at FROM checkout_sessions cs LEFT JOIN transactions t ON t.reference = cs.transaction_reference "
            + "WHERE cs.status IN ('PAID','PARTIALLY_REFUNDED','REFUNDED')";
    static final String SQL_REFUNDS =
            "SELECT r.id, r.checkout_session_id, r.amount_cents, r.created_at AS changed_at, cs.merchant_id, cs.livemode, cs.currency, "
            + "cs.amount_cents AS gross, cs.commission_amount_cents, cs.net_amount_cents, "
            + "(SELECT COALESCE(SUM(r2.amount_cents), 0) FROM refunds r2 WHERE r2.checkout_session_id = r.checkout_session_id "
            + "AND r2.status = 'COMPLETED' AND r2.id < r.id) AS refunded_before "
            + "FROM refunds r JOIN checkout_sessions cs ON cs.id = r.checkout_session_id WHERE r.status = 'COMPLETED'";
    static final String SQL_SETTLEMENTS =
            "SELECT s.id, s.merchant_id, s.currency, s.net_amount_cents, s.status, s.created_at, s.paid_at, "
            + "GREATEST(s.created_at, IFNULL(s.paid_at, s.created_at)) AS changed_at FROM settlements s";
    static final String SQL_CONSUMER =
            "SELECT t.id, t.reference, t.currency, t.amount_cents, t.payment_provider_code, "
            + "COALESCE(t.fulfilment_provider_code, pt.provider_code) AS vendor, t.status, t.payment_confirmed_at, t.completed_at, "
            + "t.refunded_at, t.updated_at AS changed_at "
            + "FROM transactions t JOIN product_types pt ON pt.id = t.product_type_id "
            + "WHERE pt.category <> 'MERCHANT' AND t.payment_confirmed_at IS NOT NULL "
            + "AND (t.provider_reference IS NULL OR t.provider_reference NOT LIKE 'TEST\\_SIM\\_%')";

    private Item collectionItem(ResultSet rs) throws SQLException {
        Collection c = new Collection(rs.getLong("id"), rs.getLong("merchant_id"), rs.getBoolean("livemode"), rs.getString("currency"),
                rs.getLong("amount_cents"), nullableLong(rs, "commission_amount_cents"), nullableLong(rs, "net_amount_cents"),
                rs.getString("rail"), date(rs, "paid_at"));
        return new Item(ts(rs, "changed_at"), c.sessionId(), List.of(PostingTemplates.merchantCollection(entity, c)));
    }

    private Item refundItem(ResultSet rs) throws SQLException {
        Refund r = new Refund(rs.getLong("id"), rs.getLong("checkout_session_id"), rs.getLong("merchant_id"), rs.getBoolean("livemode"),
                rs.getString("currency"), rs.getLong("gross"), nullableLong(rs, "commission_amount_cents"), nullableLong(rs, "net_amount_cents"),
                rs.getLong("refunded_before"), rs.getLong("amount_cents"), date(rs, "changed_at"));
        return new Item(ts(rs, "changed_at"), r.refundId(), List.of(PostingTemplates.merchantRefund(entity, r)));
    }

    private Item settlementItem(ResultSet rs) throws SQLException {
        long net = rs.getLong("net_amount_cents");
        List<JournalRequest> out = new ArrayList<>();
        if (net > 0) {
            String ccy = rs.getString("currency");
            out.add(PostingTemplates.settlementCreated(entity, new Settlement(rs.getLong("id"), rs.getLong("merchant_id"), ccy, net, date(rs, "created_at"))));
            if ("PAID".equals(rs.getString("status"))) {
                LocalDate paidOn = rs.getTimestamp("paid_at") != null ? date(rs, "paid_at") : date(rs, "created_at");
                out.add(PostingTemplates.settlementPaid(entity, new Settlement(rs.getLong("id"), rs.getLong("merchant_id"), ccy, net, paidOn),
                        settlementBankAccount.apply(ccy)));
            }
        }
        return new Item(ts(rs, "changed_at"), rs.getLong("id"), out);
    }

    private Item consumerItem(ResultSet rs) throws SQLException {
        String status = rs.getString("status");
        String ref = rs.getString("reference");
        String ccy = rs.getString("currency");
        long amt = rs.getLong("amount_cents");
        String rail = rs.getString("payment_provider_code");
        String vendor = rs.getString("vendor");
        List<JournalRequest> out = new ArrayList<>();
        out.add(PostingTemplates.consumerPayment(entity, new ConsumerPurchase(ref, ccy, amt, rail, vendor, date(rs, "payment_confirmed_at"))));
        if ("COMPLETED".equals(status)) {
            LocalDate on = rs.getTimestamp("completed_at") != null ? date(rs, "completed_at") : date(rs, "changed_at");
            Integer bps = vendorCommissionBps.apply(vendor == null ? "" : vendor.toUpperCase());
            out.add(PostingTemplates.consumerFulfilment(entity, new ConsumerPurchase(ref, ccy, amt, rail, vendor, on), bps == null ? 0 : bps));
        }
        if ("REFUND_PENDING".equals(status) || "REFUNDED".equals(status)) {
            out.add(PostingTemplates.consumerRefundDue(entity, new ConsumerPurchase(ref, ccy, amt, rail, vendor, date(rs, "changed_at"))));
        }
        if ("REFUNDED".equals(status)) {
            LocalDate on = rs.getTimestamp("refunded_at") != null ? date(rs, "refunded_at") : date(rs, "changed_at");
            out.add(PostingTemplates.consumerRefundPaid(entity, new ConsumerPurchase(ref, ccy, amt, rail, vendor, on)));
        }
        return new Item(ts(rs, "changed_at"), rs.getLong("id"), out);
    }

    // ── Failures ─────────────────────────────────────────────────────────

    /** Re-posts open failures from their current source rows. Returns how many now posted. */
    int retryFailures() {
        record Open(String key, String sourceType, String sourceRef) {}
        List<Open> open = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT idempotency_key, source_type, source_ref FROM shadow_ledger_failures WHERE resolved_at IS NULL ORDER BY id LIMIT 500")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) open.add(new Open(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "[SHADOW] could not read failures", e);
            return 0;
        }
        int ok = 0;
        for (Open o : open) {
            List<JournalRequest> candidates = refetch(o.sourceType(), o.sourceRef());
            for (JournalRequest r : candidates) {
                if (!r.idempotencyKey().equals(o.key())) continue;
                int[] counts = new int[3];
                post(r, counts);
                if (counts[2] == 0) ok++;
            }
        }
        return ok;
    }

    private List<JournalRequest> refetch(String sourceType, String sourceRef) {
        List<Item> items = switch (sourceType) {
            case "session" -> query(SQL_COLLECTIONS + " AND cs.id = ?", sourceRef, this::collectionItem);
            case "refund" -> query(SQL_REFUNDS + " AND r.id = ?", sourceRef, this::refundItem);
            case "settlement" -> query(SQL_SETTLEMENTS + " WHERE s.id = ?", sourceRef, this::settlementItem);
            case "transaction" -> query(SQL_CONSUMER + " AND t.reference = ?", sourceRef, this::consumerItem);
            default -> List.of();
        };
        List<JournalRequest> out = new ArrayList<>();
        items.forEach(i -> out.addAll(i.requests));
        return out;
    }

    private void recordFailure(JournalRequest r, String code, String message) {
        String msg = message == null ? "" : (message.length() > 1000 ? message.substring(0, 1000) : message);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO shadow_ledger_failures (idempotency_key, template_code, source_type, source_ref, error_code, message, attempts, first_seen_at, last_seen_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?, 1, NOW(6), NOW(6)) "
                             + "ON DUPLICATE KEY UPDATE attempts = attempts + 1, error_code = VALUES(error_code), message = VALUES(message), "
                             + "last_seen_at = NOW(6), resolved_at = NULL, resolution = NULL, resolved_by = NULL")) {
            ps.setString(1, r.idempotencyKey());
            ps.setString(2, r.templateCode());
            ps.setString(3, r.sourceType());
            ps.setString(4, r.sourceRef());
            ps.setString(5, code);
            ps.setString(6, msg);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "[SHADOW] could not record failure for " + r.idempotencyKey(), e);
        }
    }

    private void resolveFailureIfAny(String key, String resolution) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE shadow_ledger_failures SET resolved_at = NOW(6), resolution = ?, resolved_by = 'SYSTEM:shadow-ledger' "
                             + "WHERE idempotency_key = ? AND resolved_at IS NULL")) {
            ps.setString(1, resolution);
            ps.setString(2, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.log(Level.WARNING, "[SHADOW] could not resolve failure " + key, e);
        }
    }

    public record Failure(String idempotencyKey, String templateCode, String sourceType, String sourceRef, String errorCode,
                          String message, int attempts, LocalDateTime firstSeen, LocalDateTime lastSeen, LocalDateTime resolvedAt, String resolution) {}

    /** Failures for the finance/ops desk, newest first. */
    public List<Failure> failures(boolean openOnly, int limit) {
        List<Failure> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT idempotency_key, template_code, source_type, source_ref, error_code, message, attempts, first_seen_at, last_seen_at, "
                        + "resolved_at, resolution FROM shadow_ledger_failures " + (openOnly ? "WHERE resolved_at IS NULL " : "")
                        + "ORDER BY last_seen_at DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, Math.min(limit, 1_000)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp resolved = rs.getTimestamp(10);
                    out.add(new Failure(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                            rs.getInt(7), rs.getTimestamp(8).toLocalDateTime(), rs.getTimestamp(9).toLocalDateTime(),
                            resolved == null ? null : resolved.toLocalDateTime(), rs.getString(11)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read shadow failures", e);
        }
        return out;
    }

    /** A person explains and closes a failure that will never post (e.g. a known legacy gap). */
    public boolean resolveManually(String idempotencyKey, String resolution, String by) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE shadow_ledger_failures SET resolved_at = NOW(6), resolution = ?, resolved_by = ? WHERE idempotency_key = ? AND resolved_at IS NULL")) {
            ps.setString(1, resolution);
            ps.setString(2, by);
            ps.setString(3, idempotencyKey);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("could not resolve failure", e);
        }
    }

    // ── Watermarks ───────────────────────────────────────────────────────

    LocalDateTime watermark(String stream) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT watermark FROM shadow_ledger_state WHERE stream = ?")) {
            ps.setString(1, stream);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getTimestamp(1).toLocalDateTime() : LocalDateTime.of(2000, 1, 1, 0, 0);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("shadow watermark unavailable", e);
        }
    }

    private void setWatermark(String stream, LocalDateTime wm, int posted, int failed) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO shadow_ledger_state (stream, watermark, last_run_at, last_posted, last_failed, updated_at) VALUES (?, ?, NOW(6), ?, ?, NOW(6)) "
                             + "ON DUPLICATE KEY UPDATE watermark = VALUES(watermark), last_run_at = NOW(6), last_posted = VALUES(last_posted), "
                             + "last_failed = VALUES(last_failed), updated_at = NOW(6)")) {
            ps.setString(1, stream);
            ps.setTimestamp(2, Timestamp.valueOf(wm));
            ps.setInt(3, posted);
            ps.setInt(4, failed);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not save shadow watermark", e);
        }
    }

    // ── JDBC helpers ─────────────────────────────────────────────────────

    @FunctionalInterface
    private interface RowMapper { Item map(ResultSet rs) throws SQLException; }

    private List<Item> query(String sql, LocalDateTime fromTs, long afterId, RowMapper mapper) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.valueOf(fromTs));
            ps.setTimestamp(2, Timestamp.valueOf(fromTs));
            ps.setLong(3, afterId);
            ps.setInt(4, pageSize);
            return collect(ps, mapper);
        } catch (SQLException e) {
            throw new IllegalStateException("shadow read failed: " + e.getMessage(), e);
        }
    }

    private List<Item> query(String sql, String id, RowMapper mapper) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            return collect(ps, mapper);
        } catch (SQLException e) {
            throw new IllegalStateException("shadow read failed: " + e.getMessage(), e);
        }
    }

    private static List<Item> collect(PreparedStatement ps, RowMapper mapper) throws SQLException {
        List<Item> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(mapper.map(rs));
        }
        return out;
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static LocalDateTime ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? LocalDateTime.now() : t.toLocalDateTime();
    }

    private static LocalDate date(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? LocalDate.now() : t.toLocalDateTime().toLocalDate();
    }
}
