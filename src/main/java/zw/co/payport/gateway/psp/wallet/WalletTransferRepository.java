package zw.co.payport.gateway.psp.wallet;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WalletTransferRepository extends JpaRepository<WalletTransfer, Long> {
    Optional<WalletTransfer> findByTransferRef(String transferRef);

    @Query("SELECT t FROM WalletTransfer t WHERE t.fromRef = :ref OR t.toRef = :ref ORDER BY t.createdAt DESC")
    List<WalletTransfer> history(@Param("ref") String ref, Pageable page);

    /** [sum of amount+fee+tax, count] debited from a wallet owner since a time, completed only. */
    @Query("SELECT COALESCE(SUM(t.amountMinor + t.feeMinor + t.taxMinor), 0), COUNT(t) FROM WalletTransfer t " +
           "WHERE t.fromRef = :ref AND t.currency = :ccy AND t.status = 'COMPLETED' AND t.createdAt >= :since AND t.type IN :types")
    List<Object[]> debitsSince(@Param("ref") String ref, @Param("ccy") String ccy, @Param("since") LocalDateTime since,
                               @Param("types") Collection<String> types);

    /** Agent activity totals since a time: [sum, count]. */
    @Query("SELECT COALESCE(SUM(t.amountMinor), 0), COUNT(t) FROM WalletTransfer t " +
           "WHERE t.agentId = :agentId AND t.type = :type AND t.currency = :ccy AND t.status = 'COMPLETED' AND t.createdAt >= :since")
    List<Object[]> agentTotalsSince(@Param("agentId") Long agentId, @Param("type") String type, @Param("ccy") String ccy,
                                    @Param("since") LocalDateTime since);

    List<WalletTransfer> findByCreatedAtBetweenAndStatus(LocalDateTime from, LocalDateTime to, String status);
}
