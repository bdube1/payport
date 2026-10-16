package zw.co.payport.gateway.risk;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.platform.ApprovalStore;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Designated authorities within compliance. REPORT_APPROVER (the MLRO/compliance officer authorised
 * to approve reports to the FIU) is requested by an ADMIN and approved by a COMPLIANCE officer who
 * is not the person being designated. Revocation is immediate (any ADMIN), and audited.
 */
@Service
public class ComplianceAuthorities {

    public static final Set<String> AUTHORITIES = Set.of("REPORT_APPROVER");
    private final JdbcTemplate jdbc;
    private final ComplianceAudit audit;

    public ComplianceAuthorities(JdbcTemplate jdbc, ComplianceAudit audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public boolean holds(String holder, String authority) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM compliance_authorities WHERE holder = ? AND authority = ? AND revoked_at IS NULL",
                Integer.class, holder, authority);
        return n != null && n > 0;
    }

    @Transactional
    public Map<String, Object> request(String holder, String authority, String reason, String byAdmin) {
        if (!AUTHORITIES.contains(authority)) throw new IllegalArgumentException("authority must be one of " + AUTHORITIES);
        if (holder == null || !holder.startsWith("staff:")) throw new IllegalArgumentException("holder must be a staff identity (staff:<phone>)");
        String role = jdbc.queryForList("SELECT role FROM users WHERE phone = ?", String.class, holder.substring(6)).stream().findFirst().orElse(null);
        if (!ComplianceService.ROLE.equals(role)) throw new IllegalArgumentException("only a COMPLIANCE user can hold " + authority);
        ApprovalStore.Approval a = jdbc.execute((ConnectionCallback<ApprovalStore.Approval>) c ->
                ApprovalStore.request(c, "COMPLIANCE_AUTHORITY_GRANT", holder, "{\"authority\":\"" + authority + "\"}", reason, ComplianceService.ROLE, byAdmin, 168));
        audit.record(byAdmin, "AUTHORITY_GRANT_REQUESTED", "authority:" + holder, authority);
        return Map.of("approvalId", a.id(), "status", a.status());
    }

    @Transactional
    public Map<String, Object> decide(long approvalId, boolean approve, String note, String by) {
        ApprovalStore.Approval pending = jdbc.execute((ConnectionCallback<ApprovalStore.Approval>) c -> ApprovalStore.load(c, approvalId, true));
        if (!"COMPLIANCE_AUTHORITY_GRANT".equals(pending.actionType())) throw new IllegalStateException("not an authority grant");
        if (by.equals(pending.subjectRef())) throw new IllegalStateException("Nobody approves their own designation");
        ApprovalStore.Approval a = jdbc.execute((ConnectionCallback<ApprovalStore.Approval>) c ->
                ApprovalStore.decide(c, approvalId, approve, by, Set.of(ComplianceService.ROLE), note));
        if (approve) {
            String authority = a.payload().replaceAll(".*\"authority\":\"([A-Z_]+)\".*", "$1");
            jdbc.update("INSERT INTO compliance_authorities (holder, authority, granted_by, granted_at) VALUES (?, ?, ?, ?)",
                    a.subjectRef(), authority, a.requestedBy() + " / " + by, Timestamp.valueOf(LocalDateTime.now()));
        }
        audit.record(by, approve ? "AUTHORITY_GRANTED" : "AUTHORITY_GRANT_REJECTED", "authority:" + a.subjectRef(), a.payload());
        return Map.of("approvalId", a.id(), "status", a.status());
    }

    @Transactional
    public void revoke(String holder, String authority, String by) {
        int n = jdbc.update("UPDATE compliance_authorities SET revoked_at = ?, revoked_by = ? WHERE holder = ? AND authority = ? AND revoked_at IS NULL",
                Timestamp.valueOf(LocalDateTime.now()), by, holder, authority);
        if (n == 0) throw new IllegalStateException(holder + " does not hold " + authority);
        audit.record(by, "AUTHORITY_REVOKED", "authority:" + holder, authority);
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT * FROM compliance_authorities ORDER BY id DESC");
    }
}
