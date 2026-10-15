package zw.co.payport.gateway.psp.ledger.domain;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/** Canonical content hash of a journal entry and its lines. Shared by the writer and the verifier so they cannot drift. */
public final class EntryHashing {

    public record HashLine(int lineNo, String accountCode, String direction, long amountMinor) {}

    private EntryHashing() {}

    public static String hash(String entryRef, String idempotencyKey, String entryType, String currency,
                              LocalDateTime effectiveAt, String reversesEntryRef, List<HashLine> lines) {
        StringBuilder l = new StringBuilder();
        lines.stream()
                .sorted(Comparator.comparingInt(HashLine::lineNo))
                .forEach(x -> l.append(x.lineNo()).append('|').append(x.accountCode()).append('|')
                        .append(x.direction()).append('|').append(x.amountMinor()).append(';'));
        return ChainHasher.contentHash(entryRef, idempotencyKey, entryType, currency,
                effectiveAt != null ? effectiveAt.withNano(0).toString() : null, reversesEntryRef, l.toString());
    }
}
