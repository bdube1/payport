package zw.co.payport.gateway.merchant;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.service.MerchantService;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 4 merchant platform rules that need more than one person or a staff decision:
 *
 *  - KYB cases: a merchant is approved only after one staff member RECOMMENDS (checklist complete)
 *    and a DIFFERENT staff member DECIDES (database CHECK as well). Rejection takes the same two steps.
 *  - Settlement destinations: added by the merchant, used for payouts only once PayPort staff
 *    have VERIFIED them; a change starts unverified again.
 *  - Market enablements: which currencies a merchant may take live payments in.
 *  - Live readiness: live API keys need an ACTIVE merchant, an enablement and a verified destination.
 */
@Slf4j
@Service
public class MerchantPlatformService {

    /** What a recommender must confirm before recommending approval. */
    public static final List<String> KYB_CHECKLIST = List.of(
            "registration_documents_verified", "directors_identified", "sanctions_screened", "business_activity_acceptable", "bank_account_verified");

    private final JdbcTemplate jdbc;
    private final MerchantService merchants;
    private final ObjectMapper json;

    public MerchantPlatformService(JdbcTemplate jdbc, MerchantService merchants, ObjectMapper json) {
        this.jdbc = jdbc;
        this.merchants = merchants;
        this.json = json;
    }

    // ─── KYB cases ─────────────────────────────────────────────────────────────

    /** The open case for a merchant, created on first use. */
    @Transactional
    public Map<String, Object> kybCase(long merchantId) {
        List<Map<String, Object>> open = jdbc.queryForList("SELECT * FROM kyb_cases WHERE merchant_id = ? AND status NOT IN ('APPROVED','REJECTED') "
                + "ORDER BY id DESC LIMIT 1", merchantId);
        if (!open.isEmpty()) return open.get(0);
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO kyb_cases (merchant_id, status, checklist, created_at, updated_at) VALUES (?, 'OPEN', '{}', ?, ?)",
                merchantId, Timestamp.valueOf(now), Timestamp.valueOf(now));
        return jdbc.queryForList("SELECT * FROM kyb_cases WHERE merchant_id = ? ORDER BY id DESC LIMIT 1", merchantId).get(0);
    }

    @Transactional
    public Map<String, Object> assign(long merchantId, String staff) {
        Map<String, Object> c = kybCase(merchantId);
        jdbc.update("UPDATE kyb_cases SET assignee = ?, updated_at = ? WHERE id = ?", staff, Timestamp.valueOf(LocalDateTime.now()), c.get("id"));
        return get(((Number) c.get("id")).longValue());
    }

    /**
     * Step 1 (maker): recommend APPROVE (every checklist item confirmed) or REJECT (with a reason).
     */
    @Transactional
    public Map<String, Object> recommend(long merchantId, String decision, Map<String, Boolean> checklist, String note, String staff) {
        String d = decision == null ? "" : decision.trim().toUpperCase();
        if (!d.equals("APPROVE") && !d.equals("REJECT")) throw new IllegalArgumentException("recommendation must be APPROVE or REJECT");
        Map<String, Object> c = kybCase(merchantId);
        if ("APPROVE".equals(d)) {
            // Phase 6: a screening hit holds approval until compliance closes its case (no detail given here).
            Integer openCase = jdbc.queryForObject("SELECT COUNT(*) FROM risk_cases WHERE subject_type = 'MERCHANT' AND subject_key = ? "
                    + "AND status NOT LIKE 'CLOSED%'", Integer.class, "MERCHANT:" + merchantId);
            Integer confirmed = jdbc.queryForObject("SELECT COUNT(*) FROM risk_cases WHERE subject_type = 'MERCHANT' AND subject_key = ? "
                    + "AND status = 'CLOSED_CONFIRMED'", Integer.class, "MERCHANT:" + merchantId);
            if ((openCase != null && openCase > 0) || (confirmed != null && confirmed > 0)) {
                throw new IllegalStateException("Compliance review pending for this merchant: approval is on hold");
            }
            for (String item : KYB_CHECKLIST) {
                if (checklist == null || !Boolean.TRUE.equals(checklist.get(item))) {
                    throw new IllegalArgumentException("checklist item not confirmed: " + item);
                }
            }
        } else if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("a reason is required to recommend rejection");
        }
        String cl;
        try {
            cl = json.writeValueAsString(checklist == null ? Map.of() : checklist);
        } catch (Exception e) {
            throw new IllegalArgumentException("bad checklist");
        }
        jdbc.update("UPDATE kyb_cases SET status = 'AWAITING_DECISION', recommendation = ?, recommended_by = ?, recommended_at = ?, "
                        + "recommendation_note = ?, checklist = ?, updated_at = ? WHERE id = ?",
                d, staff, Timestamp.valueOf(LocalDateTime.now()), note, cl, Timestamp.valueOf(LocalDateTime.now()), c.get("id"));
        log.info("[KYB] Merchant {}: {} recommended by {}", merchantId, d, staff);
        return get(((Number) c.get("id")).longValue());
    }

    public record Decision(Map<String, Object> kybCase, MerchantService.ApprovalResult approval) {}

    /**
     * Step 2 (checker): a different staff member accepts or overrules the recommendation. Accepting
     * an APPROVE recommendation activates the merchant (and issues its first test key).
     */
    @Transactional
    public Decision decide(long merchantId, boolean accept, String note, String staff, Long staffUserId) {
        Map<String, Object> c = kybCase(merchantId);
        if (!"AWAITING_DECISION".equals(c.get("status"))) throw new IllegalStateException("No recommendation to decide on yet");
        if (staff.equals(c.get("recommended_by"))) throw new IllegalStateException("A second person must decide: you made the recommendation");
        String recommended = (String) c.get("recommendation");
        String outcome = accept ? ("APPROVE".equals(recommended) ? "APPROVED" : "REJECTED") : "OPEN";
        LocalDateTime now = LocalDateTime.now();
        if (!accept) {
            jdbc.update("UPDATE kyb_cases SET status = 'OPEN', recommendation = NULL, recommended_by = NULL, recommended_at = NULL, "
                    + "decision_note = ?, updated_at = ? WHERE id = ?", note, Timestamp.valueOf(now), c.get("id"));
            return new Decision(get(((Number) c.get("id")).longValue()), null);
        }
        jdbc.update("UPDATE kyb_cases SET status = ?, decision = ?, decided_by = ?, decided_at = ?, decision_note = ?, updated_at = ? WHERE id = ?",
                outcome, "APPROVED".equals(outcome) ? "APPROVE" : "REJECT", staff, Timestamp.valueOf(now), note, Timestamp.valueOf(now), c.get("id"));
        MerchantService.ApprovalResult approval = null;
        if ("APPROVED".equals(outcome)) {
            approval = merchants.approve(merchantId, staffUserId);
        } else {
            merchants.reject(merchantId, c.get("recommendation_note") != null ? (String) c.get("recommendation_note") : "Application not approved");
        }
        log.info("[KYB] Merchant {} {} (recommended by {}, decided by {})", merchantId, outcome, c.get("recommended_by"), staff);
        return new Decision(get(((Number) c.get("id")).longValue()), approval);
    }

    public Map<String, Object> get(long caseId) {
        return jdbc.queryForMap("SELECT * FROM kyb_cases WHERE id = ?", caseId);
    }

    // ─── settlement destinations ──────────────────────────────────────────────

    @Transactional
    public Map<String, Object> addDestination(long merchantId, String currency, String type, String accountName, String accountNumber,
                                              String institution, String branch, String requestedBy) {
        if (currency == null || currency.isBlank() || accountName == null || accountName.isBlank() || accountNumber == null
                || accountNumber.isBlank() || institution == null || institution.isBlank()) {
            throw new IllegalArgumentException("currency, account name, account number and institution are required");
        }
        String t = type == null ? "BANK" : type.trim().toUpperCase();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO merchant_settlement_destinations (merchant_id, currency, type, account_name, account_number, institution, branch, "
                        + "status, requested_by, requested_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING_VERIFICATION', ?, ?)",
                merchantId, currency.trim().toUpperCase(), t, accountName.trim(), accountNumber.trim(), institution.trim(), branch,
                requestedBy, Timestamp.valueOf(now));
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM merchant_settlement_destinations WHERE merchant_id = ?", Long.class, merchantId);
        log.info("[DESTINATION] Merchant {} added a {} {} destination (#{}), awaiting verification", merchantId, currency, t, id);
        return jdbc.queryForMap("SELECT * FROM merchant_settlement_destinations WHERE id = ?", id);
    }

    /** Staff verification. The verifier must not be the requester; the previous verified destination for the currency is retired. */
    @Transactional
    public Map<String, Object> verifyDestination(long destinationId, boolean approve, String note, String staff) {
        Map<String, Object> d = jdbc.queryForMap("SELECT * FROM merchant_settlement_destinations WHERE id = ? FOR UPDATE", destinationId);
        if (!"PENDING_VERIFICATION".equals(d.get("status"))) throw new IllegalStateException("Destination is " + d.get("status"));
        if (staff.equals(d.get("requested_by"))) throw new IllegalStateException("The requester cannot verify their own destination");
        LocalDateTime now = LocalDateTime.now();
        if (approve) {
            jdbc.update("UPDATE merchant_settlement_destinations SET status = 'RETIRED' WHERE merchant_id = ? AND currency = ? AND status = 'VERIFIED'",
                    d.get("merchant_id"), d.get("currency"));
        }
        jdbc.update("UPDATE merchant_settlement_destinations SET status = ?, verified_by = ?, verified_at = ?, review_note = ? WHERE id = ?",
                approve ? "VERIFIED" : "REJECTED", staff, Timestamp.valueOf(now), note, destinationId);
        return jdbc.queryForMap("SELECT * FROM merchant_settlement_destinations WHERE id = ?", destinationId);
    }

    public List<Map<String, Object>> destinations(long merchantId) {
        return jdbc.queryForList("SELECT id, currency, type, account_name, CONCAT('****', RIGHT(account_number, 4)) AS account_number_masked, "
                + "institution, branch, status, requested_at, verified_at FROM merchant_settlement_destinations WHERE merchant_id = ? ORDER BY id", merchantId);
    }

    /** The verified destination text for payouts, or null. */
    public String verifiedDestination(long merchantId, String currency) {
        List<String> r = jdbc.queryForList("SELECT CONCAT(type, ': ', institution, ' ', COALESCE(branch, ''), ' ', account_number, ' (', account_name, ')') "
                + "FROM merchant_settlement_destinations WHERE merchant_id = ? AND currency = ? AND status = 'VERIFIED' ORDER BY id DESC LIMIT 1",
                String.class, merchantId, currency);
        return r.isEmpty() ? null : r.get(0);
    }

    // ─── market enablements and live readiness ────────────────────────────────

    @Transactional
    public Map<String, Object> enable(long merchantId, String country, String currency, boolean enabled, String staff) {
        String status = (String) jdbc.queryForObject("SELECT status FROM merchants WHERE id = ?", String.class, merchantId);
        if (enabled && !"ACTIVE".equals(status)) throw new IllegalStateException("Only an ACTIVE merchant can be enabled for live payments");
        jdbc.update("INSERT INTO merchant_enablements (merchant_id, country, currency, status, enabled_by, enabled_at) VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE status = VALUES(status), enabled_by = VALUES(enabled_by), enabled_at = VALUES(enabled_at)",
                merchantId, country.toUpperCase(), currency.toUpperCase(), enabled ? "ENABLED" : "DISABLED", staff, Timestamp.valueOf(LocalDateTime.now()));
        return Map.of("merchantId", merchantId, "country", country.toUpperCase(), "currency", currency.toUpperCase(), "status", enabled ? "ENABLED" : "DISABLED");
    }

    public List<Map<String, Object>> enablements(long merchantId) {
        return jdbc.queryForList("SELECT country, currency, status, enabled_at FROM merchant_enablements WHERE merchant_id = ? ORDER BY id", merchantId);
    }

    public boolean liveEnabled(long merchantId, String currency) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM merchant_enablements WHERE merchant_id = ? AND currency = ? AND status = 'ENABLED'",
                Integer.class, merchantId, currency.toUpperCase());
        return n != null && n > 0;
    }

    /** Why the merchant cannot have live keys yet (empty = ready). */
    public List<String> liveBlockers(long merchantId) {
        List<String> out = new java.util.ArrayList<>();
        String status = jdbc.queryForObject("SELECT status FROM merchants WHERE id = ?", String.class, merchantId);
        if (!"ACTIVE".equals(status)) out.add("merchant is " + status + " (KYB approval needed)");
        Integer en = jdbc.queryForObject("SELECT COUNT(*) FROM merchant_enablements WHERE merchant_id = ? AND status = 'ENABLED'", Integer.class, merchantId);
        if (en == null || en == 0) out.add("no currency enabled for live payments");
        Integer dest = jdbc.queryForObject("SELECT COUNT(*) FROM merchant_settlement_destinations WHERE merchant_id = ? AND status = 'VERIFIED'", Integer.class, merchantId);
        if (dest == null || dest == 0) out.add("no verified settlement destination");
        return out;
    }

    public Map<String, Object> readiness(long merchantId) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<String> blockers = liveBlockers(merchantId);
        m.put("liveReady", blockers.isEmpty());
        m.put("blockers", blockers);
        m.put("enablements", enablements(merchantId));
        m.put("destinations", destinations(merchantId));
        return m;
    }
}
