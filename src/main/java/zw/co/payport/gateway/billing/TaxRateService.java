package zw.co.payport.gateway.billing;

import zw.co.payport.gateway.platform.ApprovalStore;
import zw.co.payport.gateway.platform.PlatformException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Effective-dated tax rates (VAT on fees, IMTT) that take effect only after a second person
 * approves them (PHASE1_DESIGN.md section 7). Nothing is seeded: finance enters the rates
 * confirmed by the tax adviser.
 *
 * A new rate must start after the start of the rate currently in force; on approval the
 * previous open-ended rate is closed the day before, so history is never rewritten and a
 * posting for any past date finds the rate that applied on that date.
 */
public class TaxRateService {

    public static final String ACTION = "TAX_RATE_ACTIVATE";
    public static final String REQUIRED_ROLE = "FINANCE";

    public record TaxRate(long id, String entity, String taxCode, String currency, int rateBps, Long capMinor, boolean merchantBorne,
                          LocalDate effectiveFrom, LocalDate effectiveTo, String status, long approvalId, String createdBy, String approvedBy) {}

    private final DataSource dataSource;

    public TaxRateService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Creates a PROPOSED rate and its approval request, together. */
    public TaxRate propose(String entity, String taxCode, String currency, int rateBps, Long capMinor, boolean merchantBorne,
                           LocalDate effectiveFrom, String notes, String proposer) {
        if (effectiveFrom == null) throw new PlatformException("TAX_INVALID", "effective_from is required");
        return inTx(c -> {
            ApprovalStore.Approval a = ApprovalStore.request(c, ACTION, entity + ":" + taxCode + ":" + currency,
                    "{\"rate_bps\":" + rateBps + ",\"effective_from\":\"" + effectiveFrom + "\"}",
                    notes == null || notes.isBlank() ? "Tax rate change" : notes, REQUIRED_ROLE, proposer, 24 * 14);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO fin_tax_rates (entity_code, tax_code, currency, rate_bps, cap_minor, merchant_borne, effective_from, status, "
                            + "approval_id, created_by, created_at, notes) VALUES (?, ?, ?, ?, ?, ?, ?, 'PROPOSED', ?, ?, NOW(6), ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, entity);
                ps.setString(2, taxCode);
                ps.setString(3, currency);
                ps.setInt(4, rateBps);
                if (capMinor == null) ps.setNull(5, Types.BIGINT); else ps.setLong(5, capMinor);
                ps.setBoolean(6, merchantBorne);
                ps.setDate(7, Date.valueOf(effectiveFrom));
                ps.setLong(8, a.id());
                ps.setString(9, proposer);
                ps.setString(10, notes);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    return load(c, rs.getLong(1), false);
                }
            }
        });
    }

    public TaxRate approve(long rateId, String approver, Set<String> approverRoles, String note) {
        return inTx(c -> {
            TaxRate r = load(c, rateId, true);
            if (!"PROPOSED".equals(r.status())) throw new PlatformException("TAX_NOT_PROPOSED", "rate is " + r.status());
            ApprovalStore.decide(c, r.approvalId(), true, approver, approverRoles, note);
            // Lock the rates in force for this key so concurrent approvals serialise.
            List<TaxRate> active = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM fin_tax_rates WHERE entity_code = ? AND tax_code = ? AND currency = ? AND status = 'ACTIVE' FOR UPDATE")) {
                ps.setString(1, r.entity());
                ps.setString(2, r.taxCode());
                ps.setString(3, r.currency());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) active.add(load(c, rs.getLong(1), false));
                }
            }
            for (TaxRate a : active) {
                if (!r.effectiveFrom().isAfter(a.effectiveFrom())) {
                    throw new PlatformException("TAX_OVERLAP", "a new rate must start after " + a.effectiveFrom()
                            + " (the start of the rate in force); history is not rewritten");
                }
                if (a.effectiveTo() == null || !a.effectiveTo().isBefore(r.effectiveFrom())) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE fin_tax_rates SET effective_to = ? WHERE id = ?")) {
                        ps.setDate(1, Date.valueOf(r.effectiveFrom().minusDays(1)));
                        ps.setLong(2, a.id());
                        ps.executeUpdate();
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE fin_tax_rates SET status = 'ACTIVE', approved_by = ?, approved_at = NOW(6) WHERE id = ?")) {
                ps.setString(1, approver);
                ps.setLong(2, rateId);
                ps.executeUpdate();
            }
            return load(c, rateId, false);
        });
    }

    public TaxRate reject(long rateId, String approver, Set<String> approverRoles, String note) {
        return inTx(c -> {
            TaxRate r = load(c, rateId, true);
            if (!"PROPOSED".equals(r.status())) throw new PlatformException("TAX_NOT_PROPOSED", "rate is " + r.status());
            ApprovalStore.decide(c, r.approvalId(), false, approver, approverRoles, note);
            try (PreparedStatement ps = c.prepareStatement("UPDATE fin_tax_rates SET status = 'REJECTED' WHERE id = ?")) {
                ps.setLong(1, rateId);
                ps.executeUpdate();
            }
            return load(c, rateId, false);
        });
    }

    /** The approved rate in force on the given date, if any. */
    public Optional<TaxRate> rateOn(String entity, String taxCode, String currency, LocalDate date) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM fin_tax_rates WHERE entity_code = ? AND tax_code = ? AND currency = ? AND status = 'ACTIVE' "
                        + "AND effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?) ORDER BY effective_from DESC LIMIT 1")) {
            ps.setString(1, entity);
            ps.setString(2, taxCode);
            ps.setString(3, currency);
            ps.setDate(4, Date.valueOf(date));
            ps.setDate(5, Date.valueOf(date));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(load(c, rs.getLong(1), false)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<TaxRate> all(String entity) {
        List<TaxRate> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM fin_tax_rates WHERE entity_code = ? ORDER BY tax_code, currency, effective_from")) {
            ps.setString(1, entity);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(load(c, rs.getLong(1), false));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private static TaxRate load(Connection c, long id, boolean forUpdate) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, entity_code, tax_code, currency, rate_bps, cap_minor, merchant_borne, effective_from, effective_to, status, "
                        + "approval_id, created_by, approved_by FROM fin_tax_rates WHERE id = ?" + (forUpdate ? " FOR UPDATE" : ""))) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new PlatformException("TAX_NOT_FOUND", "no tax rate " + id);
                long cap = rs.getLong(6);
                Long capOrNull = rs.wasNull() ? null : cap;
                Date to = rs.getDate(9);
                return new TaxRate(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), capOrNull,
                        rs.getBoolean(7), rs.getDate(8).toLocalDate(), to == null ? null : to.toLocalDate(), rs.getString(10),
                        rs.getLong(11), rs.getString(12), rs.getString(13));
            }
        }
    }

    @FunctionalInterface
    private interface TxWork<T> { T run(Connection c) throws SQLException; }

    private <T> T inTx(TxWork<T> work) {
        try (Connection c = dataSource.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T out = work.run(c);
                c.commit();
                return out;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                if (e instanceof PlatformException pe) throw pe;
                if (e instanceof SQLException se) {
                    throw new PlatformException("TAX_DB", se.getErrorCode() + " " + se.getMessage());
                }
                throw (RuntimeException) e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
