package zw.co.payport.gateway.psp.ledger;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

    Optional<JournalEntry> findByIdempotencyKey(String idempotencyKey);

    Optional<JournalEntry> findByEntryRef(String entryRef);

    Optional<JournalEntry> findByReversesEntryId(Long entryId);

    List<JournalEntry> findBySourceTypeAndSourceRefOrderByIdAsc(String sourceType, String sourceRef);

    @Query("SELECT e FROM JournalEntry e WHERE e.id > :afterId AND e.createdAt < :cutoff ORDER BY e.id ASC")
    List<JournalEntry> findSealable(@Param("afterId") long afterId, @Param("cutoff") LocalDateTime cutoff, Pageable page);

    @Query("SELECT e FROM JournalEntry e WHERE e.id BETWEEN :fromId AND :toId ORDER BY e.id ASC")
    List<JournalEntry> findRange(@Param("fromId") long fromId, @Param("toId") long toId);

    /** Entry ids whose debits and credits do not net to zero. Must always be empty. */
    @Query(value = "SELECT l.entry_id FROM psp_journal_lines l GROUP BY l.entry_id " +
            "HAVING SUM(CASE WHEN l.direction = 'DEBIT' THEN l.amount_minor ELSE -l.amount_minor END) <> 0", nativeQuery = true)
    List<Long> findUnbalancedEntryIds();

    /** Entries with fewer than two lines (a header without its lines means a partial write). */
    @Query(value = "SELECT e.id FROM psp_journal_entries e LEFT JOIN psp_journal_lines l ON l.entry_id = e.id " +
            "GROUP BY e.id HAVING COUNT(l.id) < 2", nativeQuery = true)
    List<Long> findEntriesWithoutLines();
}
