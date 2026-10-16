package zw.co.payport.gateway.settlement;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 3 settlement: eligible funds, batches with approvals, payout instructions and
 * payout confirmation. Integer minor units throughout.
 *
 * Lifecycle: DRAFT -> APPROVED (1 approval, or 2 above the currency's threshold; never by
 * the creator; never the same person twice) -> INSTRUCTED (payout file generated; LIVE mode
 * only) -> COMPLETED (every item PAID, RETURNED or FAILED). DRAFT/APPROVED can be CANCELLED,
 * which releases the sessions.
 *
 * Eligible funds per merchant and currency: live checkout sessions that were paid (also
 * partly or fully refunded), created before the cutoff (now - hold), not claimed by another
 * batch, not settled by the legacy settlement (LIVE mode), and, if required, with the
 * payment MATCHED on a provider statement. Net per session = session net prorated for
 * refunds, rounded half-up in minor units.
 *
 * Idempotency: generating the payout file for an instructed batch returns the stored file
 * (same content, same hash); instruction references are fixed per item and unique, so a
 * re-run after a crash never creates a second instruction. An item is PAID only with the
 * bank's reference and proof (database CHECK as well).
 */
@Slf4j
@Service
public class SettlementBatchService {

    public enum Mode { PARALLEL, LIVE }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final Environment env;
    private final Mode mode;
    private final long holdHours;
    private final boolean requireReconciled;
    private final PayoutFormats payoutFormats;
    private org.springframework.beans.factory.ObjectProvider<zw.co.payport.gateway.service.WebhookService> webhooks;

    private PayoutLedger payoutLedger;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPayoutLedger(PayoutLedger payoutLedger) {
        this.payoutLedger = payoutLedger;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setWebhooks(org.springframework.beans.factory.ObjectProvider<zw.co.payport.gateway.service.WebhookService> webhooks) {
        this.webhooks = webhooks;
    }

    public SettlementBatchService(JdbcTemplate jdbc, Environment env, PayoutFormats payoutFormats,
                                  @Value("${payport.settlement.mode:PARALLEL}") Mode mode,
                                  @Value("${payport.settlement.hold-hours:24}") long holdHours,
                                  @Value("${payport.settlement.require-reconciled:true}") boolean requireReconciled) {
        this.jdbc = jdbc;
        this.env = env;
        this.payoutFormats = payoutFormats;
        this.mode = mode;
        this.holdHours = holdHours;
        this.requireReconciled = requireReconciled;
    }

    public Mode mode() {
        return mode;
    }

    public record EligibleSession(long sessionId, long merchantId, String currency, long gross, long fee, long refunded, long net) {}

    public record Batch(long id, String batchRef, String currency, String mode, String status, int itemCount, long totalNetMinor,
                        int approvalsRequired, String createdBy, String fileSha256) {}

    /** Eligible funds, per session. Read-only. */
    public List<EligibleSession> eligible(String currency, LocalDateTime cutoff) {
        String sql = "SELECT cs.id, cs.merchant_id, cs.currency, cs.amount_cents, cs.commission_amount_cents, cs.net_amount_cents, "
                + "COALESCE((SELECT SUM(r.amount_cents) FROM refunds r WHERE r.checkout_session_id = cs.id AND r.status = 'COMPLETED'), 0) AS refunded "
                + "FROM checkout_sessions cs JOIN merchants m ON m.id = cs.merchant_id "
                + "WHERE cs.livemode = TRUE AND cs.status IN ('PAID','PARTIALLY_REFUNDED','REFUNDED') AND cs.currency = ? "
                + "AND cs.created_at < ? AND m.status = 'ACTIVE' "
                + "AND NOT EXISTS (SELECT 1 FROM stl_session_claims c WHERE c.session_id = cs.id) "
                + (mode == Mode.LIVE ? "AND cs.settlement_id IS NULL " : "")
                + (requireReconciled ? "AND EXISTS (SELECT 1 FROM recon_statement_lines l WHERE l.matched_transaction = cs.transaction_reference "
                + "AND l.match_status = 'MATCHED') " : "")
                + "ORDER BY cs.merchant_id, cs.id";
        return jdbc.query(sql, (rs, i) -> {
            long amount = rs.getLong("amount_cents");
            long refunded = Math.min(rs.getLong("refunded"), amount);
            long remaining = amount - refunded;
            long fullFee = rs.getObject("commission_amount_cents") == null ? 0 : rs.getLong("commission_amount_cents");
            long fullNet = rs.getObject("net_amount_cents") == null ? amount - fullFee : rs.getLong("net_amount_cents");
            return new EligibleSession(rs.getLong("id"), rs.getLong("merchant_id"), rs.getString("currency"), remaining,
                    prorate(fullFee, remaining, amount), refunded, prorate(fullNet, remaining, amount));
        }, currency, cutoff);
    }

    /** value * part / whole, rounded half-up, exact in long arithmetic for amounts below ~4.6e9 minor units squared. */
    static long prorate(long value, long part, long whole) {
        if (whole <= 0 || part <= 0) return 0;
        if (part == whole) return value;
        java.math.BigInteger n = java.math.BigInteger.valueOf(value).multiply(java.math.BigInteger.valueOf(part)).multiply(java.math.BigInteger.TWO)
                .add(java.math.BigInteger.valueOf(whole));
        return n.divide(java.math.BigInteger.valueOf(whole).multiply(java.math.BigInteger.TWO)).longValueExact();
    }

    /** Approvals a batch of this total needs: 2 above payport.settlement.dual-approval-threshold-minor.CCY (default 1,000,000). */
    public int approvalsRequired(String currency, long totalNet) {
        Long threshold = env.getProperty("payport.settlement.dual-approval-threshold-minor." + currency.toUpperCase(), Long.class,
                env.getProperty("payport.settlement.dual-approval-threshold-minor", Long.class, 1_000_000L));
        return totalNet > threshold ? 2 : 1;
    }

    /** Drafts a batch from the eligible funds now. Returns null when nothing is eligible. */
    @Transactional
    public Batch draft(String currency, String createdBy) {
        String ccy = currency.toUpperCase();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minusHours(holdHours);
        List<EligibleSession> sessions = eligible(ccy, cutoff);
        if (sessions.isEmpty()) return null;

        Map<Long, List<EligibleSession>> byMerchant = new LinkedHashMap<>();
        for (EligibleSession s : sessions) byMerchant.computeIfAbsent(s.merchantId(), k -> new ArrayList<>()).add(s);
        long total = sessions.stream().mapToLong(EligibleSession::net).sum();

        String batchRef = "STL-" + ccy + "-" + now.toLocalDate().toString().replace("-", "") + "-" + HexFormat.of().formatHex(randomBytes(4));
        long batchId = insert("INSERT INTO stl_batches (batch_ref, currency, mode, cutoff_at, status, item_count, total_net_minor, "
                        + "approvals_required, created_by, created_at) VALUES (?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?)",
                batchRef, ccy, mode.name(), Timestamp.valueOf(cutoff), byMerchant.size(), total, approvalsRequired(ccy, total), createdBy, Timestamp.valueOf(now));

        int n = 0;
        for (Map.Entry<Long, List<EligibleSession>> e : byMerchant.entrySet()) {
            n++;
            List<EligibleSession> list = e.getValue();
            long gross = list.stream().mapToLong(EligibleSession::gross).sum();
            long fee = list.stream().mapToLong(EligibleSession::fee).sum();
            long refunds = list.stream().mapToLong(EligibleSession::refunded).sum();
            long net = list.stream().mapToLong(EligibleSession::net).sum();
            String destination = destinationFor(e.getKey(), ccy);
            Long destinationId = verifiedDestinationId(e.getKey(), ccy);
            String instructionRef = "PO-" + batchRef.substring(4) + "-" + n;
            long itemId = insert("INSERT INTO stl_items (batch_id, merchant_id, currency, session_count, gross_minor, fee_minor, refund_minor, "
                            + "net_minor, destination, destination_id, instruction_ref, status, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)",
                    batchId, e.getKey(), ccy, list.size(), gross, fee, refunds, net, destination, destinationId, instructionRef, Timestamp.valueOf(now));
            for (EligibleSession s : list) {
                // The primary key on stl_session_claims makes a concurrent second draft fail instead of paying a session twice.
                jdbc.update("INSERT INTO stl_session_claims (session_id, item_id) VALUES (?, ?)", s.sessionId(), itemId);
                jdbc.update("INSERT INTO stl_item_sessions (item_id, session_id, net_minor) VALUES (?, ?, ?)", itemId, s.sessionId(), s.net());
            }
        }
        log.info("[SETTLEMENT] Drafted {} ({}): {} merchants, {} sessions, net {} {}", batchRef, mode, byMerchant.size(), sessions.size(), total, ccy);
        return get(batchId);
    }

    /**
     * Where a merchant is paid: the staff-verified settlement destination for the currency (Phase 4);
     * until a merchant has one, the payout details captured at signup (legacy). Automatic approval
     * only accepts verified destinations (SettlementAutomation).
     */
    String destinationFor(long merchantId, String currency) {
        List<String> verified = jdbc.queryForList("SELECT CONCAT(type, ': ', institution, ' ', COALESCE(CONCAT(branch, ' '), ''), account_number, "
                + "' (', account_name, ')') FROM merchant_settlement_destinations WHERE merchant_id = ? AND currency = ? AND status = 'VERIFIED' "
                + "ORDER BY id DESC LIMIT 1", String.class, merchantId, currency);
        if (!verified.isEmpty()) return verified.get(0);
        return jdbc.queryForObject("SELECT CONCAT(COALESCE(payout_account_type,''), ': ', COALESCE(payout_account_details,'')) "
                + "FROM merchants WHERE id = ?", String.class, merchantId);
    }

    Long verifiedDestinationId(long merchantId, String currency) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM merchant_settlement_destinations WHERE merchant_id = ? AND currency = ? AND status = 'VERIFIED' "
                + "ORDER BY id DESC LIMIT 1", Long.class, merchantId, currency);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** Records an approval. The creator cannot approve; nobody approves twice. Moves to APPROVED when enough. */
    @Transactional
    public Batch approve(long batchId, String approver) {
        Map<String, Object> b = lockBatch(batchId);
        if (!"DRAFT".equals(b.get("status"))) throw new IllegalStateException("Only a DRAFT batch can be approved (status " + b.get("status") + ")");
        if (approver.equals(b.get("created_by"))) throw new IllegalStateException("The creator of a batch cannot approve it");
        try {
            jdbc.update("INSERT INTO stl_batch_approvals (batch_id, approver, approved_at) VALUES (?, ?, ?)", batchId, approver, Timestamp.valueOf(LocalDateTime.now()));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalStateException(approver + " has already approved this batch");
        }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM stl_batch_approvals WHERE batch_id = ?", Integer.class, batchId);
        int required = ((Number) b.get("approvals_required")).intValue();
        if (count != null && count >= required) {
            jdbc.update("UPDATE stl_batches SET status = 'APPROVED', approved_at = ? WHERE id = ?", Timestamp.valueOf(LocalDateTime.now()), batchId);
            log.info("[SETTLEMENT] Batch {} approved ({} of {})", b.get("batch_ref"), count, required);
        }
        return get(batchId);
    }

    /** Cancels a DRAFT or APPROVED batch and releases its sessions. */
    @Transactional
    public Batch cancel(long batchId, String by) {
        Map<String, Object> b = lockBatch(batchId);
        String status = (String) b.get("status");
        if (!"DRAFT".equals(status) && !"APPROVED".equals(status)) throw new IllegalStateException("Only DRAFT or APPROVED batches can be cancelled");
        jdbc.update("DELETE c FROM stl_session_claims c JOIN stl_items i ON i.id = c.item_id WHERE i.batch_id = ?", batchId);
        jdbc.update("UPDATE stl_batches SET status = 'CANCELLED' WHERE id = ?", batchId);
        log.info("[SETTLEMENT] Batch {} cancelled by {}", b.get("batch_ref"), by);
        return get(batchId);
    }

    public record PayoutFile(String batchRef, String fileName, String format, String sha256, String content, boolean regenerated) {}

    /**
     * Generates the payout instruction file (APPROVED -> INSTRUCTED). Idempotent: for an
     * instructed batch the stored file is returned unchanged. Refused in PARALLEL mode.
     */
    @Transactional
    public PayoutFile instruct(long batchId, String by) {
        Map<String, Object> b = lockBatch(batchId);
        String status = (String) b.get("status");
        if ("INSTRUCTED".equals(status) || "COMPLETED".equals(status)) {
            return new PayoutFile((String) b.get("batch_ref"), (String) b.get("file_name"), (String) b.get("file_format"),
                    (String) b.get("file_sha256"), (String) b.get("file_content"), false);
        }
        if (!"LIVE".equals(b.get("mode")) || mode != Mode.LIVE) {
            throw new IllegalStateException("Settlement is in PARALLEL mode: batches cannot be instructed until the legacy settlement is retired");
        }
        if (!"APPROVED".equals(status)) throw new IllegalStateException("Only an APPROVED batch can be instructed (status " + status + ")");
        List<Map<String, Object>> items = jdbc.queryForList("SELECT * FROM stl_items WHERE batch_id = ? AND status = 'PENDING' ORDER BY id", batchId);
        PayoutFileAdapter payoutAdapter = payoutFormats.forCurrency((String) b.get("currency"));
        String content = payoutAdapter.render(b, items);
        String sha = sha256(content);
        String fileName = b.get("batch_ref") + "." + payoutAdapter.extension();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("UPDATE stl_items SET status = 'INSTRUCTED', updated_at = ? WHERE batch_id = ? AND status = 'PENDING'", Timestamp.valueOf(now), batchId);
        jdbc.update("UPDATE stl_batches SET status = 'INSTRUCTED', instructed_at = ?, file_format = ?, file_name = ?, file_sha256 = ?, file_content = ? "
                + "WHERE id = ?", Timestamp.valueOf(now), payoutAdapter.format(), fileName, sha, content, batchId);
        log.info("[SETTLEMENT] Batch {} instructed by {}: {} ({} items, sha256 {})", b.get("batch_ref"), by, fileName, items.size(), sha);
        return new PayoutFile((String) b.get("batch_ref"), fileName, payoutAdapter.format(), sha, content, true);
    }

    /** Bank result for one instruction. status: PAID, RETURNED or FAILED. */
    public record PayoutResult(String instructionRef, String status, String bankReference, String reason) {}

    /**
     * Applies a bank confirmation or return file. proofRef/proofSha256 identify the file (the
     * evidence). PAID needs the bank's reference. Re-applying the same result is a no-op.
     * RETURNED/FAILED items release their sessions so they become eligible again.
     */
    @Transactional
    public Map<String, Integer> applyPayoutResults(List<PayoutResult> results, String proofRef, String proofSha256, String by) {
        if (proofSha256 == null || proofSha256.isBlank()) throw new IllegalArgumentException("proof (file hash) is required");
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (PayoutResult r : results) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, batch_id, status, merchant_id, currency, net_minor FROM stl_items "
                    + "WHERE instruction_ref = ? FOR UPDATE", r.instructionRef());
            if (rows.isEmpty()) { counts.merge("UNKNOWN_INSTRUCTION", 1, Integer::sum); continue; }
            long itemId = ((Number) rows.get(0).get("id")).longValue();
            String current = (String) rows.get(0).get("status");
            String target = r.status().toUpperCase();
            if (current.equals(target)) { counts.merge("ALREADY_" + target, 1, Integer::sum); continue; }
            boolean allowed = ("INSTRUCTED".equals(current) && List.of("PAID", "RETURNED", "FAILED").contains(target))
                    || ("PAID".equals(current) && "RETURNED".equals(target));
            if (!allowed) { counts.merge("REJECTED_TRANSITION", 1, Integer::sum); continue; }
            LocalDateTime now = LocalDateTime.now();
            if ("PAID".equals(target)) {
                if (r.bankReference() == null || r.bankReference().isBlank()) { counts.merge("REJECTED_NO_BANK_REFERENCE", 1, Integer::sum); continue; }
                jdbc.update("UPDATE stl_items SET status = 'PAID', bank_reference = ?, proof_ref = ?, proof_sha256 = ?, paid_at = ?, updated_at = ? WHERE id = ?",
                        r.bankReference(), proofRef, proofSha256, Timestamp.valueOf(now), Timestamp.valueOf(now), itemId);
            } else {
                jdbc.update("UPDATE stl_items SET status = ?, return_reason = ?, proof_ref = COALESCE(proof_ref, ?), updated_at = ? WHERE id = ?",
                        target, r.reason(), proofRef, Timestamp.valueOf(now), itemId);
                jdbc.update("DELETE FROM stl_session_claims WHERE item_id = ?", itemId);
            }
            counts.merge(target, 1, Integer::sum);
            notifyPayout(rows.get(0), r, target, now);
            if (payoutLedger != null) {
                if ("PAID".equals(target)) payoutLedger.afterCommit(itemId, "PAID");
                else if ("PAID".equals(current)) payoutLedger.afterCommit(itemId, "RETURNED");
            }
            completeIfDone(((Number) rows.get(0).get("batch_id")).longValue());
        }
        log.info("[SETTLEMENT] Payout results applied by {} from {}: {}", by, proofRef, counts);
        return counts;
    }

    /** payout.paid / payout.returned / payout.failed to the merchant's webhooks (sent after commit). */
    private void notifyPayout(Map<String, Object> item, PayoutResult r, String target, LocalDateTime at) {
        if (webhooks == null) return;
        zw.co.payport.gateway.service.WebhookService ws = webhooks.getIfAvailable();
        if (ws == null) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("payoutId", r.instructionRef());
        data.put("status", target);
        data.put("currency", item.get("currency"));
        data.put("amountMinor", ((Number) item.get("net_minor")).longValue());
        if (r.bankReference() != null) data.put("bankReference", r.bankReference());
        if (r.reason() != null) data.put("reason", r.reason());
        data.put("at", at.toString());
        try {
            ws.notifyMerchantEvent(((Number) item.get("merchant_id")).longValue(), "payout." + target.toLowerCase(), data, true);
        } catch (RuntimeException e) {
            log.warn("[SETTLEMENT] payout webhook for {} not queued: {}", r.instructionRef(), e.getMessage());
        }
    }

    private void completeIfDone(long batchId) {
        Integer open = jdbc.queryForObject("SELECT COUNT(*) FROM stl_items WHERE batch_id = ? AND status IN ('PENDING','INSTRUCTED')", Integer.class, batchId);
        if (open != null && open == 0) {
            jdbc.update("UPDATE stl_batches SET status = 'COMPLETED', completed_at = ? WHERE id = ? AND status = 'INSTRUCTED'",
                    Timestamp.valueOf(LocalDateTime.now()), batchId);
        }
    }

    /**
     * Parallel-run comparison: for the sessions in this batch, the new net against the legacy
     * settlement's recorded net (if legacy settled them). Read-only.
     */
    public List<Map<String, Object>> compareWithLegacy(long batchId) {
        return jdbc.queryForList("SELECT i.merchant_id, i.currency, SUM(s.net_minor) AS new_net_minor, "
                + "COUNT(cs.settlement_id) AS sessions_in_legacy, COUNT(*) AS sessions, "
                + "SUM(CASE WHEN cs.settlement_id IS NULL THEN 0 ELSE cs.net_amount_cents END) AS legacy_net_minor_full_sessions "
                + "FROM stl_items i JOIN stl_item_sessions s ON s.item_id = i.id JOIN checkout_sessions cs ON cs.id = s.session_id "
                + "WHERE i.batch_id = ? GROUP BY i.merchant_id, i.currency", batchId);
    }

    public Batch get(long id) {
        return jdbc.queryForObject("SELECT * FROM stl_batches WHERE id = ?", (rs, i) -> new Batch(rs.getLong("id"), rs.getString("batch_ref"),
                rs.getString("currency"), rs.getString("mode"), rs.getString("status"), rs.getInt("item_count"), rs.getLong("total_net_minor"),
                rs.getInt("approvals_required"), rs.getString("created_by"), rs.getString("file_sha256")), id);
    }

    public List<Map<String, Object>> items(long batchId) {
        return jdbc.queryForList("SELECT * FROM stl_items WHERE batch_id = ? ORDER BY id", batchId);
    }

    private Map<String, Object> lockBatch(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM stl_batches WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("No settlement batch " + id);
        return rows.get(0);
    }

    private long insert(String sql, Object... args) {
        org.springframework.jdbc.support.GeneratedKeyHolder kh = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(c -> {
            var ps = c.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
