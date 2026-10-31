package zw.co.payport.gateway.care;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.risk.ComplianceAudit;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;
import zw.co.payport.gateway.wallets.WalletPaymentService;
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

/**
 * The central reversal desk (owner, 2026-10-30): the one place staff reverse money. Someone with the Reversals
 * permission requests a reversal of something PayPort recorded (the amount and currency come from the record, never
 * typed); someone else with the Reversal approval permission approves it, and only then is it carried out by the
 * same services that own the money:
 * <ul>
 *   <li>WALLET_REFUND - a payment made from a PayPort wallet goes back to that wallet (WalletPaymentService).</li>
 *   <li>PAYMENT_REFUND - a mobile money or card payment is marked for refund (REFUND_PENDING); the payout to the
 *       customer is made on the provider's side and recorded here with its reference, which closes it (REFUNDED).</li>
 *   <li>WALLET_REVERSAL - a transfer, cash-in, cash-out or conversion is reversed in full by a mirrored ledger entry
 *       (WalletService.reverseOperation); refused if the money is no longer there.</li>
 * </ul>
 * Nobody approves their own request (checked here and by the database). One open request per target.
 */
@Service
public class ReversalService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final CareService care;
    private final WalletService wallets;
    private final WalletPaymentService walletPayments;
    private final TransactionOrchestratorService orchestrator;
    private final ComplianceAudit audit;

    public ReversalService(JdbcTemplate jdbc, CareService care, WalletService wallets, WalletPaymentService walletPayments,
                           TransactionOrchestratorService orchestrator, ComplianceAudit audit) {
        this.jdbc = jdbc;
        this.care = care;
        this.wallets = wallets;
        this.walletPayments = walletPayments;
        this.orchestrator = orchestrator;
        this.audit = audit;
    }

    public List<Map<String, Object>> list(String status) {
        StringBuilder sql = new StringBuilder("SELECT r.*, c.complaint_ref FROM reversal_requests r LEFT JOIN complaints c ON c.id = r.complaint_id WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            if ("OPEN".equalsIgnoreCase(status)) sql.append(" AND r.status IN ('PENDING_APPROVAL','AWAITING_PAYOUT')");
            else { sql.append(" AND r.status = ?"); args.add(status.trim().toUpperCase(Locale.ROOT)); }
        }
        sql.append(" ORDER BY FIELD(r.status,'PENDING_APPROVAL','AWAITING_PAYOUT','FAILED','EXECUTED','REJECTED'), r.id DESC LIMIT 1000");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        for (Map<String, Object> r : rows) r.remove("open_target");
        return rows;
    }

    public Map<String, Object> get(String reversalRef) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT r.*, c.complaint_ref FROM reversal_requests r LEFT JOIN complaints c ON c.id = r.complaint_id WHERE r.reversal_ref = ?", reversalRef);
        if (rows.isEmpty()) throw new IllegalArgumentException("No such reversal");
        Map<String, Object> r = new LinkedHashMap<>(rows.get(0));
        r.remove("open_target");
        return r;
    }

    public record NewReversal(String kind, String targetRef, String reason, String complaintRef) {}

    /** A request: it must match one of the reversals the trace of the target allows. */
    public Map<String, Object> request(NewReversal b, String by) {
        if (b.reason() == null || b.reason().trim().length() < 5) throw new IllegalArgumentException("Give the reason for the reversal");
        String target = b.targetRef() == null ? "" : b.targetRef().trim();
        String kind = b.kind() == null ? "" : b.kind().trim().toUpperCase(Locale.ROOT);
        @SuppressWarnings("unchecked") List<Map<String, Object>> options = (List<Map<String, Object>>) care.trace(target).get("options");
        Map<String, Object> o = options.stream().filter(x -> kind.equals(x.get("kind")) && target.equals(x.get("targetRef"))).findFirst()
                .orElseThrow(() -> new IllegalStateException("This cannot be reversed that way (it may already be reversed or not be reversible)"));
        Long complaintId = null;
        if (b.complaintRef() != null && !b.complaintRef().isBlank()) {
            List<Long> ids = jdbc.queryForList("SELECT id FROM complaints WHERE complaint_ref = ?", Long.class, b.complaintRef().trim().toUpperCase(Locale.ROOT));
            if (ids.isEmpty()) throw new IllegalArgumentException("No complaint " + b.complaintRef());
            complaintId = ids.get(0);
        }
        LocalDateTime now = LocalDateTime.now();
        String ref = null;
        for (int i = 0; i < 10 && ref == null; i++) {
            String candidate = "RVS-" + now.format(DateTimeFormatter.ofPattern("yyMMdd")) + "-" + String.format("%05d", RANDOM.nextInt(100_000));
            try {
                jdbc.update("INSERT INTO reversal_requests (reversal_ref, kind, target_ref, amount_minor, currency, summary, reason, complaint_id, status, requested_by, requested_at) "
                                + "VALUES (?,?,?,?,?,?,?,?, 'PENDING_APPROVAL', ?,?)",
                        candidate, kind, target, o.get("amountMinor"), o.get("currency"), CareService.clip((String) o.get("label"), 300),
                        CareService.clip(b.reason().trim(), 1000), complaintId, by, Timestamp.valueOf(now));
                ref = candidate;
            } catch (DuplicateKeyException e) {
                if (!jdbc.queryForList("SELECT 1 FROM reversal_requests WHERE open_target = ?", Integer.class, target).isEmpty())
                    throw new IllegalStateException("A reversal of " + target + " is already waiting");
            }
        }
        if (ref == null) throw new IllegalStateException("Please try again");
        audit.record(by, "REVERSAL_REQUESTED", "reversal:" + ref, kind + " " + target + " " + o.get("currency") + " " + CareService.money(o.get("amountMinor")));
        if (complaintId != null) {
            care.addNote(complaintId, "Reversal " + ref + " requested: " + o.get("label"), by);
            jdbc.update("UPDATE complaints SET status = 'AWAITING_REVERSAL', updated_at = ? WHERE id = ? AND status IN ('OPEN','INVESTIGATING')", Timestamp.valueOf(now), complaintId);
        }
        return get(ref);
    }

    private Map<String, Object> pending(String ref, String status) {
        Map<String, Object> r = get(ref);
        if (!status.equals(r.get("status"))) throw new IllegalStateException("This reversal is " + String.valueOf(r.get("status")).toLowerCase(Locale.ROOT).replace('_', ' '));
        return r;
    }

    private static void fourEyes(Map<String, Object> r, String by) {
        if (by.equalsIgnoreCase(String.valueOf(r.get("requested_by")))) throw new SecurityException("You cannot approve your own reversal request");
    }

    public Map<String, Object> reject(String ref, String note, String by) {
        Map<String, Object> r = pending(ref, "PENDING_APPROVAL");
        fourEyes(r, by);
        if (note == null || note.isBlank()) throw new IllegalArgumentException("Give the reason for rejecting");
        jdbc.update("UPDATE reversal_requests SET status = 'REJECTED', decided_by = ?, decided_at = ?, decision_note = ? WHERE reversal_ref = ? AND status = 'PENDING_APPROVAL'",
                by, Timestamp.valueOf(LocalDateTime.now()), CareService.clip(note.trim(), 1000), ref);
        audit.record(by, "REVERSAL_REJECTED", "reversal:" + ref, note);
        complaintNote(r, "Reversal " + ref + " rejected: " + note.trim(), by);
        return get(ref);
    }

    /** Approves and carries out the reversal. */
    public Map<String, Object> approve(String ref, String note, String by) {
        Map<String, Object> r = pending(ref, "PENDING_APPROVAL");
        fourEyes(r, by);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        int claimed = jdbc.update("UPDATE reversal_requests SET decided_by = ?, decided_at = ?, decision_note = ? WHERE reversal_ref = ? AND status = 'PENDING_APPROVAL' AND decided_by IS NULL",
                by, now, note == null ? null : CareService.clip(note.trim(), 1000), ref);
        if (claimed == 0) throw new IllegalStateException("Someone else is already deciding this reversal");
        String kind = (String) r.get("kind"), target = (String) r.get("target_ref"), reason = ref + ": " + r.get("reason");
        try {
            switch (kind) {
                case "WALLET_REFUND" -> {
                    Map<String, Object> out = walletPayments.refundToWallet(target, reason, by);
                    done(ref, String.valueOf(out.get("entryRef")));
                }
                case "WALLET_REVERSAL" -> {
                    Map<String, Object> out = wallets.reverseOperation(target, reason, by);
                    done(ref, String.valueOf(out.get("entryRef")));
                }
                case "PAYMENT_REFUND" -> {
                    String st = jdbc.queryForObject("SELECT status FROM transactions WHERE reference = ?", String.class, target);
                    if (!"REFUND_PENDING".equals(st)) orchestrator.initiateRefund(target, by, reason);
                    jdbc.update("UPDATE reversal_requests SET status = 'AWAITING_PAYOUT' WHERE reversal_ref = ?", ref);
                }
                default -> throw new IllegalStateException("Unknown reversal kind " + kind);
            }
            audit.record(by, "REVERSAL_APPROVED", "reversal:" + ref, kind + " " + target);
            complaintNote(r, "Reversal " + ref + " approved by " + by + ("PAYMENT_REFUND".equals(kind) ? "; waiting for the payout to the customer" : " and carried out"), by);
        } catch (RuntimeException e) {
            jdbc.update("UPDATE reversal_requests SET status = 'FAILED', error = ? WHERE reversal_ref = ?", CareService.clip(String.valueOf(e.getMessage()), 500), ref);
            audit.record(by, "REVERSAL_FAILED", "reversal:" + ref, e.getMessage());
            complaintNote(r, "Reversal " + ref + " could not be carried out: " + e.getMessage(), by);
        }
        return get(ref);
    }

    /** A payment refund: the payout made to the customer on the provider's side, recorded with its reference. */
    public Map<String, Object> recordPayout(String ref, String payoutReference, String method, String by) {
        Map<String, Object> r = pending(ref, "AWAITING_PAYOUT");
        if (payoutReference == null || payoutReference.isBlank()) throw new IllegalArgumentException("Enter the payout reference");
        String m = method == null || method.isBlank() ? "PROVIDER" : method.trim().toUpperCase(Locale.ROOT);
        if ("MERCHANT_REFUND".equals(r.get("kind"))) {
            // The merchant's balance was already reduced when it refunded; this records the money reaching the customer.
            done(ref, payoutReference.trim());
            merchantRefundPaid(r, payoutReference.trim(), m);
        } else {
            orchestrator.completeManualRefund((String) r.get("target_ref"), by, payoutReference.trim(), m, ref + ": " + r.get("reason"));
            done(ref, payoutReference.trim());
        }
        audit.record(by, "REVERSAL_PAYOUT_RECORDED", "reversal:" + ref, m + " " + payoutReference.trim());
        complaintNote(r, "Refund " + ref + " paid to the customer (" + m + " " + payoutReference.trim() + ")", by);
        return get(ref);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private zw.co.payport.gateway.events.PlatformEvents events;

    /** refund.completed on the event stream, for the merchant's records and PayPort Commerce. */
    private void merchantRefundPaid(Map<String, Object> r, String payoutReference, String method) {
        if (events == null) return;
        String target = String.valueOf(r.get("target_ref"));
        long refundId;
        try { refundId = Long.parseLong(target.substring(target.indexOf(':') + 1)); } catch (RuntimeException e) { return; }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT r.id, r.amount_cents, cs.id AS session_id, cs.reference, cs.currency, cs.merchant_id, cs.livemode "
                + "FROM refunds r JOIN checkout_sessions cs ON cs.id = r.checkout_session_id WHERE r.id = ?", refundId);
        if (rows.isEmpty()) return;
        Map<String, Object> x = rows.get(0);
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("refundId", refundId);
        p.put("checkoutSessionId", x.get("session_id"));
        p.put("reference", x.get("reference"));
        p.put("amountMinor", x.get("amount_cents"));
        p.put("currency", x.get("currency"));
        p.put("reversalRef", r.get("reversal_ref"));
        p.put("payoutMethod", method);
        events.record("refund.completed", ((Number) x.get("merchant_id")).longValue(), "refund_" + refundId, "refund:" + refundId + ":completed",
                Boolean.TRUE.equals(x.get("livemode")) || Integer.valueOf(1).equals(x.get("livemode")), p);
    }

    private void done(String ref, String result) {
        jdbc.update("UPDATE reversal_requests SET status = 'EXECUTED', executed_at = ?, result_ref = ?, error = NULL WHERE reversal_ref = ?",
                Timestamp.valueOf(LocalDateTime.now()), CareService.clip(result, 100), ref);
    }

    private void complaintNote(Map<String, Object> r, String note, String by) {
        if (r.get("complaint_id") != null) care.addNote(((Number) r.get("complaint_id")).longValue(), note, by);
    }
}
