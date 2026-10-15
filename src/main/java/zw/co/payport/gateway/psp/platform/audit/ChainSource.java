package zw.co.payport.gateway.psp.platform.audit;

import java.time.LocalDateTime;
import java.util.List;

/** An append-only table that can be hash-sealed (ledger journal, audit events). */
public interface ChainSource {

    record Row(long id, String storedHash, String recomputedHash) {}

    String chainName();

    /** Rows with id > afterId created before cutoff, oldest first, at most limit. recomputedHash may be null here. */
    List<Row> sealable(long afterId, LocalDateTime cutoff, int limit);

    /** All rows in [fromId, toId] with both stored and freshly recomputed content hash. */
    List<Row> range(long fromId, long toId);
}
