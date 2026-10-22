package zw.co.payport.gateway.ledger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A request to post one balanced journal entry. Built by posting templates; validated
 * again, authoritatively, by the database (ledger_post).
 *
 * requestHash() identifies the business content (everything except who posted it), so
 * a replay with the same idempotency key is recognised as the same request, and a
 * different request reusing a key is rejected (LEDGER_IDEMPOTENCY_CONFLICT).
 */
public record JournalRequest(
        String idempotencyKey,
        String entity,
        Book book,
        String templateCode,
        Integer templateVersion,
        LocalDate effectiveDate,
        String description,
        String sourceType,
        String sourceRef,
        String postedBy,
        List<JournalLine> lines) {

    public JournalRequest {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(book, "book");
        Objects.requireNonNull(effectiveDate, "effectiveDate");
        Objects.requireNonNull(postedBy, "postedBy");
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 190) throw new IllegalArgumentException("idempotencyKey must be 1-190 chars");
        lines = List.copyOf(lines);
        if (lines.size() < 2) throw new IllegalArgumentException("an entry needs at least two lines");
        for (JournalLine l : lines) {
            if (!l.account().entity().equals(entity) || l.account().book() != book) {
                throw new IllegalArgumentException("line account " + l.account() + " is not in entity " + entity + " / book " + book);
            }
        }
    }

    public static Builder builder(String idempotencyKey) {
        return new Builder(idempotencyKey);
    }

    /** Distinct accounts in first-use order (opened before posting). */
    public Set<AccountKey> accounts() {
        Set<AccountKey> s = new LinkedHashSet<>();
        lines.forEach(l -> s.add(l.account()));
        return s;
    }

    public String requestHash() {
        StringBuilder sb = new StringBuilder();
        sb.append(idempotencyKey).append('|').append(entity).append('|').append(book).append('|')
          .append(nz(templateCode)).append('|').append(templateVersion == null ? "" : templateVersion).append('|')
          .append(effectiveDate).append('|').append(nz(description)).append('|').append(nz(sourceType)).append('|')
          .append(nz(sourceRef));
        for (JournalLine l : lines) {
            sb.append('|').append(l.account().code()).append(',').append(l.direction()).append(',').append(l.amountMinor())
              .append(',').append(nz(l.memo()));
        }
        return sha256(sb.toString());
    }

    /** The JSON document ledger_post expects. */
    String toJson(String entryRef) {
        StringBuilder sb = new StringBuilder(256 + lines.size() * 128);
        sb.append('{');
        field(sb, "idempotency_key", idempotencyKey).append(',');
        field(sb, "request_hash", requestHash()).append(',');
        field(sb, "entry_ref", entryRef).append(',');
        field(sb, "entity", entity).append(',');
        field(sb, "book", book.name()).append(',');
        field(sb, "template_code", templateCode).append(',');
        sb.append("\"template_version\":").append(templateVersion == null ? "null" : templateVersion.toString()).append(',');
        field(sb, "effective_date", effectiveDate.toString()).append(',');
        field(sb, "description", description).append(',');
        field(sb, "source_type", sourceType).append(',');
        field(sb, "source_ref", sourceRef).append(',');
        field(sb, "posted_by", postedBy).append(',');
        sb.append("\"lines\":[");
        for (int i = 0; i < lines.size(); i++) {
            JournalLine l = lines.get(i);
            if (i > 0) sb.append(',');
            sb.append('{');
            field(sb, "account_code", l.account().code()).append(',');
            field(sb, "direction", l.direction().name()).append(',');
            sb.append("\"amount_minor\":").append(l.amountMinor()).append(',');
            field(sb, "memo", l.memo());
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static StringBuilder field(StringBuilder sb, String name, String value) {
        sb.append('"').append(name).append("\":");
        if (value == null) return sb.append("null");
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"');
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final class Builder {
        private final String idempotencyKey;
        private String entity;
        private Book book;
        private String templateCode;
        private Integer templateVersion;
        private LocalDate effectiveDate;
        private String description;
        private String sourceType;
        private String sourceRef;
        private String postedBy;
        private final List<JournalLine> lines = new ArrayList<>();

        private Builder(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

        public Builder entity(String v) { this.entity = v; return this; }
        public Builder book(Book v) { this.book = v; return this; }
        public Builder template(String code, int version) { this.templateCode = code; this.templateVersion = version; return this; }
        public Builder effectiveDate(LocalDate v) { this.effectiveDate = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder source(String type, Object ref) { this.sourceType = type; this.sourceRef = ref == null ? null : String.valueOf(ref); return this; }
        public Builder postedBy(String v) { this.postedBy = v; return this; }
        public Builder debit(AccountKey a, long amount, String memo) { lines.add(JournalLine.debit(a, amount, memo)); return this; }
        public Builder credit(AccountKey a, long amount, String memo) { lines.add(JournalLine.credit(a, amount, memo)); return this; }
        public Builder line(JournalLine l) { lines.add(l); return this; }

        public JournalRequest build() {
            return new JournalRequest(idempotencyKey, entity, book, templateCode, templateVersion, effectiveDate, description,
                    sourceType, sourceRef, postedBy, lines);
        }
    }
}
