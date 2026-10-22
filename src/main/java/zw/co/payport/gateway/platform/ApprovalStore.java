package zw.co.payport.gateway.platform;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Set;

/**
 * Maker-checker requests (platform_approvals). Methods take the caller's Connection so a
 * decision and its effect (e.g. activating a tax rate) commit or roll back together.
 *
 * Rules: only PENDING, unexpired requests can be decided; the decider must not be the
 * requester (also enforced by the database CHECK chk_papp_four_eyes); the decider must hold
 * the request's required role or ADMIN.
 */
public final class ApprovalStore {

    public record Approval(long id, String ref, String actionType, String subjectRef, String payload, String reason,
                           String requiredRole, String status, String requestedBy, String decidedBy, LocalDateTime expiresAt) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private ApprovalStore() {}

    public static Approval request(Connection c, String actionType, String subjectRef, String payload, String reason,
                                   String requiredRole, String requestedBy, int ttlHours) throws SQLException {
        if (reason == null || reason.isBlank()) throw new PlatformException("APPROVAL_REASON", "a reason is required");
        if (requestedBy == null || requestedBy.isBlank()) throw new PlatformException("APPROVAL_ACTOR", "requester required");
        byte[] b = new byte[10];
        RANDOM.nextBytes(b);
        String ref = "apr_" + HexFormat.of().formatHex(b);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO platform_approvals (approval_ref, action_type, subject_ref, payload, reason, required_role, status, "
                        + "requested_by, requested_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, NOW(6), NOW(6) + INTERVAL ? HOUR)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, ref);
            ps.setString(2, actionType);
            ps.setString(3, subjectRef);
            ps.setString(4, payload == null ? "{}" : payload);
            ps.setString(5, reason);
            ps.setString(6, requiredRole);
            ps.setString(7, requestedBy);
            ps.setInt(8, ttlHours);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return load(c, rs.getLong(1), false);
            }
        }
    }

    /** Records an APPROVED or REJECTED decision after checking every rule. Locks the request row. */
    public static Approval decide(Connection c, long approvalId, boolean approve, String decider, Set<String> deciderRoles, String note)
            throws SQLException {
        Approval a = load(c, approvalId, true);
        if (!"PENDING".equals(a.status())) throw new PlatformException("APPROVAL_NOT_PENDING", "request is " + a.status());
        if (a.expiresAt().isBefore(dbNow(c))) {
            setStatus(c, approvalId, "EXPIRED", null, "expired before a decision");
            throw new PlatformException("APPROVAL_EXPIRED", "request expired at " + a.expiresAt());
        }
        if (decider == null || decider.isBlank()) throw new PlatformException("APPROVAL_ACTOR", "decider required");
        if (decider.equals(a.requestedBy())) throw new PlatformException("APPROVAL_SELF", "you cannot decide your own request");
        if (deciderRoles == null || !(deciderRoles.contains(a.requiredRole()) || deciderRoles.contains("ADMIN"))) {
            throw new PlatformException("APPROVAL_ROLE", "requires role " + a.requiredRole());
        }
        setStatus(c, approvalId, approve ? "APPROVED" : "REJECTED", decider, note);
        return load(c, approvalId, false);
    }

    /** The requester may withdraw a pending request. */
    public static void cancel(Connection c, long approvalId, String requester) throws SQLException {
        Approval a = load(c, approvalId, true);
        if (!"PENDING".equals(a.status())) throw new PlatformException("APPROVAL_NOT_PENDING", "request is " + a.status());
        if (!a.requestedBy().equals(requester)) throw new PlatformException("APPROVAL_NOT_OWNER", "only the requester can cancel");
        setStatus(c, approvalId, "CANCELLED", requester, "withdrawn by requester");
    }

    public static Approval load(Connection c, long id, boolean forUpdate) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, approval_ref, action_type, subject_ref, payload, reason, required_role, status, requested_by, decided_by, expires_at "
                        + "FROM platform_approvals WHERE id = ?" + (forUpdate ? " FOR UPDATE" : ""))) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new PlatformException("APPROVAL_NOT_FOUND", "no approval " + id);
                return new Approval(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getTimestamp(11).toLocalDateTime());
            }
        }
    }

    private static void setStatus(Connection c, long id, String status, String decider, String note) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE platform_approvals SET status = ?, decided_by = ?, decided_at = NOW(6), decision_note = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setString(2, decider);
            ps.setString(3, note);
            ps.setLong(4, id);
            ps.executeUpdate();
        }
    }

    private static LocalDateTime dbNow(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT NOW(6)")) {
            rs.next();
            return rs.getTimestamp(1).toLocalDateTime();
        }
    }
}
