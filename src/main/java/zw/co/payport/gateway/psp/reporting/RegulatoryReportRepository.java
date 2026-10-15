package zw.co.payport.gateway.psp.reporting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Report storage plus the read-only aggregate queries that feed returns. */
public interface RegulatoryReportRepository extends JpaRepository<RegulatoryReport, Long> {

    Optional<RegulatoryReport> findByReportRef(String reportRef);

    List<RegulatoryReport> findTop100ByOrderByCreatedAtDesc();

    /** Gateway payments completed in a period: [category, channel, currency, count, value]. */
    @Query(value = "SELECT pt.category, t.channel, t.currency, COUNT(*), COALESCE(SUM(t.amount_cents),0) FROM transactions t " +
            "JOIN product_types pt ON pt.id = t.product_type_id WHERE t.status IN ('COMPLETED','REFUND_PENDING','REFUNDED') " +
            "AND t.payment_confirmed_at >= :from AND t.payment_confirmed_at < :to GROUP BY pt.category, t.channel, t.currency",
            nativeQuery = true)
    List<Object[]> gatewayVolumes(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Payment method mix: [payment_provider_code, currency, count, value]. */
    @Query(value = "SELECT t.payment_provider_code, t.currency, COUNT(*), COALESCE(SUM(t.amount_cents),0) FROM transactions t " +
            "WHERE t.payment_confirmed_at >= :from AND t.payment_confirmed_at < :to GROUP BY t.payment_provider_code, t.currency",
            nativeQuery = true)
    List<Object[]> methodVolumes(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Failed and unknown-outcome payments in a period: [status, count]. */
    @Query(value = "SELECT t.status, COUNT(*) FROM transactions t WHERE t.created_at >= :from AND t.created_at < :to " +
            "AND t.status IN ('PAYMENT_FAILED','PAYMENT_TIMEOUT','FULFILMENT_FAILED') GROUP BY t.status", nativeQuery = true)
    List<Object[]> failures(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Wallet / agent activity: [type, channel, currency, count, value, fees]. */
    @Query(value = "SELECT w.type, w.channel, w.currency, COUNT(*), COALESCE(SUM(w.amount_minor),0), COALESCE(SUM(w.fee_minor),0) " +
            "FROM psp_wallet_transfers w WHERE w.status = 'COMPLETED' AND w.created_at >= :from AND w.created_at < :to " +
            "GROUP BY w.type, w.channel, w.currency", nativeQuery = true)
    List<Object[]> walletVolumes(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query(value = "SELECT COUNT(DISTINCT x.ref) FROM (SELECT from_ref AS ref FROM psp_wallet_transfers WHERE created_at >= :from AND created_at < :to " +
            "AND from_ref LIKE 'cus\\_%' UNION SELECT to_ref FROM psp_wallet_transfers WHERE created_at >= :from AND created_at < :to " +
            "AND to_ref LIKE 'cus\\_%') x", nativeQuery = true)
    long activeWallets(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query(value = "SELECT kyc_tier, COUNT(*) FROM psp_customer_profiles GROUP BY kyc_tier", nativeQuery = true)
    List<Object[]> customersByTier();

    @Query(value = "SELECT status, COUNT(*) FROM psp_agents GROUP BY status", nativeQuery = true)
    List<Object[]> agentsByStatus();

    @Query(value = "SELECT status, COUNT(*) FROM merchants GROUP BY status", nativeQuery = true)
    List<Object[]> merchantsByStatus();

    @Query(value = "SELECT status, COUNT(*), COALESCE(SUM(amount_cents),0) FROM disputes WHERE created_at >= :from AND created_at < :to GROUP BY status",
            nativeQuery = true)
    List<Object[]> disputes(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
