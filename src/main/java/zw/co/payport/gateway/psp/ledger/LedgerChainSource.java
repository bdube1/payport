package zw.co.payport.gateway.psp.ledger;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.psp.ledger.domain.EntryHashing;
import zw.co.payport.gateway.psp.platform.audit.ChainSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Exposes the journal to the hash-chain sealer and verifier. */
@Component
public class LedgerChainSource implements ChainSource {

    private final JournalEntryRepository entries;
    private final JournalLineRepository lines;

    public LedgerChainSource(JournalEntryRepository entries, JournalLineRepository lines) {
        this.entries = entries;
        this.lines = lines;
    }

    @Override
    public String chainName() {
        return "LEDGER";
    }

    @Override
    public List<Row> sealable(long afterId, LocalDateTime cutoff, int limit) {
        return entries.findSealable(afterId, cutoff, PageRequest.of(0, limit)).stream()
                .map(e -> new Row(e.getId(), e.getEntryHash(), null)).toList();
    }

    @Override
    public List<Row> range(long fromId, long toId) {
        List<JournalEntry> es = entries.findRange(fromId, toId);
        if (es.isEmpty()) return List.of();
        Map<Long, List<JournalLine>> byEntry = lines.findByEntryIdInOrderByEntryIdAscLineNoAsc(
                es.stream().map(JournalEntry::getId).toList()).stream().collect(Collectors.groupingBy(JournalLine::getEntryId));
        return es.stream().map(e -> {
            List<EntryHashing.HashLine> hl = byEntry.getOrDefault(e.getId(), List.of()).stream()
                    .map(l -> new EntryHashing.HashLine(l.getLineNo(), l.getAccountCode(), l.getDirection().name(), l.getAmountMinor()))
                    .toList();
            String recomputed = EntryHashing.hash(e.getEntryRef(), e.getIdempotencyKey(), e.getEntryType().name(),
                    e.getCurrency(), e.getEffectiveAt(), e.getReversesEntryRef(), hl);
            return new Row(e.getId(), e.getEntryHash(), recomputed);
        }).toList();
    }
}
