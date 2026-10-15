package zw.co.payport.gateway.psp.ledger;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface JournalLineRepository extends JpaRepository<JournalLine, Long> {

    List<JournalLine> findByEntryIdOrderByLineNoAsc(Long entryId);

    List<JournalLine> findByEntryIdInOrderByEntryIdAscLineNoAsc(Collection<Long> entryIds);

    /** [debits, credits] for one account, straight from the journal. */
    @Query("SELECT COALESCE(SUM(CASE WHEN l.direction = zw.co.payport.gateway.psp.ledger.domain.Direction.DEBIT THEN l.amountMinor ELSE 0 END), 0), " +
           "COALESCE(SUM(CASE WHEN l.direction = zw.co.payport.gateway.psp.ledger.domain.Direction.CREDIT THEN l.amountMinor ELSE 0 END), 0) " +
           "FROM JournalLine l WHERE l.accountId = :accountId")
    List<Object[]> sumsForAccount(@Param("accountId") Long accountId);

    /** Same, as at a point in time (for statements and regulatory returns). */
    @Query(value = "SELECT COALESCE(SUM(CASE WHEN l.direction = 'DEBIT' THEN l.amount_minor ELSE 0 END), 0), " +
           "COALESCE(SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount_minor ELSE 0 END), 0) " +
           "FROM psp_journal_lines l JOIN psp_journal_entries e ON e.id = l.entry_id " +
           "WHERE l.account_id = :accountId AND e.effective_at < :asOf", nativeQuery = true)
    List<Object[]> sumsForAccountAsOf(@Param("accountId") Long accountId, @Param("asOf") LocalDateTime asOf);

    /** Per-account totals for every account, used by the nightly projection check. */
    @Query(value = "SELECT l.account_id, " +
           "SUM(CASE WHEN l.direction = 'DEBIT' THEN l.amount_minor ELSE 0 END), " +
           "SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount_minor ELSE 0 END) " +
           "FROM psp_journal_lines l GROUP BY l.account_id", nativeQuery = true)
    List<Object[]> sumsForAllAccounts();

    /** Statement lines with their entry header, newest first. */
    @Query(value = "SELECT l.id, e.entry_ref, e.entry_type, e.effective_at, e.description, e.source_type, e.source_ref, " +
           "l.direction, l.amount_minor, l.memo FROM psp_journal_lines l JOIN psp_journal_entries e ON e.id = l.entry_id " +
           "WHERE l.account_id = :accountId AND e.effective_at >= :from AND e.effective_at < :to " +
           "ORDER BY e.effective_at DESC, l.id DESC", nativeQuery = true)
    List<Object[]> statement(@Param("accountId") Long accountId, @Param("from") LocalDateTime from,
                             @Param("to") LocalDateTime to, Pageable page);

    /** Movement totals per entry type in a window, for regulatory and tax returns. */
    @Query(value = "SELECT e.entry_type, l.direction, COUNT(DISTINCT e.id), SUM(l.amount_minor) " +
           "FROM psp_journal_lines l JOIN psp_journal_entries e ON e.id = l.entry_id " +
           "WHERE l.account_id = :accountId AND e.effective_at >= :from AND e.effective_at < :to " +
           "GROUP BY e.entry_type, l.direction", nativeQuery = true)
    List<Object[]> movementsByType(@Param("accountId") Long accountId, @Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to);

    /** Per-account [account_id, debits, credits] as at a time; one query for trial balances. */
    @Query(value = "SELECT l.account_id, SUM(CASE WHEN l.direction = 'DEBIT' THEN l.amount_minor ELSE 0 END), " +
           "SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount_minor ELSE 0 END) " +
           "FROM psp_journal_lines l JOIN psp_journal_entries e ON e.id = l.entry_id WHERE e.effective_at < :asOf " +
           "GROUP BY l.account_id", nativeQuery = true)
    List<Object[]> accountTotalsAsOf(@Param("asOf") LocalDateTime asOf);
}
